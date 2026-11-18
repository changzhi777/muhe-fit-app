package com.muhe.fit.app.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhe.fit.app.R
import com.muhe.fit.app.data.CheckInPrefs
import com.muhe.fit.app.data.MuheDatabase
import com.muhe.fit.app.data.entity.AttendanceEntity
import com.muhe.fit.app.data.entity.GymCardEntity
import com.muhe.fit.app.data.entity.Stamp
import com.muhe.fit.app.lib.moveGymCard
import com.muhe.fit.app.sync.SyncMetaStore
import com.muhe.fit.app.sync.SyncScheduler
import com.muhe.fit.app.sync.SyncStateBus
import com.muhe.fit.app.sync.SyncUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TreeSet
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 打卡屏状态（§3.4）：卡轨数据 + 历史与统计；扫码/权限属易失 UI 态，留在 Screen 层。 */
data class CheckInUiState(
    val cards: List<GymCardEntity> = emptyList(),
    val lastCardId: String? = null,
    /** 历史区当前选中的月份（V1.3 月切换；默认本月）。 */
    val selectedMonth: YearMonth = YearMonth.now(),
    /** 选中月的到场记录（ts 倒序，Dao getRange 排序）。 */
    val monthEntries: List<AttendanceEntity> = emptyList(),
    /** 本月到场次数（统计口径恒锚本月，不随选中月走——设计「本月 N 次 / streak」）。 */
    val monthCount: Int = 0,
    val streakDays: Int = 0,
    /** 到场写入进行中（防双击产生重复条目；协议只增不减，撤销要记删除戳，尽量别误写）。 */
    val savingVisit: Boolean = false,
)

/**
 * 打卡屏 ViewModel（M2）：gymCards 卡轨 + attendance 到场记录的本地读写半。
 * 写入口径与 V0.9 同步协议对齐：本地条目先写 `_ts` = 本地变更时刻（Stamps.kt 注释语义，
 * 上传后以服务端重盖为准），raw 整条目 JSON 为唯一透传重建源（DocEntityMap 反向同构）。
 */
