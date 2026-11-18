package com.muhe.fit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhe.fit.app.data.WorkoutSessionPrefs
import com.muhe.fit.app.data.dao.RoutineDao
import com.muhe.fit.app.data.dao.WorkoutDao
import com.muhe.fit.app.data.entity.WorkoutEntity
import com.muhe.fit.app.data.entity.Stamp
import com.muhe.fit.app.lib.FinishWorkout
import com.muhe.fit.app.lib.History
import com.muhe.fit.app.lib.Js
import com.muhe.fit.app.lib.arr
import com.muhe.fit.app.lib.bool
import com.muhe.fit.app.lib.jn
import com.muhe.fit.app.lib.jsTruthy
import com.muhe.fit.app.lib.num
import com.muhe.fit.app.lib.numOr
import com.muhe.fit.app.lib.str
import com.muhe.fit.app.lib.sub
import com.muhe.fit.app.lib.SessionStart
import com.muhe.fit.app.lib.SupersetFlow
import com.muhe.fit.app.lib.WorkoutModel
import com.muhe.fit.app.sync.DocEntityMap
import com.muhe.fit.app.sync.SyncMetaStore
import com.muhe.fit.app.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * Workout 训练会话屏数据源（批 2 · MVP）。
 *
 * 对位 PWA Workout.jsx 的三层状态：
 *   S.active（持久）  → prefs.load() 恢复（WorkoutSessionPrefs）
 *   屏内 cur/highWater → active JSON 的 cur 键 / VM 本地防重放计数
 *   useUI timer（瞬态）→ VM 内 ticker 协程 + _restLeft StateFlow（杀进程即无，符合 PWA 语义）
 *
 * 完成链（对位 sheets.jsx doFinishWorkout L3386）：
 *   sessionEnd（断档判「忘关」）→ buildCompletedWorkout（落盘白名单，剥 planSec/weightOrigin）
 *   → DocEntityMap.workout 落 Room → SyncMetaStore.recordEdit 盖章 → requestSync("data-change")
 *   → prefs.clear() → Summary 态 → 用户确认后回 Idle。
 *
 * MVP 范围（批 3 迭代项）：单 list 视图（无 cards/focus）、组行 stepper+done、称重 sheet 后移
 * （bw 不录）、编辑已存会话后移；批 3 已补：失败组/Drop/Burst/per-side 侧翻/热身化/复制行
 * （Workout.jsx 加重手段面，全走 mutSet/mutSets 共通管线，restSec seed 60 待批 4 UI 滚轮）。
 *
 * 批 3b：superset 配对（History.pairAdjacent/unpairSuperset 相邻强制）+ 编辑已存会话
 * （editingWorkoutId 标记，完成时同 id REPLACE 覆盖）+ backfill 补记（startBackfill 指定过去日期）。
 */
