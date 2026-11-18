package com.muhe.fit.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.muhe.fit.app.data.MuheDatabase
import com.muhe.fit.app.data.TokenStore
import com.muhe.fit.app.sync.SyncStateBus
import com.muhe.fit.app.sync.SyncUiState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 我的页数据源（M2 数据充实批次）：登录态 + Room 计数聚合。
 * 零改动 data/ 包（room-builder 属地）：计数走既有 getAll()/getRange() 全量读后内存聚合——
 * 本地健身数据量级小（数百条），KISS 足够；投影列查询随打卡屏批次按需再加。
 * 刷新时机（2026-11-18 修复：原来只有 init 一次，配对成功返回后仍显示访客态）：init +
 * 同步 Ok（首拉/前台 pull 落库后数据即见）+ ProfileScreen ON_RESUME（登录页返回即刷登录态）。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val db: MuheDatabase,
    syncBus: SyncStateBus,
) : ViewModel() {

    /** 登录态：TokenStore 有 token = 已配对（账号卡副标题切换） */
    var paired by mutableStateOf(false)
        private set

    /** 训练历史行副标题：workouts 总条数 */
    var workoutCount by mutableStateOf(0)
        private set

    /** 身体围度行副标题：measurements 总条数（无表的行为保持占位文案） */
    var measurementCount by mutableStateOf(0)
        private set

    /** 本月训练天数：workouts(d) ∪ attendance(ts) 日期并集大小 */
    var monthSessions by mutableStateOf(0)
        private set

    /** 连续训练天数：本月并集内截至最近的连续链（今天未练不视为断链） */
    var monthStreak by mutableStateOf(0)
        private set

    init {
        refresh()
        // 同步落地即刷新（配对首拉 syncNow("pair-redeemed") 落库后，聚合行立即真数）
        viewModelScope.launch {
            syncBus.state.collect { if (it is SyncUiState.Ok) refresh() }
        }
    }

    /** 首帧 + 登录态变更/同步落地后重读（Room suspend DAO 自主安全，免手切 IO） */
    fun refresh() {
        viewModelScope.launch {
            paired = tokenStore.get() != null
            val workouts = db.workoutDao().getAll()
            workoutCount = workouts.size
            measurementCount = db.measurementDao().getAll().size

            val today = LocalDate.now()
            val month = YearMonth.from(today)
            val zone = ZoneId.systemDefault()
            // attendance 查询窗口放宽到整月边界（ts 毫秒；转日期后仍按系统时区过滤一遍）
            val fromMs = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val toMs = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            val monthDays = buildSet {
                workouts.forEach { workout ->
                    val d = runCatching { LocalDate.parse(workout.d) }.getOrNull()
                    if (d != null && YearMonth.from(d) == month) add(d)
                }
                db.attendanceDao().getRange(fromMs, toMs).forEach { attendance ->
                    val d = Instant.ofEpochMilli(attendance.at).atZone(zone).toLocalDate()
                    if (YearMonth.from(d) == month) add(d)
                }
            }
            monthSessions = monthDays.size
            // streak 用全史口径（与 StatsCalc.dayStreak / 打卡屏统计一致）：只看本月会在每月 1 号
            // 把连续链砍成 0/1 天——CheckInViewModel 已裁定这是假信号，两页数字不得互相矛盾。
            // P9 批 B：本页保持**日口径**（「本月训练 N 天 · 连续 N 天」同语境）；统计 Tab 瓦片
            // 已换**周口径**（lib/Streak.kt，JS streakWeeks 移植）——两页口径有意不同，非矛盾
            val allDays = buildSet {
                workouts.forEach { workout ->
                    runCatching { LocalDate.parse(workout.d) }.getOrNull()?.let { add(it) }
                }
                db.attendanceDao().getRange(0, Long.MAX_VALUE).forEach { attendance ->
                    add(Instant.ofEpochMilli(attendance.at).atZone(zone).toLocalDate())
                }
            }
            monthStreak = streakOf(allDays, today)
        }
    }

    /** 退出配对：清 token 回访客态（本地数据不动；同步面由 SyncClient 下次判定跳过） */
    fun logout() {
        viewModelScope.launch {
            tokenStore.clear()
            paired = false
        }
    }

    private companion object {
        /**
         * 连续链口径：今天已练从今天往回数，未练从昨天往回数（进行中的一天不视为断链）；
         * 集合为全史日期（streak 真实跨月/跨年），monthSessions 仍锚本月口径不变。
         */
        fun streakOf(days: Set<LocalDate>, today: LocalDate): Int {
            if (days.isEmpty()) return 0
            var cursor = if (today in days) today else today.minusDays(1)
            var streak = 0
            while (cursor in days) {
                streak++
                cursor = cursor.minusDays(1)
            }
            return streak
        }
    }
}
