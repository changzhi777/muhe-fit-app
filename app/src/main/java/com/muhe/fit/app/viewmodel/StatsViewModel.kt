package com.muhe.fit.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhe.fit.app.data.MuheDatabase
import com.muhe.fit.app.lib.StatsCalc
import com.muhe.fit.app.lib.StatsCalc.BwPoint
import com.muhe.fit.app.lib.Streak
import com.muhe.fit.app.sync.SyncStateBus
import com.muhe.fit.app.sync.SyncUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * 统计 Tab 数据源（裁定 #5 修订：统计回归独立 Tab）。聚合全走 StatsCalc 纯函数，
 * Room 只读（既有 getAll，不建新 Dao 方法——ProfileViewModel 同口径）。
 */
data class StatsUiState(
    /** 无训练记录 → 整屏空态引导开练 */
    val hasWorkouts: Boolean,
    val totalWorkouts: Int,
    val monthWorkouts: Int,
    /**
     * 连续训练周数（P9 批 B：瓦片换周口径，JS streakWeeks 移植见 lib/Streak.kt；
     * 原日口径 StatsCalc.dayStreak 函数保留，「我的」页仍用日口径——两页口径差异
     * 注记在 ProfileViewModel）
     */
    val streakWeeks: Int,
    /** 30 天体重变化（已带符号 formatted，null = 窗内不足两点） */
    val bwDelta: String?,
    /** 热图格子：列 = 周（旧→新）× 行 = 周一..周日 */
    val heatColumns: List<List<StatsCalc.HeatCell>>,
    /** 近四周每周组数（升序，末位 = 本周） */
    val weekRows: List<StatsCalc.WeekVolume>,
    val currentWeekSets: Int,
    /** 重点动作 Est.1RM（降序） */
    val estimates: List<StatsCalc.LiftEstimate>,
    /** 体重完整趋势（升序） */
    val bwPoints: List<BwPoint>,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val db: MuheDatabase,
    syncBus: SyncStateBus,
) : ViewModel() {

    var uiState by mutableStateOf<StatsUiState?>(null)
        private set

    init {
        refresh()
        // 同步落地即刷新（2026-11-18 补齐，原注释自承「M1 批次落点」）：停留在统计 Tab 期间
        // 后台 pull 下的新数据不再等到下次重建 VM 才可见（CheckInViewModel 同款口径）
        viewModelScope.launch {
            syncBus.state.collect { if (it is SyncUiState.Ok) refresh() }
        }
    }

    /** 首帧 + 同步落地聚合（Room suspend DAO 自主安全） */
    fun refresh() {
        viewModelScope.launch {
            val today = LocalDate.now()
            // raw 是整条目 JSON 透传（V0.5 八表纪律），坏行跳过；按 start 升序重排供估算/热图
            val workouts = db.workoutDao().getAll()
                .mapNotNull { w ->
                    runCatching { Json.parseToJsonElement(w.raw) as? JsonObject }.getOrNull()
                }
                .sortedBy { (it["start"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }

            val tally = StatsCalc.dailySetTally(workouts)
            val bw = db.bodyweightDao().getAll()
                .mapNotNull { b ->
                    val weight = b.w ?: return@mapNotNull null
                    runCatching { LocalDate.parse(b.d) }.getOrNull()?.let { BwPoint(it, weight) }
                }
                .sortedBy { it.date }

            val weekRows = StatsCalc.weeklySetTotals(tally, today, VOLUME_WEEKS)
            uiState = StatsUiState(
                hasWorkouts = workouts.isNotEmpty(),
                totalWorkouts = workouts.size,
                monthWorkouts = StatsCalc.monthWorkoutCount(tally.keys, today),
                streakWeeks = Streak.streakWeeks(workouts, today),
                bwDelta = StatsCalc.bwDelta30(bw, today)?.let(StatsCalc::fmtSigned),
                heatColumns = StatsCalc.heatGrid(tally, today, HEAT_WEEKS),
                weekRows = weekRows,
                currentWeekSets = weekRows.lastOrNull()?.sets ?: 0,
                estimates = StatsCalc.latestEstimates(workouts),
                bwPoints = bw,
            )
        }
    }

    private companion object {
        const val HEAT_WEEKS = 12
        const val VOLUME_WEEKS = 4
    }
}