@HiltViewModel
class CheckInViewModel @Inject constructor(
    private val db: MuheDatabase,
    private val meta: SyncMetaStore,
    private val prefs: CheckInPrefs,
    private val syncScheduler: SyncScheduler,
    private val syncBus: SyncStateBus,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    var uiState by mutableStateOf(
        CheckInUiState(lastCardId = prefs.lastGymCardId, selectedMonth = YearMonth.now()),
    )
        private set

    init {
        reload()
        // 同步落地即刷新（server→local 方向闭环，V1.3 注释「同步挂接后拉下来的新卡/新记录
        // 即见」的兑现）：前台恢复 pull 下来他端的卡/到场记录，Ok 一到即重读 Room。
        // StateFlow collect 每 Ok 一次 reload，本地查询 ms 级，不做去抖。
        viewModelScope.launch {
            syncBus.state.collect { if (it is SyncUiState.Ok) reload() }
        }
    }

    /** 卡轨与历史两段一并刷新（进场/前台恢复都可用——同步挂接后拉下来的新数据即见）。 */
    fun reload() {
        viewModelScope.launch {
            uiState = uiState.copy(cards = db.gymCardDao().getAll())
            loadStats()
            loadMonth()
        }
    }

    /**
     * 历史区翻月（offset ±1；钳制上限 = 本月，未来月无数据可看）。
     * 选中月列表独立装载（getRange 单月窗），不牵动本月统计口径。
     */
    fun shiftMonth(offset: Int) {
        val now = YearMonth.now()
        val target = uiState.selectedMonth.plusMonths(offset.toLong())
        val clamped = if (target > now) now else target
        if (clamped == uiState.selectedMonth) return
        uiState = uiState.copy(selectedMonth = clamped)
        viewModelScope.launch { loadMonth() }
    }

    /** 卡轨停在 page 位（HorizontalPager settledPage 驱动）→ 记忆为最后用的卡。 */
    fun onActiveCardChanged(page: Int) {
        val card = uiState.cards.getOrNull(page) ?: return
        if (card.id != uiState.lastCardId) {
            prefs.lastGymCardId = card.id
            uiState = uiState.copy(lastCardId = card.id)
        }
    }

    /**
     * 扫码添加卡（禁手输纪律——值只来自相机解码）。fmt 已过 canRenderFmt 闸
     * （Screen 层拒非 QR），此处只存 value + 归一制式；label 用默认卡名（改名归编辑批次）。
     */
    fun addCard(value: String, fmt: String) {
        if (value.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = newId()
            val label = context.getString(R.string.checkin_card_default)
            val entry = buildJsonObject {
                put("id", id)
                put("label", label)
                put("value", value)
                put("fmt", fmt)
                put("_ts", now)
            }
            db.gymCardDao().upsert(
                GymCardEntity(id = id, label = label, value = value, fmt = fmt, raw = entry.toString(), stamp = Stamp(ts = now)),
            )
            // 新卡即当前卡（CheckIn.jsx commit 语义：s.lastGymCardId = id）
            prefs.lastGymCardId = id
            uiState = uiState.copy(lastCardId = id)
            uiState = uiState.copy(cards = db.gymCardDao().getAll())
            // 数据变更触发源②：落库即挂欠账同步（5s 防抖窗口合并连发写入）
            syncScheduler.requestSync("data-change")
        }
    }

    /**
     * ◀▶ 重排（moveGymCard 语义移植）。顺序落库机制：REPLACE 重插 → rowid 序 = 列表序
     * （getAll 无 ORDER BY 按行号扫描），push 侧 buildLocalDoc 按此序组档——与 JS
     * 「gymCards 数组序即卡轨序」协议语义一致；条目 _ts 不动（排序属数组序，JS 同样只改数组），
     * 文档级 stampDocChange 记欠账让重排随下次 push 上去。
     */
    fun moveCard(from: Int, to: Int) {
        val next = moveGymCard(uiState.cards, from, to)
        if (next == uiState.cards) return
        uiState = uiState.copy(cards = next)
        viewModelScope.launch {
            db.gymCardDao().upsertAll(next)
            meta.stampDocChange(LinkedHashMap(meta.remainder()), System.currentTimeMillis())
            syncScheduler.requestSync("data-change")
        }
    }

    /**
     * 编辑卡（CheckIn.jsx CardSheet commit 语义）：label 留空落默认卡名；value/fmt 只来自重扫
     * （禁手输）。条目级整体覆盖 + 新 `_ts`——协议「同键 _ts 晚者胜」即编辑合并语义；
     * raw 整条目重建保证透传零丢失。[result] false = 卡已在其他设备被删（先查再写，防幽灵写）。
     * 保序落库（2026-11-18 修复）：单条 upsert REPLACE 会给被编辑行发新 rowid，把它挤到卡轨末位
     * （moveCard 依赖 rowid 序 = 列表序）——改为当前列表原位替换后 upsertAll 重插，与 moveCard 同口径。
     */
    fun updateCard(card: GymCardEntity, label: String, value: String, fmt: String, result: (Boolean) -> Unit) {
        if (value.isBlank()) return
        viewModelScope.launch {
            if (db.gymCardDao().getById(card.id) == null) {
                result(false)
                return@launch
            }
            val now = System.currentTimeMillis()
            val finalLabel = label.trim().ifEmpty { context.getString(R.string.checkin_card_default) }
            val updated = card.copy(
                label = finalLabel,
                value = value,
                fmt = fmt,
                raw = buildJsonObject {
                    put("id", card.id)
                    put("label", finalLabel)
                    put("value", value)
                    put("fmt", fmt)
                    put("_ts", now)
                }.toString(),
                stamp = Stamp(ts = now),
            )
            // 原位替换保 rowid 序：getAll 的列表序即卡轨序（见 moveCard 注释）；列表以 DB 实读
            // 为准（uiState.cards 可能尚未装载或过期，整表重插内容等同 REPLACE，无副作用）
            val next = db.gymCardDao().getAll().map { if (it.id == card.id) updated else it }
            db.gymCardDao().upsertAll(next)
            uiState = uiState.copy(cards = db.gymCardDao().getAll())
            syncScheduler.requestSync("data-change")
            result(true)
        }
    }

    /**
     * 删除卡：先记文档级 deleted.gymCards 戳（Dao 注释「同步层负责」的接线点，
     * entryTime = 条目 _ts——DocEntityMap.entryTime 对 gymCards 的语义）再删行；
     * lastGymCardId 落在被删卡上时回第一张（CheckIn.jsx remove 同款善后）。
     */
    fun deleteCard(card: GymCardEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            meta.recordDeletion("gymCards", card.id, card.stamp.ts, now)
            db.gymCardDao().deleteById(card.id)
            if (prefs.lastGymCardId == card.id) {
                val first = db.gymCardDao().getAll().firstOrNull()?.id
                prefs.lastGymCardId = first
                uiState = uiState.copy(lastCardId = first)
            }
            uiState = uiState.copy(cards = db.gymCardDao().getAll())
            syncScheduler.requestSync("data-change")
        }
    }

    /** 「记录到场」：落 attendance {id, gymCardId, ts, note=null}（协议第八类首版 UI 消费）。
     *  savingVisit 走 try/finally——中途异常（DB 损坏/磁盘满）时必须复位，否则按钮永久锁死。 */
    fun checkIn(card: GymCardEntity) {
        if (uiState.savingVisit) return
        uiState = uiState.copy(savingVisit = true)
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val id = newId()
                // note 可空首版恒缺省（协议键缺省 = null，DocEntityMap 读法兼容）；要备注随编辑批次
                val entry = buildJsonObject {
                    put("id", id)
                    put("gymCardId", card.id)
                    put("ts", now)
                }
                db.attendanceDao().upsert(
                    AttendanceEntity(id = id, gymCardId = card.id, at = now, note = null, raw = entry.toString(), stamp = Stamp(ts = now)),
                )
                loadStats()
                loadMonth()
                syncScheduler.requestSync("data-change")
            } finally {
                uiState = uiState.copy(savingVisit = false)
            }
        }
    }

    /**
     * 本月统计装载：次数 = 本月条目；streak 窗口回溯一个月——streak 允许跨月延续，
     * 否则每月 1 号 streak 必清零是假信号。口径恒锚本月（不随历史区选中月走）。
     */
    private suspend fun loadStats() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monthStart = today.withDayOfMonth(1)
        val windowStart = monthStart.minusMonths(1)
        val from = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val window = db.attendanceDao().getRange(from, to)
        val month = window.filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() >= monthStart }
        uiState = uiState.copy(
            monthCount = month.size,
            streakDays = streakOf(window, zone, today),
        )
    }

    /** 选中月列表装载（单月窗：月初起、下月初止）。参数化 + 写回校验——快速翻月时两个
     *  装载协程返回顺序无保证，读「执行时」的 selectedMonth 会把旧月列表写进新月状态。 */
    private suspend fun loadMonth() {
        val month = uiState.selectedMonth
        val zone = ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val entries = db.attendanceDao().getRange(from, to)
        if (month == uiState.selectedMonth) {
            uiState = uiState.copy(monthEntries = entries)
        }
    }

    /**
     * 连续天数：今天有记录从今天起算，没有则从昨天起算（今天还没去 ≠ 断签）；
     * 窗口内逐日回溯计数。
     */
    private fun streakOf(entries: List<AttendanceEntity>, zone: ZoneId, today: LocalDate): Int {
        val days: TreeSet<LocalDate> = TreeSet(java.util.Comparator.reverseOrder())
        entries.forEach { days.add(Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate()) }
        if (days.isEmpty()) return 0
        var day = today
        if (day !in days) day = day.minusDays(1)
        var streak = 0
        while (day in days) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    companion object {
        /** uid()（format.js:140 语义）：时刻 base36 + 6 位随机 base36——本地生成协议 id。 */
        fun newId(): String {
            val t = java.lang.Long.toString(System.currentTimeMillis(), 36)
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
            val r = buildString { repeat(6) { append(alphabet[Random.nextInt(alphabet.length)]) } }
            return t + r
        }
    }
}