@HiltViewModel
class WorkoutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: WorkoutSessionPrefs,
    private val routineDao: RoutineDao,
    private val workoutDao: WorkoutDao,
    private val meta: SyncMetaStore,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    /** StartChooser 选项（Idle 态列表）：routine raw + 动作数。 */
    data class RoutineOption(val id: String, val name: String, val exCount: Int)

    /** 休息计时浮层状态（useUI timer 对位）：endsAt ms epoch，paused 时冻结 remain。 */
    data class RestTimer(val totalSec: Int, val endsAt: Long, val paused: Boolean, val remainSec: Int)

    /** summary 简版瓦片数据。 */
    data class Summary(val name: String?, val durationMin: Long, val sets: Int, val vol: Double)

    /**
     * 组行参考值（P9 批 T4 · Workout.jsx refLine L253 MVP）：上次会话同位置完成组的 w×r。
     * w/r 任一缺失即无参考（bodyweight 只记 r 的行不出参考线，见 refSetsFor 注记）。
     */
    data class SetRef(val w: Double, val r: Double)

    sealed interface UiState {
        /** 无进行中会话：StartChooser（routine 列表 + 自由练）。 */
        data class Idle(val routines: List<RoutineOption>) : UiState

        /** 会话进行中。 */
        data class Active(
            val active: JsonObject,      // S.active 整条（id/d/start/name/cur/entries/routineIds）
            val entries: List<JsonObject>,
            val cur: Int,
            val doneCount: Int,
            val totalCount: Int,
            val curEntryName: String?,
            val workoutView: String = "list",  // 批 5：布局分叉（PWA Layout 菜单，"list"/"cards"，随档持久化）
            // 🆕 P9 批 T3：折叠已完成开关（Workout.jsx A.collapseCompleted ?? S.collapseCompleted，
            //   存档字段随档持久化；Layout 菜单第四项勾选态 + list/compact 视图折叠过滤共用）
            val collapseCompleted: Boolean = false,
            // 🆕 P9 批 T4：组行参考（refs[entryIdx][setIdx] = 上次会话同位组 w×r；无历史 = 空/null）
            val refs: List<List<SetRef?>> = emptyList(),
        ) : UiState

        /** 完成确认页（FinishSummary 简版）。 */
        data class Done(val summary: Summary) : UiState
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UiState>(UiState.Idle(emptyList()))
    val state: StateFlow<UiState> = _state

    private val _rest = MutableStateFlow<RestTimer?>(null)
    val rest: StateFlow<RestTimer?> = _rest

    private var ticker: kotlinx.coroutines.Job? = null

    /** S.active 内存态（_state.Active.active 的同源引用，写回 prefs 的准绳）。 */
    private var active: JsonObject? = null

    /** progressHighWater（supersetFlow.js setProgressHighWater）：防重勾重放的已完成水位。 */
    private var highWater = mutableMapOf<Int, Int>()

    /** 编辑已存会话标记（批 3b）：非空 = 本次会话源自 loadForEdit，完成时换回原 id 覆盖落库。 */
    private var editingWorkoutId: String? = null

    /**
     * 🆕 P9 批 T4 · 组行参考的历史缓存（Workout.jsx sessionHistory 对位）：Room workouts 全量
     * raw 解析后按 start 升序（JS S.workouts 追加序 = 时间序，lastEntryFor 倒序扫 = 最新优先）。
     * 会话开场（load 恢复 / beginSession / loadForEdit）刷一次，会话内不重扫（参考面只看历史，
     * 与本会话编辑互不影响）；编辑模式下剔除正在编辑的条目自身（JS 编辑态同口径）。
     */
    private var historyCache: List<JsonObject> = emptyList()

    /**
     * 批 5 · 滑动删 undo token（Workout.jsx undoDeleteSet 语义）：entryIdx + 被删前的完整 sets
     * 数组。deleteActiveSet 先存；undoDeleteSet 原样放回后清空。新会话（beginSession /
     * discardWorkout / loadForEdit / finishWorkout）清 token。VM 内存态，进程重启即无（PWA 同）。
     */
    private var lastUndo: Pair<Int, List<JsonObject>>? = null

    // ── 载体 ──────────────────────────────────────────────────────────

    /** 文档余部（exWeights/barWeights/dbLoad/loadKind/plates/barWeights 设置键的家）。 */
    private fun docState(): JsonObject {
        val rem = meta.remainder()
        // routines 在 Room（协议档第三类），组进 st 供 SessionStart 解析
        return rem
    }

    private suspend fun stateWithRoutines(): JsonObject {
        val rs = routineDao.getAll().map { Json.parseToJsonElement(it.raw) }
        return Js.with(docState(), "routines", kotlinx.serialization.json.JsonArray(rs))
    }

    // ── 生命周期 ──────────────────────────────────────────────────────

    /** Focus 布局 Prev/Next：切换当前动作（游标持久化在 active.cur，随会话保存）。 */
    fun setCur(entryIdx: Int) {
        val a = active ?: return
        val n = currentEntries(a).size
        val idx = entryIdx.coerceIn(0, (n - 1).coerceAtLeast(0))
        val upd = Js.with(a, "cur", JsonPrimitive(idx))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    /** 进屏加载：优先消费历史屏的编辑请求（读后即清）→ 有 active 恢复 → 无则列 routines。 */
    fun load() {
        viewModelScope.launch {
            prefs.consumeLastEditId()?.let { id ->
                val e = workoutDao.getById(id)
                if (e != null) {
                    val parsed = runCatching {
                        Json.parseToJsonElement(e.raw) as? JsonObject
                    }.getOrNull()
                    if (parsed != null) {
                        active = parsed
                        editingWorkoutId = id
                        prefs.save(parsed)
                        highWater.clear()
                        refreshHistory() // 🆕 P9 T4：编辑入口同刷缓存（剔除被编辑条目自身）
                        publishActive()
                        return@launch
                    }
                }
            }
            val saved = prefs.load()
            if (saved != null) {
                refreshHistory() // 🆕 P9 T4：参考线历史缓存（publishActive 组 refs 用）
                active = saved
                highWater.clear()
                publishActive()
            } else {
                val rs = routineDao.getAll().map { r ->
                    val raw = Json.parseToJsonElement(r.raw).let { it as? JsonObject } ?: Js.obj()
                    val exN = raw.arr("ex")?.size ?: 0
                    RoutineOption(r.id, r.name ?: r.id, exN)
                }
                _state.value = UiState.Idle(rs)
            }
        }
    }

    /** 开场：routineId null = 自由练（空条目）；否则 SessionStart 组档（session-merge 合并语义）。 */
    fun start(routineId: String?) {
        beginSession(routineId, LocalDate.now().toString())
    }

    /** 补记开场（批 3b）：同 start 组档，但 d 用指定过去日期（补记错过的训练日）。 */
    fun startBackfill(routineId: String, dateIso: String) {
        beginSession(routineId, dateIso)
    }

    /** 组档共通（start/startBackfill 复用）：start 时间戳恒 = 现在，d = 传参（今天或补记日）。 */
    private fun beginSession(routineId: String?, d: String) {
        viewModelScope.launch {
            editingWorkoutId = null
            lastUndo = null
            val st = stateWithRoutines()
            val ids = routineId?.let { listOf(it) } ?: emptyList()
            val result = SessionStart.buildCombinedEntries(st, ids)
            val name = SessionStart.deriveSessionName(
                result.routines.mapNotNull { it.str("name") },
            )
            val now = System.currentTimeMillis()
            val a = Js.obj(
                "id" to JsonPrimitive(UUID.randomUUID().toString().substring(0, 12)),
                "d" to JsonPrimitive(d),
                "start" to JsonPrimitive(now),
                "name" to (name?.let { JsonPrimitive(it) } ?: JsonPrimitive("训练")),
                "cur" to JsonPrimitive(0),
                "routineIds" to kotlinx.serialization.json.JsonArray(
                    result.routineIds.map { JsonPrimitive(it) },
                ),
                "entries" to kotlinx.serialization.json.JsonArray(result.entries),
            )
            prefs.save(a)
            refreshHistory() // 🆕 P9 T4：参考线历史缓存（新会话开场刷一次）
            active = a
            highWater.clear()
            publishActive()
        }
    }

    /** 勾组/取消（PWA toggle 核心 MVP 版）：写 done+at → 休息 → 推进。 */
    fun toggleSet(entryIdx: Int, setIdx: Int) {
        val a = active ?: return
        val entries = currentEntries(a).toMutableList()
        val entry = entries.getOrNull(entryIdx) ?: return
        val sets = entry.arr("sets")?.toMutableList() ?: return
        val cur = sets.getOrNull(setIdx) as? JsonObject ?: return
        val now = System.currentTimeMillis()
        val wasDone = (cur["done"] as? JsonPrimitive)?.booleanOrNull == true

        var next = if (wasDone) {
            // 取消勾选：剥 done+at（PWA undo；重勾补休息的 restOnRecheck 批 3 再进）
            Js.without(Js.without(cur, "done"), "at")
        } else {
            Js.with(Js.with(cur, "done", JsonPrimitive(true)), "at", JsonPrimitive(now))
        }
        sets[setIdx] = next
        entries[entryIdx] = mergeSets(entry, sets)
        val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(entries))
        // entry 整块回写（mergeSets 已含），entries 数组替换
        active = upd
        prefs.save(upd)

        if (wasDone) {
            stopRest()
            publishActive()
            return
        }

        // unitDone = 该动作全部组完成
        val doneSets = currentEntries(upd).getOrNull(entryIdx)?.arr("sets")
            ?.mapNotNull { it as? JsonObject } ?: emptyList()
        val unitDone = doneSets.isNotEmpty() && doneSets.all { jsTruthy(it["done"]) }

        if (!unitDone) {
            // 组间休息：restSecFor（动作 target.restSec > 全局）
            val sec = SupersetFlow.restSecFor(currentEntries(upd), listOf(setIdx), null)
            if (sec > 0) startRest(sec.toInt()) else stopRest()
        } else {
            // 动作完成：动作级休息 + cur 推进到下一个未完成动作
            val unit = History.supersetUnits(
                currentEntries(upd).mapNotNull { it as? JsonObject },
            ).firstOrNull { entryIdx in it }
            val sec = SupersetFlow.restSecFor(
                currentEntries(upd), unit, null,
                nextUnfinishedSetRest(upd, entryIdx),
            )
            if (sec > 0) startRest(sec.toInt()) else stopRest()
            val step = SupersetFlow.supersetFlowStep(
                currentEntries(upd), unit, ((a["cur"] as? JsonPrimitive)?.intOrNull ?: 0),
            )
            if (step != null && step.nextIdx != null) {
                active = Js.with(upd, "cur", JsonPrimitive(step.nextIdx))
            } else {
                active = upd
            }
        }
        publishActive()
    }

    /** stepper：改组字段（重量/次数）。重量改动走 cascadeWeight 级联后续组（History 已移植）。 */
    fun setField(entryIdx: Int, setIdx: Int, key: String, delta: Double) {
        val a = active ?: return
        val entries = currentEntries(a).toMutableList()
        val entry = entries.getOrNull(entryIdx) ?: return
        val sets = entry.arr("sets")?.toMutableList() ?: return
        val set = sets.getOrNull(setIdx) as? JsonObject ?: return
        val cur = set.numValueOf(key) ?: 0.0
        val nv = (cur + delta).coerceAtLeast(0.0)
        sets[setIdx] = Js.with(set, key, JsonPrimitive(nv))
        entries[entryIdx] = mergeSets(entry, sets)
        // 重量级联（history.js cascadeWeight：动作内 sets 数组，from = 组下标）——
        // 改的是 w 且后续未完成组跟随新重量；热身组不级联（函数内自判）
        val cascade = if (key == "w") {
            val cascaded = History.cascadeWeight(
                sets.mapNotNull { it as? JsonObject }, setIdx, nv,
            ).map { it as kotlinx.serialization.json.JsonElement }
            entries.toMutableList().also { it[entryIdx] = mergeSets(entry, cascaded) }
        } else {
            entries
        }
        val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(cascade))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    /** 加一组（copyRowAt：复制最后一组的 w/r 作起点；至少 0 组可无限加）。 */
    fun addSet(entryIdx: Int) {
        val a = active ?: return
        val entries = currentEntries(a).toMutableList()
        val entry = entries.getOrNull(entryIdx) ?: return
        val sets = entry.arr("sets")?.mapNotNull { it as? JsonObject } ?: emptyList()
        val last = sets.lastOrNull()
        val newSet = Js.obj(
            "w" to (last?.get("w") ?: kotlinx.serialization.json.JsonPrimitive(20.0) as kotlinx.serialization.json.JsonElement),
            "r" to (last?.get("r") ?: kotlinx.serialization.json.JsonPrimitive(10.0) as kotlinx.serialization.json.JsonElement),
        )
        val newSets = sets + newSet
        entries[entryIdx] = Js.with(entry, "sets", kotlinx.serialization.json.JsonArray(newSets))
        val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(entries))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    /** 删组（removeRowAt：至少保留一组，删空拒绝）。 */
    fun removeSet(entryIdx: Int, setIdx: Int) {
        val a = active ?: return
        val entries = currentEntries(a).toMutableList()
        val entry = entries.getOrNull(entryIdx) ?: return
        val sets = entry.arr("sets")?.mapNotNull { it as? JsonObject } ?: emptyList()
        if (sets.size <= 1) return
        val newSets = sets.filterIndexed { i, _ -> i != setIdx }
        entries[entryIdx] = Js.with(entry, "sets", kotlinx.serialization.json.JsonArray(newSets))
        val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(entries))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    /** 加热身组（UI 批 3 契约）：转调 makeWarmupAt（history.js:1063，组行转热身同一条 ramp）。 */
    fun addWarmupSet(entryIdx: Int) {
        val a = active ?: return
        val entries = currentEntries(a).toMutableList()
        val entry = entries.getOrNull(entryIdx) ?: return
        val sets = entry.arr("sets")?.mapNotNull { it as? JsonObject } ?: return
        val next = History.makeWarmupAt(sets, sets.size - 1)
        entries[entryIdx] = Js.with(entry, "sets", kotlinx.serialization.json.JsonArray(next))
        val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(entries))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    /** 删 burst（UI 批 3 契约）：转调 removeClusterAt（workout-model.js:237-240）。 */
    fun removeCluster(entryIdx: Int, setIdx: Int, i: Int) {
        mutSet(entryIdx, setIdx) { set -> WorkoutModel.removeClusterAt(set, i) }
    }

    /** 完成会话（doFinishWorkout 链）。 */
    fun finishWorkout() {
        val a = active ?: return
        val now = System.currentTimeMillis().toDouble()
        val end = FinishWorkout.sessionEnd(a, now)
        val built = FinishWorkout.buildCompletedWorkout(a, end)
        // 编辑模式（批 3b）：换回原会话 id，upsertAll REPLACE 覆盖原条目（协议「同 id 覆盖」语义）；
        // 新会话（标记为 null）照批 2 直接落新条目
        val w = editingWorkoutId?.let { eid -> Js.with(built, "id", JsonPrimitive(eid)) } ?: built
        viewModelScope.launch {
            // 盖章先例同 CheckInViewModel：本地条目 _ts = 变更时刻（上传后服务端重盖为准），
            // DocEntityMap.workout 从条目读戳组 Entity（raw 整条透传）
            val nowMs = System.currentTimeMillis()
            val stamped = Js.with(w, "_ts", JsonPrimitive(nowMs))
            val entity = DocEntityMap.workout(stamped)
            workoutDao.upsertAll(listOf(entity))
            meta.recordEdit("workouts", nowMs)
            syncScheduler.requestSync("data-change")
            prefs.clear()
            active = null
            lastUndo = null
            stopRest()
            _state.value = UiState.Done(
                Summary(
                    name = entity.name,
                    durationMin = ((entity.end - entity.start) / 60000L).coerceAtLeast(0),
                    sets = History.setsDoneActive(w),
                    vol = entity.vol ?: 0.0,
                ),
            )
        }
    }

    /** 丢弃会话（Discard）：不落库直接清。 */
    fun discardWorkout() {
        editingWorkoutId = null
        lastUndo = null
        prefs.clear()
        active = null
        stopRest()
        load()
    }

    /** Summary 确认 → 回 Idle（StartChooser）。 */
    fun acknowledgeSummary() {
        editingWorkoutId = null
        _state.value = UiState.Idle(emptyList())
        load()
    }

    // ── 批 3 · 加重手段（Workout.jsx mutSet 面：drop/burst/failure/side/热身/复制）──

    /** 失败组开关（Workout.jsx toggleFailureAt L126 → workout-model.js:49-54）：开加 failure 键、关删键回原样；行级键（Android 单行 sides 形态，无成对翻面）。 */
    fun toggleFailure(entryIdx: Int, setIdx: Int) {
        mutSet(entryIdx, setIdx) { WorkoutModel.toggleFailure(it) }
    }

    /**
     * 加 drop（Workout.jsx addDropRow L130-142）：per-side 行 addSideDrop 两侧各按自己末 drop/侧重
     * seed（workout-model.js:303-309）；普通行 base = 末个 drop w 否则当前 w（workout-model.js:90-93
     * + 135-150），r 沿 prev。🆕 P7b pct 接真配置：entry.target.intensifier（type=dropset）的 pct，
     * 无配置 null → 引擎默认 20%（nextDropWeightCore clamp 1-90；旧批 3 硬 0.5 = 50% 减重系移植
     * 偏差）。dropGrid snap 待器械库存数据面接入（后续批注记）。
     */
    fun addDrop(entryIdx: Int, setIdx: Int) {
        val entry = active?.let { currentEntries(it).getOrNull(entryIdx) as? JsonObject }
        val pct = entry?.sub("target")?.sub("intensifier")
            ?.takeIf { it.str("type") == "dropset" }?.num("pct")
        mutSet(entryIdx, setIdx) { set ->
            if (WorkoutModel.isSideSet(set)) return@mutSet WorkoutModel.addSideDrop(set, pct)
            val drops = WorkoutModel.dropsOf(set)
            val base = if (drops.isNotEmpty()) (drops.lastOrNull() as? JsonObject).numOr("w") else set.numOr("w")
            WorkoutModel.addDrop(set, Js.obj("w" to jn(WorkoutModel.nextDropWeight(base, pct)), "r" to jn(set.numOr("r"))))
        }
    }

    /**
     * 加 burst（Workout.jsx addBurstRow L145-152）：per-side 行 addSideCluster 两侧各按自己末 burst
     * seed 且侧 r 引擎内同步自增（workout-model.js:320-327）；普通行 restpause 的行 r = 全部 burst
     * 总和（PWA L147-151 注释），故行 r 同步 +added（workout-model.js:96-99 + 153-155）。
     * restSec 先固定 60（UI 滚轮批 4）。
     */
    fun addBurst(entryIdx: Int, setIdx: Int) {
        mutSet(entryIdx, setIdx) { set ->
            if (WorkoutModel.isSideSet(set)) return@mutSet WorkoutModel.addSideCluster(set, 60.0)
            val clusters = WorkoutModel.clustersOf(set)
            val base = if (clusters.isNotEmpty()) (clusters.lastOrNull() as? JsonObject).numOr("r") else set.numOr("r")
            val added = WorkoutModel.nextBurstReps(base)
            Js.with(
                WorkoutModel.addCluster(set, Js.obj("r" to jn(added), "restSec" to jn(60.0))),
                "r", jn(set.numOr("r") + added),
            )
        }
    }

    /** 删 drop（Workout.jsx removeDrop L169）：per-side 行两侧对称删同位（workout-model.js:310-312），普通行删单个、清空最后一个回 straight（workout-model.js:102-105）。 */
    fun removeDrop(entryIdx: Int, setIdx: Int, i: Int) {
        mutSet(entryIdx, setIdx) { set ->
            if (WorkoutModel.isSideSet(set)) WorkoutModel.removeSideDropAt(set, i)
            else WorkoutModel.removeDropAt(set, i)
        }
    }

    /** 翻单侧完成（Workout.jsx onToggleSide L1637 → workout-model.js:274-279）：翻该侧 done；行聚合（两侧齐 → 行 done）由引擎 patchSide 内 syncSideAggregate 收口。 */
    fun toggleSideDone(entryIdx: Int, setIdx: Int, side: String) {
        mutSet(entryIdx, setIdx) { WorkoutModel.toggleSide(it, side) }
    }

    /** 行转热身（Workout.jsx setPhaseAt L122 → history.js:1063-1069 makeWarmupAt：留 w/r/at/done、弃 drops/sides/effort，行挪热身区边缘）。 */
    fun makeWarmup(entryIdx: Int, setIdx: Int) {
        mutSets(entryIdx) { History.makeWarmupAt(it, setIdx) }
    }

    /** 复制行（Workout.jsx L861 → history.js:1118-1130 copyRowAt：剥完成态/子行、per-side 成对复制，插在该组之后）。 */
    fun copySet(entryIdx: Int, setIdx: Int) {
        mutSets(entryIdx) { History.copyRowAt(it, setIdx) }
    }

    // ── 批 3b · superset 配对 + 编辑已存会话 + backfill ────────────────

    /** superset 配对（Workout.jsx pairWithPrev → history.js pairAdjacent：与上一条相邻配对；首条 no-op）。 */
    fun pairWithPrev(entryIdx: Int) {
        val a = active ?: return
        val entries = currentEntries(a)
        if (entryIdx <= 0 || entryIdx >= entries.size) return
        try {
            val next = History.pairAdjacent(entries, entryIdx - 1, entryIdx)
            val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(next))
            active = upd
            prefs.save(upd)
            publishActive()
        } catch (_: IllegalArgumentException) {
            // pairAdjacent 相邻/越界校验：UI 已约束入口，防御 no-op
        }
    }

    /** superset 解配（history.js unpairSuperset：摘出该条并清失去同伴的 sg）。 */
    fun unpair(entryIdx: Int) {
        val a = active ?: return
        val entries = currentEntries(a)
        try {
            val next = History.unpairSuperset(entries, entryIdx)
            val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(next))
            active = upd
            prefs.save(upd)
            publishActive()
        } catch (_: IllegalArgumentException) {
            // 越界校验：UI 已约束入口，防御 no-op
        }
    }

    /** 编辑最近一条已存会话（编辑模式入口：getAll 按 end 降序取最近 → loadForEdit）。 */
    fun loadLastForEdit() {
        viewModelScope.launch {
            val latest = workoutDao.getAll().maxByOrNull { it.end } ?: return@launch
            loadForEdit(latest.id)
        }
    }

    /**
     * 编辑已存会话（批 3b）：raw 整条目 JSON 重建 active 档 + editingWorkoutId 标记。
     * 完成时 finishWorkout 将 buildCompletedWorkout 产出的 id 换回原 id → upsertAll REPLACE
     * 覆盖原条目（协议「同 id 覆盖」语义，raw 透传零丢失）。
     */
    fun loadForEdit(workoutId: String) {
        viewModelScope.launch {
            val e = workoutDao.getById(workoutId) ?: return@launch
            val doc = runCatching { Json.parseToJsonElement(e.raw) as? JsonObject }.getOrNull()
                ?: return@launch
            stopRest()
            active = doc
            editingWorkoutId = workoutId
            lastUndo = null
            highWater.clear()
            refreshHistory() // 🆕 P9 T4：编辑态刷缓存（refreshHistory 内已剔除被编辑条目自身）
            publishActive()
        }
    }

    // ── 批 4 · 称重录入 + per-side stepper ────────────────────────────

    /** 开场称重（PWA bwSheet 保存面 → S.active.bw）：写整档 bw 键，随 raw 透传落库（buildCompletedWorkout 白名单含 bw，finishWorkout 自动透传）。UI 取值侧：`(active["bw"] as? JsonPrimitive)?.doubleOrNull`。 */
    fun setBw(w: Double) {
        val a = active ?: return
        val upd = Js.with(a, "bw", JsonPrimitive(w))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    /** per-side stepper（PWA 侧翻行 w/r 步进 → workout-model.js:264-271 setSideField）：当前值 = sides[side][key]，新值 = (cur+delta) 下限 0，经 mutSet 共通管线落 entries → prefs → publish。 */
    fun setSideField(entryIdx: Int, setIdx: Int, side: String, key: String, delta: Double) {
        val a = active ?: return
        val set = currentEntries(a).getOrNull(entryIdx)?.arr("sets")?.getOrNull(setIdx) as? JsonObject ?: return
        val cur = (((set["sides"] as? JsonObject)?.get(side) as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val nv = (cur + delta).coerceAtLeast(0.0)
        mutSet(entryIdx, setIdx) { WorkoutModel.setSideField(it, side, key, JsonPrimitive(nv)) }
    }

    // ── 批 5 · 滑动删除 Undo + 布局持久化（UI 契约，签名锁定）──────────

    /**
     * 滑动删组（UI 批 5 契约；PWA deleteActiveSet 语义）：removeRowAt 摘行（per-side span 感知，
     * L/R 一对一起删；删完会空引擎自 no-op）→ 先存 undo token（entryIdx + 删前完整 sets）→
     * 停该行计时/休息（stopRest）→ 共通管线落 prefs。shiftRowRefs 平移省略（批 2 无 row refs
     * 依赖，注释在案随引用面批次补）。
     */
    fun deleteActiveSet(entryIdx: Int, setIdx: Int) {
        val a = active ?: return
        val entries = currentEntries(a).toMutableList()
        val entry = entries.getOrNull(entryIdx) ?: return
        val sets = entry.arr("sets")?.mapNotNull { it as? JsonObject } ?: return
        if (sets.isEmpty()) return
        val next = History.removeRowAt(sets, setIdx)
        if (next.size == sets.size) return  // removeRowAt 空保护 no-op：不记 token 不落盘
        lastUndo = entryIdx to sets
        entries[entryIdx] = mergeSets(entry, next)
        val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(entries))
        active = upd
        prefs.save(upd)
        stopRest()
        publishActive()
    }

    /** 撤销滑动删（Workout.jsx undoDeleteSet L836）：token 恢复 = 行 + 位置原样放回，清 token。 */
    fun undoDeleteSet() {
        val (entryIdx, sets) = lastUndo ?: return
        val a = active ?: return
        val entries = currentEntries(a).toMutableList()
        val entry = entries.getOrNull(entryIdx) ?: return
        entries[entryIdx] = mergeSets(entry, sets)
        val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(entries))
        active = upd
        prefs.save(upd)
        lastUndo = null
        publishActive()
    }

    /** 布局持久化（PWA Layout 菜单 → S.active.workoutView）：写档随 raw 透传，publish 分叉 UI。 */
    fun setLayout(view: String) {
        val a = active ?: return
        val upd = Js.with(a, "workoutView", JsonPrimitive(view))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    /**
     * 🆕 P9 批 T3 · 折叠已完成开关（Workout.jsx L1353-1355 菜单项 → A.collapseCompleted 翻转）：
     * `s.active.collapseCompleted = !collapseOn` 同款写档（Js.with + prefs.save + publishActive，
     * setLayout 同管线）；存档字段随档持久化，publishActive 读出随 state 发布。
     */
    fun toggleCollapse() {
        val a = active ?: return
        val cur = a.bool("collapseCompleted") ?: false
        val upd = Js.with(a, "collapseCompleted", JsonPrimitive(!cur))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    /** 🆕 P9 批 T4 · 历史缓存刷新：Room 全量 → 剔除编辑中条目 → start 升序 → raw 解析。 */
    private suspend fun refreshHistory() {
        val eid = editingWorkoutId
        historyCache = workoutDao.getAll()
            .filter { eid == null || it.id != eid }
            .sortedBy { it.start }
            .mapNotNull { e ->
                runCatching { Json.parseToJsonElement(e.raw) as? JsonObject }.getOrNull()
            }
    }

    // ── 休息计时 ──────────────────────────────────────────────────────

    fun startRest(sec: Int) {
        val endsAt = System.currentTimeMillis() + sec * 1000L
        _rest.value = RestTimer(sec, endsAt, paused = false, remainSec = sec)
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(250)
                val t = _rest.value ?: break
                if (!t.paused) {
                    val left = ((t.endsAt - System.currentTimeMillis()) / 1000L).toInt()
                    if (left <= 0) { _rest.value = null; break }
                    if (left != t.remainSec) _rest.value = t.copy(remainSec = left)
                }
            }
        }
    }

    fun adjustRest(delta: Int) {
        val t = _rest.value ?: return
        val newEnds = t.endsAt + delta * 1000L
        _rest.value = t.copy(
            endsAt = newEnds,
            totalSec = t.totalSec + delta,
            remainSec = (((newEnds - System.currentTimeMillis()) / 1000L).toInt()).coerceAtLeast(0),
        )
    }

    fun togglePauseRest() {
        val t = _rest.value ?: return
        _rest.value = if (t.paused) {
            val endsAt = System.currentTimeMillis() + t.remainSec * 1000L
            t.copy(paused = false, endsAt = endsAt)
        } else {
            t.copy(paused = true, remainSec = (((t.endsAt - System.currentTimeMillis()) / 1000L).toInt()).coerceAtLeast(0))
        }
    }

    fun skipRest() {
        stopRest()
    }

    private fun stopRest() {
        ticker?.cancel()
        _rest.value = null
    }

    override fun onCleared() {
        ticker?.cancel()
        super.onCleared()
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private fun currentEntries(a: JsonObject): List<JsonObject> =
        a.arr("entries")?.mapNotNull { it as? JsonObject } ?: emptyList()

    /** 批 3 共通管线：单组变换（Workout.jsx mutSet L117 对位）→ entries 回写 → prefs.save → publishActive（盖章口径同批 2：条目级编辑不盖 _ts，文档级 recordEdit 由完成时统一走）。 */
    private fun mutSet(entryIdx: Int, setIdx: Int, fn: (JsonObject) -> JsonObject) {
        val a = active ?: return
        val entries = currentEntries(a).toMutableList()
        val entry = entries.getOrNull(entryIdx) ?: return
        val sets = entry.arr("sets")?.toMutableList() ?: return
        val cur = sets.getOrNull(setIdx) as? JsonObject ?: return
        sets[setIdx] = fn(cur)
        entries[entryIdx] = mergeSets(entry, sets)
        val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(entries))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    /** 批 3 共通管线：sets 数组整体变换（makeWarmupAt/copyRowAt 面）→ entries 回写 → prefs.save → publishActive。 */
    private fun mutSets(entryIdx: Int, fn: (List<JsonObject>) -> List<JsonObject>) {
        val a = active ?: return
        val entries = currentEntries(a).toMutableList()
        val entry = entries.getOrNull(entryIdx) ?: return
        val sets = entry.arr("sets")?.mapNotNull { it as? JsonObject } ?: return
        entries[entryIdx] = mergeSets(entry, fn(sets))
        val upd = Js.with(a, "entries", kotlinx.serialization.json.JsonArray(entries))
        active = upd
        prefs.save(upd)
        publishActive()
    }

    private fun mergeSets(entry: JsonObject, sets: List<kotlinx.serialization.json.JsonElement>): JsonObject =
        Js.with(entry, "sets", kotlinx.serialization.json.JsonArray(sets))

    private fun publishActive() {
        val a = active ?: return
        val entries = currentEntries(a)
        val total = History.setUnitsTotal(entries)
        val done = History.setsDoneActive(a)
        val cur = (a["cur"] as? JsonPrimitive)?.intOrNull ?: 0
        val view = a.str("workoutView") ?: "list"
        // 🆕 P9 批 T3：折叠开关随档读出（JS `A.collapseCompleted ?? S.collapseCompleted`，
        //   Android 无 S 级全局设置档，仅 A 级存档字段）
        val collapseOn = a.bool("collapseCompleted") ?: false
        // 🆕 P9 批 T4：组行参考面（Workout.jsx refLine L238-249 的 `last = lastEntryFor(H, entry.id, entry.rid)`）：
        //   H = sessionHistory 对位——只取会话日之前的会话（补记过去日期时未来会话不作参考），
        //   historyCache 已按 start 升序，lastEntryFor 倒序扫 = 最新优先
        val sessionDay = a.str("d")
        val hist = if (sessionDay == null) historyCache
        else historyCache.filter { (it.str("d") ?: "") < sessionDay }
        val H = Js.obj("workouts" to kotlinx.serialization.json.JsonArray(hist))
        val refs = entries.map { refSetsFor(H, it) }
        _state.value = UiState.Active(
            a, entries, cur, done, total, entries.getOrNull(cur)?.str("name"), view,
            collapseCompleted = collapseOn,
            refs = refs,
        )
    }

    /**
     * 🆕 P9 批 T4 · 单动作参考值（Workout.jsx refLine MVP）：上次会话同位置完成组的 w×r 列表。
     *
     * lastEntryFor 返回的 sets = 已完成非热身组（history.js lastEntryIn L578-580 同滤），
     * 即「工作组序」——热身组不占位，同位对齐的正是 PWA 参考线展示的那组数据。
     * w 或 r 缺失的行不出参考（bodyweight 只记 r：无载重参考线是刻意的，PWA 该列为
     * 「Added」列同一口径）；per-side 行读顶层聚合 w/r（PWA setLabel 同样按聚合展示，MVP 注记）。
     */
    private fun refSetsFor(H: JsonObject, entry: JsonObject): List<SetRef?> {
        val exId = entry.str("id") ?: return emptyList()
        val last = History.lastEntryFor(H, exId, entry.str("rid")) ?: return emptyList()
        return (last.arr("sets") ?: emptyList()).mapNotNull { el ->
            val s = el as? JsonObject ?: return@mapNotNull null
            val w = s.num("w") ?: return@mapNotNull null
            val r = (s.num("r") ?: s.num("reps")) ?: return@mapNotNull null
            SetRef(w, r)
        }
    }

    /** 下一个未完成动作的首个未完成组休息参考（supersetFlow 推进语义 MVP 版）。 */
    private fun nextUnfinishedSetRest(a: JsonObject, fromIdx: Int): JsonObject? = null
}

/** JsonElement 数字取值小工具（set stepper 用；Js.num 收 JsonElement 即可）。 */
private fun JsonObject.numValueOf(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull
