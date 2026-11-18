package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * history.js 的移植子集：progression / finish-workout 实际调用的部分（纯函数，零状态写入）。
 * 另含 superset-meta.js 的 syncSupersetMeta（cleanupSg 的依赖）。来源函数逐一注明 JS 行号。
 * 2026-11-19 会话模型扩充批次：行编辑 / 构建 / 计数 / 展示核心函数（批次头注见下方分隔线）。
 */
object History {

    // ── history.js:51-55 modeOf（记录模式：reps / time / cardio）──
    fun modeOf(cfg: JsonObject?): String {
        val m = cfg.str("mode")
        if (m == "reps" || m == "time" || m == "cardio") return m
        return if (Ex.isCardio(cfg?.get("id"))) "cardio" else "reps"
    }

    // history.js:71 isBw（bodyweight 标记优先，回落目录器材判定）
    fun isBw(cfg: JsonObject?): Boolean {
        if (!cfg.hasNotNull("bodyweight")) return Ex.isBodyweightEq(cfg?.get("id"))
        return jsTruthy(cfg?.get("bodyweight"))
    }

    // history.js:72 / 78 isPerSide / repStep（单侧动作的 rep 步进 = 2）
    fun isPerSide(cfg: JsonObject?): Boolean = jsTruthy(cfg?.get("side"))
    fun repStep(cfg: JsonObject?): Double = if (isPerSide(cfg)) 2.0 else 1.0

    // history.js:327-329 entryExcluded（整场 legacy 旗标 或 条目级 noProg）
    fun entryExcluded(w: JsonObject?, entry: JsonObject?): Boolean =
        w?.bool("excludeFromProgression") == true || entry?.bool("noProg") == true

    // history.js:339-343 entryRoutineId（条目 rid → 有混排即 null → 例程 routineIds[0] / routineId）
    fun entryRoutineId(w: JsonObject?, en: JsonObject?): String? {
        en.str("rid")?.takeIf { it.isNotEmpty() }?.let { return it }
        val anyRid = (w.arr("entries") ?: emptyList()).any { jsTruthy((it as? JsonObject)?.get("rid")) }
        if (anyRid) return null
        val ids = w.arr("routineIds")
        if (ids != null) {
            val first = ids.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.firstOrNull()
            if (first != null) return first
        }
        return w.str("routineId")
    }

    // ── history.js:983-1003 rerampWarmups（热身块朝工作重量重 ramp；step 只支持标量——
    //    JS 的 owned-bells 数组步长（issue #376）随 dumbbells 批次再扩）──
    private fun rungDown(x: Double, step: Double): Double = floor(x / step) * step

    fun rerampWarmups(rows: List<JsonObject>, step: Double = 2.5, floor: Double = 0.0): List<JsonObject> {
        val firstWork = rows.indexOfFirst { !WorkoutModel.isWarmupRow(it) }
        if (firstWork <= 0) return rows
        val target = rows[firstWork].numOr("w")
        if (target <= 0) return rows
        val out = rows.toMutableList()
        var from = 0.0
        for (i in 0 until firstWork) {
            if (jsTruthy(out[i].get("done"))) {
                from = out[i].numOr("w")
                continue
            }
            val w = if (target > from) minOf(target, maxOf(0.0, floor, rungDown(from + (target - from) / 2, step))) else target
            out[i] = if (WorkoutModel.isSideSet(out[i])) {
                val sides = out[i].sub("sides")!!
                WorkoutModel.syncSideAggregate(
                    Js.with(out[i], "sides", Js.obj(
                        "L" to Js.with(sides.sub("L")!!, "w", jn(w)),
                        "R" to Js.with(sides.sub("R")!!, "w", jn(w)),
                    ))
                )
            } else {
                Js.with(out[i], "w", jn(w))
            }
            from = w
        }
        return out
    }

    // ── history.js:13-19 workRowsForMode（完成态无关、模式相符的工作行）──
    private fun workRowsForMode(entry: JsonObject?, mode: String): List<JsonObject> {
        val source = entry ?: Js.emptyObj()
        val target = source.sub("target") ?: source
        val expected = WorkoutModel.normalizeMode(mode, "reps")
        return (source.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject }
            .filter { WorkoutModel.phaseForSet(it) == "work" && WorkoutModel.modeForSet(it, target) == expected }
    }

    // history.js:1163 completedRowsForMode（已完成、非热身）
    private fun completedRowsForMode(entry: JsonObject?, mode: String): List<JsonObject> =
        workRowsForMode(entry, mode).filter { WorkoutModel.hasCompletedWork(it) && !WorkoutModel.isWarmupRow(it) }

    // history.js:1165-1169 metricRowsForEntry
    private val METRIC_MODES = listOf("reps", "time", "cardio")

    private fun metricRowsForEntry(entry: JsonObject?, mode: String): List<JsonObject> {
        val requested = mode.trim().lowercase()
        val resolved = if (METRIC_MODES.contains(requested)) requested else metricModeForEntry(entry)
        return resolved?.let { completedRowsForMode(entry, it) } ?: emptyList()
    }

    // history.js:1173-1178 metricModeForEntry（reps 行优先于 timed/cardio）
    private fun metricModeForEntry(entry: JsonObject?, fallback: String? = null): String? {
        for (mode in METRIC_MODES) {
            if (completedRowsForMode(entry, mode).isNotEmpty()) return mode
        }
        return WorkoutModel.modeForEntry(entry, fallback)
    }

    /** history.js:1206-1252 bestWeightForEntry（已完成工作行的最优载重；辅助器械取轻） */
    fun bestWeightForEntry(entry: JsonObject): Double {
        val target = entry.sub("target") ?: entry
        val workRows = (entry.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject }
            .filter { WorkoutModel.phaseForSet(it) == "work" }
        val repsRows = metricRowsForEntry(entry, "reps")
        val completedRows = if (repsRows.isNotEmpty()) repsRows
        else workRows.filter { WorkoutModel.hasCompletedWork(it) && !WorkoutModel.isWarmupRow(it) }
        val assistedArg: JsonElement = if (entry.str("id") != null) entry.get("id")!! else entry
        val assisted = Ex.isAssisted(assistedArg)
        var best = 0.0
        var hasUsableWeight = false
        for (set in completedRows) {
            val completedSets: List<JsonObject> = if (WorkoutModel.isSideSet(set)) {
                val sides = set.sub("sides")!!
                listOfNotNull(sides.sub("L"), sides.sub("R")).filter { it.bool("done") == true }
            } else listOf(set)
            for (completedSet in completedSets) {
                // JS Number(completedSet?.w)：缺失 → NaN（跳过），JSON null → 0
                val weight = when (val el = completedSet.get("w")) {
                    null -> null
                    is JsonNull -> 0.0
                    else -> el.numValue()
                } ?: continue
                if (assisted && weight <= 0) continue
                best = if (hasUsableWeight) Ex.betterWeight(entry.get("id"), best, weight) else weight
                hasUsableWeight = true
            }
        }
        if (hasUsableWeight) return best
        val parentMode = WorkoutModel.modeForSet(Js.emptyObj(), target)
        val hasNonRepsWorkRow = workRows.any { WorkoutModel.modeForSet(it, target) != "reps" }
        val hasWarmupRow = (entry.arr("sets") ?: emptyList()).any { (it as? JsonObject)?.let(WorkoutModel::isWarmupRow) == true }
        val topWeight = entry.num("topW")
        if (parentMode == "reps" && !hasNonRepsWorkRow && !hasWarmupRow && topWeight != null &&
            (best <= 0 || (if (assisted) topWeight > 0 && topWeight < best else topWeight > best))
        ) best = topWeight
        return best
    }

    // ── superset-meta.js:21-30 setSupersetMeta（写/清组名与组间休息）──
    private const val SG_NAME_MAX = 40

    private fun setSupersetMeta(ex: List<JsonObject>, sg: String, name: String, rest: Double): List<JsonObject> {
        val clean = name.trim().take(SG_NAME_MAX)
        val sec = if (rest > 0) jsRound(rest) else 0.0
        return ex.map { e ->
            if (e.str("sg") != sg) return@map e
            var out = e
            if (clean.isNotEmpty()) out = Js.with(out, "sgName", JsonPrimitive(clean)) else out = Js.without(out, "sgName")
            if (sec > 0) out = Js.with(out, "sgRest", jn(sec)) else out = Js.without(out, "sgRest")
            out
        }
    }

    // ── superset-meta.js:33-44 syncSupersetMeta（非组员立即落 sgName/sgRest，组员按第一发言者归一）──
    fun syncSupersetMeta(ex: List<JsonObject>): List<JsonObject> {
        val groups = LinkedHashMap<String, Pair<String, Double>>()   // sg → (name, rest)
        var out = ex.map { e ->
            val sg = e.str("sg")?.takeIf { it.isNotEmpty() }
            if (sg == null) {
                var stripped = Js.without(e, "sgName")
                stripped = Js.without(stripped, "sgRest")
                return@map stripped
            }
            val g = groups[sg] ?: ("" to 0.0)
            val nm = if (g.first.isEmpty()) (e.str("sgName")?.takeIf { it.isNotEmpty() } ?: g.first) else g.first
            val rst = if (!(g.second > 0)) (e.num("sgRest")?.takeIf { it > 0 } ?: g.second) else g.second
            groups[sg] = nm to rst
            e
        }
        for ((sg, g) in groups) out = setSupersetMeta(out, sg, g.first, g.second)
        return out
    }

    // ── history.js:256-262 cleanupSg（失去相邻同伴的 sg 落键，再同步组元数据）──
    fun cleanupSg(ex: List<JsonObject>): List<JsonObject> {
        var out = ex.mapIndexed { i, e ->
            val sg = e.str("sg")?.takeIf { it.isNotEmpty() } ?: return@mapIndexed e
            val prev = ex.getOrNull(i - 1).str("sg")?.takeIf { it.isNotEmpty() }
            val next = ex.getOrNull(i + 1).str("sg")?.takeIf { it.isNotEmpty() }
            if (prev == sg || next == sg) e else Js.without(e, "sg")
        }
        out = syncSupersetMeta(out)
        return out
    }

    // ═════════════════════════════════════════════════════════════════════════════════════
    // 会话模型扩充批次（2026-11-19）：history.js 的 superset / 行编辑 / 构建 / 计数 / 展示核心。
    // 直接依赖的小函数一并移植进本文件（头注标来源文件与 JS 行号），已移植模块直接复用：
    //   WorkoutModel（workout-model.js）· Ex（exercises.js）· Backoff（backoff.js）·
    //   Dumbbells（dumbbells.js 库存面）· Progression（progression.js，含 isPyramid）。
    // 本批新增依赖（来源标注在各函数）：
    //   workout-model.js  isFailureSet:43-46 · dropsOf:57-60 · clustersOf:63-66 ·
    //                     nextBurstReps:153-155 · nextDropWeight:135-150 · splitBurstReps:163-172
    //   pyramid.js        PYRAMID_MAX:8 · pyramidTargetAt:57-59 · pyramidWeightAt:84-87
    //   bar.js            BAR_EQ:17 · DEFAULT_BAR_KG/LB:20-22 · barWeightFor:54-61
    //   incline.js        hasIncline:48 · inclineFrom:51
    //   speed.js          KMH_PER_MPH:12 · speedLabel:22 · toSpeed:31-34 · fmtSpeed:41
    //   format.js         fmtNum:58-62
    //   dumbbells.js      dbLoadOf:45-48 · ONE_ARM:68 · isOneArm:71 · bellsIn:73 ·
    //                     entryDbLoad:92 · cfgOfEntry:93 · meaningFactor:96-102 ·
    //                     scaleRow:104-112 · entryAs:118-126 · workoutAs:129-140 · historyAs:144-155
    // 勘记：① setLabel 的 i18n t() 以英文包字面量承载（F / L / R / each / total / % incline），
    //   Android 文案收口时统一换 stringResource；② fmtNum 不走 toLocaleString（千分位与 locale
    //   属显示层），返回纯十进制串，小数位固定 1（2 位档由调用方传 decimals）；③ nextDropWeight
    //   的 grid 只支持数值步长——JS 的函数网格是 plates.js dropGrid 前端闭包，不可序列化，不在
    //   纯数据移植面；④ 既有 rerampWarmups 只吃标量步长（勿动），本批 insertWarmupRow 的步长
    //   支持 dumbbells 数组（issue #376，ownedWeightsFor 产物走 ownedFloor）。
    // ═════════════════════════════════════════════════════════════════════════════════════

    // ── history.js:787 / 789 setUnits / doneUnits（单侧行计 2 / 双侧各自计完成）──
    fun setUnits(s: JsonObject): Int = if (WorkoutModel.isSideSet(s)) 2 else 1

    fun doneUnits(s: JsonObject): Int {
        if (WorkoutModel.isSideSet(s)) {
            val sides = s.sub("sides")!!
            return (if (sides.sub("L")?.bool("done") == true) 1 else 0) +
                (if (sides.sub("R")?.bool("done") == true) 1 else 0)
        }
        return if (jsTruthy(s.get("done"))) 1 else 0
    }

    // ── history.js:791 setUnitsTotal（全会话完成单位总数，双侧各计）──
    fun setUnitsTotal(entries: List<JsonObject>?): Int =
        (entries ?: emptyList()).sumOf { e ->
            (e.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject }.sumOf { setUnits(it) }
        }

    // ── history.js:798-802 setsDoneActive（进行中会话 A 的已完成单位数）──
    fun setsDoneActive(A: JsonObject?): Int {
        var n = 0
        (A?.arr("entries") ?: emptyList()).mapNotNull { it as? JsonObject }.forEach { e ->
            (e.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject }.forEach { s -> n += doneUnits(s) }
        }
        return n
    }

    // ── history.js:807-815 supersetUnits（相邻同 sg 归一个显示单位，返回下标分组）──
    fun supersetUnits(items: List<JsonObject>): List<List<Int>> {
        val units = mutableListOf<MutableList<Int>>()
        items.forEachIndexed { i, e ->
            val sg = e.str("sg")
            val prev = items.getOrNull(i - 1)
            if (i > 0 && !sg.isNullOrEmpty() && prev != null && prev.str("sg") == sg) units.last().add(i)
            else units.add(mutableListOf(i))
        }
        return units.map { it.toList() }
    }

    // ── history.js:880 unitOf（含 idx 的单位；无则单元素兜底）──
    fun unitOf(units: List<List<Int>>, idx: Int): List<Int> =
        units.firstOrNull { idx in it } ?: listOf(idx)

    // ── history.js:267-275 contiguousSgGroup（idx 所在连续 sg 组的下标区间）──
    private fun contiguousSgGroup(items: List<JsonObject>, idx: Int): List<Int> {
        val sg = items.getOrNull(idx)?.str("sg")?.takeIf { it.isNotEmpty() } ?: return listOf(idx)
        var first = idx
        var last = idx
        while (first > 0 && items[first - 1].str("sg") == sg) first--
        while (last + 1 < items.size && items[last + 1].str("sg") == sg) last++
        return (first..last).toList()
    }

    // ── history.js:277-283 freshSg（两未分组条目的确定性未占用组 id）──
    private fun freshSg(items: List<JsonObject>, first: Int, second: Int): String {
        val base = "sg-${minOf(first, second)}-${maxOf(first, second)}"
        var sg = base
        var n = 2
        while (items.any { it.str("sg") == sg }) { sg = "$base-$n"; n++ }
        return sg
    }

    // ── history.js:289-303 pairAdjacent（纯配对两侧相邻条目；两侧连续组合并；groupId 可选）──
    fun pairAdjacent(items: List<JsonObject>, first: Int, second: Int, groupId: String? = null): List<JsonObject> {
        require(items.getOrNull(first) != null && items.getOrNull(second) != null) { "Superset entry indexes are invalid" }
        require(abs(first - second) == 1) { "Superset entries must be adjacent" }
        val next = items.map { Js.spread(it) }
        val left = minOf(first, second)
        val right = maxOf(first, second)
        val group = groupId?.takeIf { it.isNotEmpty() }
            ?: next[left].str("sg")?.takeIf { it.isNotEmpty() }
            ?: next[right].str("sg")?.takeIf { it.isNotEmpty() }
            ?: freshSg(next, left, right)
        val members = LinkedHashSet<Int>()
        members.addAll(contiguousSgGroup(next, left))
        members.addAll(contiguousSgGroup(next, right))
        val out = next.toMutableList()
        for (i in members) out[i] = Js.with(out[i], "sg", JsonPrimitive(group))
        return out
    }

    // ── history.js:307-316 unpairSuperset（摘出一员并清失去同伴的 sg）──
    fun unpairSuperset(items: List<JsonObject>, idx: Int): List<JsonObject> {
        require(items.getOrNull(idx) != null) { "Superset entry index is invalid" }
        val next = items.map { Js.spread(it) }.toMutableList()
        next[idx] = Js.without(next[idx], "sg")
        for (i in next.indices) {
            val e = next[i]
            val sg = e.str("sg")?.takeIf { it.isNotEmpty() } ?: continue
            val prev = next.getOrNull(i - 1).str("sg")?.takeIf { it.isNotEmpty() }
            val after = next.getOrNull(i + 1).str("sg")?.takeIf { it.isNotEmpty() }
            if (prev != sg && after != sg) next[i] = Js.without(e, "sg")
        }
        return next
    }

    // ── history.js:101-104 EFFORT（RIR / RPE 双刻度常量组）──
    data class EffortScale(val f: String, val hd: String, val step: Double, val min: Double, val max: Double)

    val EFFORT: Map<String, EffortScale> = linkedMapOf(
        "rir" to EffortScale("rir", "RIR", 0.5, 0.0, 10.0),
        "rpe" to EffortScale("rpe", "RPE", 0.5, 6.0, 10.0),
    )

    // ── history.js:127-130 effortOf（档案记录的刻度；旧 showRir 旗标兜底）──
    fun effortOf(S: JsonObject?): String {
        val e = S?.str("effort")
        if (e == "none" || (e != null && EFFORT.containsKey(e))) return e!!
        return if (S != null && jsTruthy(S.get("showRir"))) "rir" else "none"
    }

    // ── format.js:58-62 fmtNum（显示舍入；勘记②：无 locale 千分位）──
    private fun fmtNum(n: Double, decimals: Int = 1): String {
        if (n.isNaN()) return "NaN"
        val p = if (decimals == 2) 100.0 else 10.0
        val v = jsRound(n * p) / p
        return if (v == floor(v) && abs(v) < 9.0e15) v.toLong().toString() else v.toString()
    }

    // JS 数值内插的字符串形（String(10)="10"、String(7.5)="7.5"）
    private fun numStr(v: Double): String =
        if (v.isFinite() && v == floor(v) && abs(v) < 9.0e15) v.toLong().toString() else v.toString()

    // ── history.js:89-92 fmtSec（秒 → m:ss）──
    fun fmtSec(sec: Double?): String {
        val n = maxOf(0.0, jsRound(sec ?: 0.0))
        return "${(floor(n / 60)).toLong()}:${((n % 60).toLong()).toString().padStart(2, '0')}"
    }

    // ── history.js:132-135 effortTail（"(RIR 2)" 尾注；空返回空串）──
    private fun effortTail(s: JsonObject): String {
        val k = if (s.hasNotNull("rir")) "rir" else if (s.hasNotNull("rpe")) "rpe" else null
        return k?.let { " (${EFFORT[it]!!.hd} ${fmtNum(s.num(it) ?: Double.NaN)})" } ?: ""
    }

    // ── workout-model.js:34-35 / 43-46 isDropSet / isRestPauseSet / isFailureSet ──
    private fun isFailureSet(s: JsonObject?): Boolean =
        s?.bool("failure") == true && !WorkoutModel.isWarmupRow(s)

    private fun dropsOf(set: JsonObject): List<JsonObject> {
        val src = set
        if (WorkoutModel.setType(src) != "dropset") return emptyList()
        return (src.get("drops") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    private fun clustersOf(set: JsonObject): List<JsonObject> {
        val src = set
        if (WorkoutModel.setType(src) != "restpause") return emptyList()
        return (src.get("clusters") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    // ── workout-model.js:135-150 nextDropWeight（pct% 降链一格；grid 数值步长，勘记③）──
    private fun nextDropWeight(prevWeight: Double, pct: JsonElement?, grid: JsonElement?): Double {
        val p = minOf(90.0, maxOf(1.0, (pct as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it != 0.0 } ?: 20.0))
        val prev = maxOf(0.0, if (prevWeight.isNaN()) 0.0 else prevWeight)
        val raw = prev * (1 - p / 100)
        val gridNum = (grid as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        return if (gridNum != null && gridNum > 0) {
            var w = jsRound(raw / gridNum) * gridNum
            if (prev > 0 && w >= prev) w = prev - gridNum
            jsRound(maxOf(0.0, w) * 1000) / 1000
        } else {
            jsRound(raw * 2) / 2
        }
    }

    // ── workout-model.js:153-155 nextBurstReps / 163-172 splitBurstReps（总量对半拆爆发）──
    private fun nextBurstReps(prevReps: Double): Double =
        maxOf(1.0, jsRound((if (prevReps.isNaN()) 0.0 else prevReps) / 2))

    private fun splitBurstReps(total: Double): List<Double> {
        val bursts = mutableListOf<Double>()
        var remaining = maxOf(0.0, jsRound(if (total.isNaN()) 0.0 else total))
        while (remaining > 0) {
            val burst = minOf(remaining, nextBurstReps(remaining))
            bursts.add(burst)
            remaining -= burst
        }
        return bursts
    }

    // ── history.js:661-663 applyIntensifierPlan（先 shape 后 failure 标记）──
    fun applyIntensifierPlan(sets: List<JsonObject>, cfg: JsonObject?, grid: JsonElement? = null): List<JsonObject> =
        applyFailurePlan(shapeRows(sets, cfg, grid), cfg)

    // ── history.js:672-680 applyFailurePlan（lastToFailure：末工作行标 failure）──
    fun applyFailurePlan(sets: List<JsonObject>, cfg: JsonObject?): List<JsonObject> {
        if (cfg == null || cfg.bool("lastToFailure") != true || modeOf(cfg) == "cardio") return sets
        var last = -1
        sets.forEachIndexed { i, s -> if (!WorkoutModel.isWarmupRow(s)) last = i }
        val from = if (last > 0 && sets.getOrNull(last)?.str("side") == "R" &&
            sets.getOrNull(last - 1)?.str("side") == "L") last - 1 else last
        if (last < 0) return sets
        return sets.mapIndexed { i, s ->
            if (i >= from && i <= last && !jsTruthy(s.get("done"))) Js.with(s, "failure", JsonPrimitive(true)) else s
        }
    }

    // ── history.js:682-730 shapeRows（dropset 盖降链 / restpause 重排两行）──
    private fun shapeRows(sets: List<JsonObject>, cfg: JsonObject?, grid: JsonElement?): List<JsonObject> {
        val intens = cfg?.sub("intensifier")
        val kind = intens?.str("type")
        if (kind != "dropset" && kind != "restpause") return sets
        if (kind == "dropset") {
            val count = maxOf(1.0, intens.num("count")?.let { jsRound(it) } ?: 1.0).toInt()
            val pct = intens.get("pct")
            fun withDrops(row: JsonObject): JsonObject {
                var w = row.numOr("w")
                val drops = ArrayList<JsonObject>()
                for (k in 0 until count) {
                    w = nextDropWeight(w, pct, grid)
                    drops.add(Js.obj("w" to jn(w), "r" to row.num("r")?.let(::jn)))
                }
                return Js.with(Js.with(row, "type", JsonPrimitive("dropset")), "drops", JsonArray(drops))
            }
            return sets.map { s ->
                when {
                    WorkoutModel.isWarmupRow(s) -> s
                    WorkoutModel.isSideSet(s) -> {
                        val sides = s.sub("sides")!!
                        WorkoutModel.syncSideAggregate(
                            Js.with(s, "sides", Js.obj(
                                "L" to withDrops(sides.sub("L") ?: Js.emptyObj()),
                                "R" to withDrops(sides.sub("R") ?: Js.emptyObj()),
                            ))
                        )
                    }
                    else -> withDrops(s)
                }
            }
        }
        // rest-pause 恒两行：热身一行（动作自带 reps）+ 单个 rest-pause 工作行
        val restSec = maxOf(5.0, intens.num("restSec")?.takeIf { it != 0.0 } ?: 15.0)
        val totalReps = maxOf(1.0, intens.num("totalReps")?.let { jsRound(it) } ?: 1.0)
        val w = (sets.firstOrNull { !WorkoutModel.isWarmupRow(it) } ?: sets.firstOrNull() ?: Js.emptyObj()).numOr("w")
        val warmup = Js.obj(
            "w" to jn(w),
            "r" to jn(maxOf(1.0, cfg.num("reps")?.let { jsRound(it) } ?: 1.0)),
            "done" to JsonPrimitive(false),
            "phase" to JsonPrimitive("warmup"),
        )
        val work = Js.obj(
            "w" to jn(w), "r" to jn(totalReps), "done" to JsonPrimitive(false),
            "type" to JsonPrimitive("restpause"),
            "clusters" to JsonArray(splitBurstReps(totalReps).map { Js.obj("r" to jn(it), "restSec" to jn(restSec)) }),
        )
        if (isPerSide(cfg)) {
            val source = sets.firstOrNull { !WorkoutModel.isWarmupRow(it) }
            fun side(key: String, reps: Double): JsonObject = Js.obj(
                "w" to jn(source?.sub("sides")?.sub(key)?.num("w") ?: w),
                "r" to jn(reps), "done" to JsonPrimitive(false), "type" to JsonPrimitive("restpause"),
                "clusters" to JsonArray(splitBurstReps(reps).map { Js.obj("r" to jn(it), "restSec" to jn(restSec)) }),
            )
            return listOf(
                WorkoutModel.makeSideSet(warmup),
                WorkoutModel.syncSideAggregate(Js.with(work, "sides", Js.obj(
                    "L" to side("L", ceil(totalReps / 2)),
                    "R" to side("R", floor(totalReps / 2)),
                ))),
            )
        }
        return listOf(warmup, work)
    }

    // ── history.js:914-949 cascadeWeight（显式改重向后续继承工作组级联；manual/done 保护）──
    fun cascadeWeight(
        rows: List<JsonObject>, from: Int, value: Double?, side: String? = null, backoffStep: Double = 0.0,
    ): List<JsonObject> {
        val source = rows.getOrNull(from)
        if (source == null || WorkoutModel.isWarmupRow(source)) return rows.toList()
        val sides: List<String>? = if (WorkoutModel.isSideSet(source)) (side?.let { listOf(it) } ?: listOf("L", "R")) else null
        val next = rows.toMutableList()
        val stepDown = backoffStep > 0 && value != null
        var k = 0
        fun setWeight(row: JsonObject, kk: Int): JsonObject {
            val wEl: JsonElement? = if (value == null) null
            else if (stepDown) jn(Backoff.backoffAt(value, kk.toDouble(), backoffStep)) else jn(value)
            return Js.with(row, "w", wEl)
        }
        fun setSideWeight(row: JsonObject, key: String, kk: Int): JsonObject {
            val current = row.sub("sides")?.sub(key) ?: return row
            if (current.bool("done") == true || current.str("weightOrigin") == WorkoutModel.WEIGHT_ORIGIN_MANUAL) return row
            val sidesObj = row.sub("sides")!!
            return WorkoutModel.syncSideAggregate(Js.with(row, "sides", Js.with(sidesObj, key, setWeight(current, kk))))
        }
        for (j in from + 1 until next.size) {
            val row = next[j]
            if (WorkoutModel.isWarmupRow(row)) continue
            k++
            if (sides != null) {
                if (!WorkoutModel.isSideSet(row)) continue
                var out = row
                for (key in sides) out = setSideWeight(out, key, k)
                next[j] = out
            } else if (!WorkoutModel.isSideSet(row) && !jsTruthy(row.get("done")) &&
                row.str("weightOrigin") != WorkoutModel.WEIGHT_ORIGIN_MANUAL
            ) {
                next[j] = setWeight(row, k)
            }
        }
        return next
    }

    // ── bar.js:17 / 20-22 / 54-61 barWeightFor（杆重：覆写 → 器材默认；非杆 null）──
    private val BAR_EQ = setOf("barbell", "olympic barbell", "ez barbell", "smith machine", "trap bar")
    private val DEFAULT_BAR_KG = mapOf(
        "barbell" to 20.0, "olympic barbell" to 20.0, "ez barbell" to 10.0, "smith machine" to 9.0, "trap bar" to 25.0,
    )
    private val DEFAULT_BAR_LB = mapOf(
        "barbell" to 45.0, "olympic barbell" to 45.0, "ez barbell" to 25.0, "smith machine" to 20.0, "trap bar" to 55.0,
    )

    private fun barWeightFor(S: JsonObject?, exId: String?): Double? {
        val eq = Ex.eqOf(exId?.let { JsonPrimitive(it) }) ?: return null
        if (!BAR_EQ.contains(eq)) return null
        val own = if (exId != null) S?.sub("barWeights")?.num(exId) else null
        if (own == 0.0) return 0.0
        if (own != null && own > 0) return own
        return (if (S.str("unit") == "lb") DEFAULT_BAR_LB else DEFAULT_BAR_KG)[eq]
    }

    // ── history.js:543 / 550 MAX_PLANNED_WARMUPS / barFloor（杆为暖身下限）──
    const val MAX_PLANNED_WARMUPS = 5

    fun barFloor(S: JsonObject?, exId: String?): Double = barWeightFor(S, exId) ?: 0.0

    // ── pyramid.js:8 / 57-59 / 84-87 PYRAMID_MAX / pyramidTargetAt / pyramidWeightAt ──
    private const val PYRAMID_MAX = "max"

    private fun pyramidTargetAt(list: List<JsonElement>, i: Int): JsonElement =
        if (list.isEmpty()) JsonNull else if (i < list.size) list[i] else list[list.size - 1]

    private fun pyramidWeightAt(cfg: JsonObject?, i: Int): Double {
        val py = cfg?.arr("pyramidWeight")
        if (!Progression.isPyramid(cfg) || py.isNullOrEmpty()) return 0.0
        return maxOf(0.0, (pyramidTargetAt(py, i) as? JsonPrimitive)?.doubleOrNull ?: 0.0)
    }

    // ── history.js:358-364 / 366-392 / 348 lastEntryFor / lastEntryIn / entryIn ──
    private fun entryIn(w: JsonObject, exId: String?, rid: String?): JsonObject? =
        (w.arr("entries") ?: emptyList()).mapNotNull { it as? JsonObject }.firstOrNull { e ->
            e.str("id") == exId && (rid.isNullOrEmpty() || entryRoutineId(w, e) == rid)
        }

    private fun lastEntryIn(S: JsonObject?, exId: String?, rid: String?): JsonObject? {
        val workouts = S?.arr("workouts") ?: emptyList()
        for (i in workouts.indices.reversed()) {
            val w = workouts[i] as? JsonObject ?: continue
            val en = entryIn(w, exId, rid) ?: continue
            if (entryExcluded(w, en)) continue
            val done = (en.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject }
                .filter { it.bool("done") == true && !WorkoutModel.isWarmupRow(it) }
            if (done.isNotEmpty()) {
                val slot = entryRoutineId(w, en)
                var out = Js.obj(
                    "d" to w.get("d"),
                    "sets" to JsonArray(done),
                    "target" to (en.get("target") ?: JsonNull),
                )
                if (!slot.isNullOrEmpty()) out = Js.with(out, "rid", JsonPrimitive(slot))
                if (jsTruthy(en.get("planned"))) out = Js.with(out, "planned", en.get("planned"))
                return out
            }
        }
        return null
    }

    fun lastEntryFor(S: JsonObject?, exId: String?, rid: String? = null): JsonObject? {
        if (!rid.isNullOrEmpty()) lastEntryIn(S, exId, rid)?.let { return it }
        return lastEntryIn(S, exId, null)
    }

    // ── history.js:431-443 freestyleConfig（自由添加以上次实际训练的目标开档）──
    fun freestyleConfig(S: JsonObject?, cfg: JsonObject): JsonObject {
        val last = lastEntryFor(S, cfg.str("id")) ?: return Js.spread(cfg)
        val lastTarget = Js.without(last.sub("target") ?: Js.emptyObj(), "dbLoad")
        return Js.spread(
            cfg, lastTarget,
            Js.obj(
                "id" to cfg.get("id"),
                "sets" to jn(maxOf(1.0, (last.arr("sets")?.size ?: 0).toDouble())),
            ),
        )
    }

    // ── dumbbells.js:45-48 / 68 / 71 / 73 dbLoadOf / ONE_ARM / isOneArm / bellsIn ──
    private fun dbLoadOf(value: JsonElement?): String? {
        val m = if (value is JsonObject) value.str("mode") else (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (m == "each" || m == "total" || m == "as") m else null
    }

    private val ONE_ARM = Regex("\\b(one|single)[- ]?(arm|hand|handed)\\b", RegexOption.IGNORE_CASE)

    private fun isOneArm(cfg: JsonObject?): Boolean =
        jsTruthy(cfg?.get("side")) || ONE_ARM.containsMatchIn(Ex.exName(cfg?.get("id")) ?: "")

    private fun bellsIn(cfg: JsonObject?): Int = if (isOneArm(cfg)) 1 else 2

    // ── dumbbells.js:92-93 / 96-102 entryDbLoad / cfgOfEntry / meaningFactor ──
    private fun entryDbLoad(entry: JsonObject): String = dbLoadOf(entry.sub("target")?.get("dbLoad")) ?: "as"

    private fun cfgOfEntry(entry: JsonObject): JsonObject =
        Js.spread(entry.sub("target"), Js.obj("id" to entry.get("id")))

    private fun meaningFactor(from: String, to: String, cfg: JsonObject): Double {
        if (from == to || from == "as" || to == "as") return 1.0
        val n = bellsIn(cfg)
        if (n == 1) return 1.0
        return if (from == "each") n.toDouble() else 1.0 / n
    }

    // ── dumbbells.js:104-112 scaleRow（按含义因子缩放一行及其 drops/sides）──
    private fun scaleRow(row: JsonObject, f: Double): JsonObject {
        val out = LinkedHashMap(row)
        row.num("w")?.let { out["w"] = jn(jsRound(it * f * 1000) / 1000) }
        (row.get("drops") as? JsonArray)?.let { drops ->
            out["drops"] = JsonArray(drops.map { dEl ->
                (dEl as? JsonObject)?.let { d -> Js.with(d, "w", jn(jsRound(d.numOr("w") * f * 1000) / 1000)) } ?: dEl
            })
        }
        row.sub("sides")?.let { sides ->
            out["sides"] = JsonObject(sides.mapValues { (_, s) -> (s as? JsonObject)?.let { scaleRow(it, f) } ?: s })
        }
        return JsonObject(out)
    }

    // ── dumbbells.js:118-126 / 129-140 entryAs / workoutAs（按 `to` 含义读数的副本）──
    private fun entryAs(entry: JsonObject, to: String): JsonObject {
        val from = entryDbLoad(entry)
        val f = meaningFactor(from, to, cfgOfEntry(entry))
        if (f == 1.0) return entry
        var out = Js.with(
            Js.with(entry, "target", Js.with(entry.sub("target") ?: Js.emptyObj(), "dbLoad", JsonPrimitive(to))),
            "sets",
            JsonArray((entry.arr("sets") ?: emptyList()).map { sEl -> (sEl as? JsonObject)?.let { scaleRow(it, f) } ?: sEl }),
        )
        if ((entry.num("topW") ?: 0.0) > 0) out = Js.with(out, "topW", jn(jsRound(entry.num("topW")!! * f * 1000) / 1000))
        return out
    }

    private fun workoutAs(w: JsonObject, exId: String?, to: String): JsonObject {
        if (to.isEmpty() || to == "as" || w.arr("entries") == null) return w
        var changed = false
        val entries = w.arr("entries")!!.map { eEl ->
            val e = eEl as? JsonObject ?: return@map eEl
            if (e.str("id") != exId) return@map eEl
            val next = entryAs(e, to)
            if (next !== e) changed = true
            next
        }
        return if (changed) Js.with(w, "entries", JsonArray(entries)) else w
    }

    // ── dumbbells.js:144-155 historyAs（整档按 `to` 读数的比较态）──
    private fun historyAs(S: JsonObject?, exId: String?, to: String?): JsonObject? {
        if (S == null || to.isNullOrEmpty() || to == "as" || S.arr("workouts") == null) return S
        var changed = false
        val workouts = S.arr("workouts")!!.map { wEl ->
            val next = (wEl as? JsonObject)?.let { workoutAs(it, exId, to) } ?: wEl
            if (next !== wEl) changed = true
            next
        }
        return if (changed) Js.with(S, "workouts", JsonArray(workouts)) else S
    }

    // ── history.js:444-457 bestWeightFor（全史最优载重；`as` 以指定含义折算后比较）──
    fun bestWeightFor(S: JsonObject?, exId: String?, asMeaning: String? = null): Double {
        val H = if (!asMeaning.isNullOrEmpty() && asMeaning != "as") historyAs(S, exId, asMeaning) else S
        var best = 0.0
        val idEl: JsonElement? = exId?.let { JsonPrimitive(it) }
        for (wEl in H?.arr("workouts") ?: emptyList()) {
            val w = wEl as? JsonObject ?: continue
            for (eEl in w.arr("entries") ?: emptyList()) {
                val e = eEl as? JsonObject ?: continue
                if (e.str("id") != exId) continue
                val entryBest = bestWeightForEntry(e)
                if (entryBest > 0) best = if (best > 0) Ex.betterWeight(idEl, best, entryBest) else entryBest
            }
        }
        return best
    }

    // ── incline.js:48 / 51 hasIncline / inclineFrom（坡度随行携带）──
    private fun hasIncline(s: JsonObject?): Boolean = (s?.num("incline") ?: 0.0) > 0

    private fun inclineFrom(s: JsonObject?): JsonElement? =
        if (hasIncline(s)) Js.obj("incline" to jn(s!!.num("incline")!!)) else null

    // ── history.js:531-540 buildSets（工作行 + 计划热身 ramp；勘记④步长支持数组）──
    fun buildSets(S: JsonObject?, cfg: JsonObject?, options: JsonObject? = null): List<JsonObject> {
        val rows = buildWorkSets(S, cfg, options)
        val warm = maxOf(0.0, minOf(MAX_PLANNED_WARMUPS.toDouble(), cfg.num("warmupSets")?.let { jsRound(it) } ?: 0.0)).toInt()
        if (warm == 0) return rows
        val mode = modeOf(cfg)
        var out = rows
        val floor = barFloor(S, cfg.str("id"))
        for (i in 0 until warm) out = insertWarmupRow(out, mode, cfg, options?.get("step"), floor)
        return out
    }

    // ── history.js:552-639 buildWorkSets（三模式行构建；金字塔/单侧/上次会话种子）──
    private fun buildWorkSets(S: JsonObject?, cfg: JsonObject?, options: JsonObject?): List<JsonObject> {
        val preferLast = jsTruthy(options?.get("preferLast"))
        val useTarget = jsTruthy(options?.get("useTarget"))
        val last = lastEntryFor(S, cfg.str("id"), options?.str("rid"))
        val n = maxOf(1.0, cfg.num("sets")?.takeIf { it != 0.0 } ?: 1.0)
        val nInt = ceil(n).toInt()
        val mode = modeOf(cfg)
        val sets = mutableListOf<JsonObject>()
        fun prevAt(i: Int): JsonObject? {
            if (useTarget || last == null) return null
            val ls = (last.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject }
            return ls.getOrNull(i) ?: ls.lastOrNull()
        }
        val planReps = jsTruthy(options?.get("planReps")) && !preferLast && (cfg.num("reps") ?: 0.0) > 0

        if (mode == "cardio") {
            for (i in 0 until nInt) {
                val prev = prevAt(i)
                val mn: Double? = when {
                    prev != null -> prev.num("min")
                    else -> (cfg.num("min")?.takeIf { it != 0.0 } ?: 20.0)
                }
                val sp: Double? = when {
                    prev != null -> prev.num("speed")
                    else -> (cfg.num("speed")?.takeIf { it != 0.0 } ?: 8.0)
                }
                sets.add(Js.obj(
                    "min" to mn?.let(::jn),
                    "speed" to sp?.let(::jn),
                    "incline" to inclineFrom(prev),
                    "done" to JsonPrimitive(false),
                ))
            }
            return sets
        }
        if (mode == "time") {
            val count = if (isPerSide(cfg)) nInt * 2 else nInt
            for (i in 0 until count) {
                val prev = prevAt(i)
                val carried = prev?.takeIf { (it.num("sec") ?: 0.0) > 0 }
                val sec = carried?.num("sec") ?: (cfg.num("sec")?.takeIf { it != 0.0 } ?: 45.0)
                val w = carried?.numOr("w") ?: cfg.numOr("weight")
                var row = Js.obj("sec" to jn(sec), "w" to jn(w), "done" to JsonPrimitive(false))
                if (isPerSide(cfg)) row = Js.with(row, "side", JsonPrimitive(if (i % 2 == 0) "L" else "R"))
                sets.add(row)
            }
            return sets
        }
        val conf = cfg.str("id")?.let { S?.sub("exWeights")?.sub(it) }
        for (i in 0 until nInt) {
            val prev = prevAt(i)
            val usable = prev?.takeIf { (it.num("r") ?: 0.0) > 0 }
            val lastRegular = last?.let { l ->
                val ls = (l.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject }
                ls.getOrNull(i) ?: ls.lastOrNull()
            }
            val w: Double? = if (useTarget) {
                val cw = cfg.num("weight")
                if (cw != null && cw > 0) cw
                else if (lastRegular != null && (lastRegular.num("r") ?: 0.0) > 0) lastRegular.num("w")
                else cw
            } else if (usable != null) {
                usable.num("w")
            } else {
                val cw2 = conf?.num("w")
                if (cw2 != null && cw2 > 0) cw2 else cfg.num("weight")
            }
            val rEl: JsonElement? = if (planReps || usable == null) {
                cfg?.get("reps")?.takeIf { it !is JsonNull }
            } else {
                usable?.let { u -> u.get("r")?.takeIf { e -> e !is JsonNull } }
            }
            var row = Js.obj("w" to w?.let(::jn), "r" to rEl, "done" to JsonPrimitive(false))
            if (Progression.isPyramid(cfg)) {
                val target = pyramidTargetAt(cfg.arr("pyramid")!!, i)
                val isMax = target == JsonPrimitive(PYRAMID_MAX)
                val seed = usable ?: lastRegular?.takeIf { (it.num("r") ?: 0.0) > 0 }
                row = if (isMax) {
                    val rr = Js.with(row, "r", if (seed != null) seed.get("r")?.takeIf { it !is JsonNull } else jn(0.0))
                    Js.with(rr, "max", JsonPrimitive(true))
                } else {
                    Js.with(row, "r", target)
                }
                val planned = pyramidWeightAt(cfg, i)
                row = when {
                    planned > 0 && !(preferLast && usable != null) -> Js.with(row, "w", jn(planned))
                    useTarget && seed != null -> Js.with(row, "w", seed.get("w")?.takeIf { it !is JsonNull })
                    else -> row
                }
            }
            sets.add(
                if (isPerSide(cfg)) {
                    if (usable != null && WorkoutModel.isSideSet(usable)) seedSideFromLast(row, usable, planReps)
                    else WorkoutModel.makeSideSet(row)
                } else row
            )
        }
        return sets
    }

    // ── history.js:645-650 seedSideFromLast（上次单侧行的两肢搬运）──
    private fun seedSideFromLast(row: JsonObject, prev: JsonObject, planReps: Boolean): JsonObject {
        val base = WorkoutModel.makeSideSet(row)
        if (!WorkoutModel.isSideSet(prev)) return base
        val baseSides = base.sub("sides")!!
        val prevSides = prev.sub("sides")!!
        fun carry(s: JsonObject?, key: String): JsonObject = Js.obj(
            "w" to jn(s.numOr("w")),
            "r" to if (planReps) baseSides.sub(key)?.num("r")?.let(::jn) else jn(s.numOr("r")),
            "done" to JsonPrimitive(false),
        )
        return WorkoutModel.syncSideAggregate(
            Js.with(base, "sides", Js.obj(
                "L" to carry(prevSides.sub("L"), "L"),
                "R" to carry(prevSides.sub("R"), "R"),
            ))
        )
    }

    // ── history.js:981 rungDown（数组步长 = 哑铃库存 ownedFloor；标量 = 步长网格）──
    private fun rungDownEl(x: Double, step: JsonElement?): Double = when (val el = step) {
        null -> floor(x / 2.5) * 2.5
        is JsonArray -> Dumbbells.ownedFloor(el.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }, x)
        is JsonPrimitive -> {
            val s = el.doubleOrNull ?: 2.5
            if (s > 0) floor(x / s) * s else floor(x / 2.5) * 2.5
        }
        else -> floor(x / 2.5) * 2.5
    }

    // ── history.js:1005-1051 insertWarmupRow（热身块末插入，朝工作重量对半 ramp）──
    fun insertWarmupRow(
        rows: List<JsonObject>, mode: String, target: JsonObject?, step: JsonElement? = null, floor: Double = 0.0,
    ): List<JsonObject> {
        val firstWork = rows.indexOfFirst { !WorkoutModel.isWarmupRow(it) }
        val at = if (firstWork == -1) rows.size else firstWork
        val prev = if (at > 0) rows.getOrNull(at - 1) else null
        val work = if (firstWork == -1) null else rows.getOrNull(firstWork)
        fun rampTo(to: Double): Double {
            val from = prev?.numOr("w") ?: 0.0
            if (!(to > 0)) return 0.0
            if (to <= from) return to
            return minOf(to, maxOf(0.0, floor, rungDownEl(from + (to - from) / 2, step)))
        }
        val warm: JsonObject = when (mode) {
            "cardio" -> {
                val mn: Double? = when {
                    prev != null -> prev.num("min")
                    work != null -> work.num("min")
                    else -> (target?.num("min")?.takeIf { it != 0.0 } ?: 20.0)
                }
                val sp: Double? = when {
                    prev != null -> prev.num("speed")
                    work != null -> work.num("speed")
                    else -> (target?.num("speed")?.takeIf { it != 0.0 } ?: 8.0)
                }
                Js.obj(
                    "min" to mn?.let(::jn), "speed" to sp?.let(::jn),
                    "incline" to inclineFrom(prev ?: work),
                    "done" to JsonPrimitive(false), "phase" to JsonPrimitive("warmup"), "warmup" to JsonPrimitive(true),
                )
            }
            "time" -> {
                val sec: Double? = when {
                    prev != null -> prev.num("sec")
                    work != null -> work.num("sec")
                    else -> (target?.num("sec")?.takeIf { it != 0.0 } ?: 45.0)
                }
                Js.obj(
                    "sec" to sec?.let(::jn),
                    "w" to jn(rampTo(if (work != null) work.numOr("w") else target?.numOr("weight") ?: 0.0)),
                    "done" to JsonPrimitive(false), "phase" to JsonPrimitive("warmup"), "warmup" to JsonPrimitive(true),
                )
            }
            else -> {
                val rEl: JsonElement? = when {
                    work != null -> work.get("r")?.takeIf { it !is JsonNull }
                    prev != null -> prev.get("r")?.takeIf { it !is JsonNull }
                    else -> target?.get("reps")?.takeIf { it !is JsonNull }
                }
                Js.obj(
                    "w" to jn(rampTo(if (work != null) work.numOr("w") else target?.numOr("weight") ?: 0.0)),
                    "r" to rEl,
                    "done" to JsonPrimitive(false), "phase" to JsonPrimitive("warmup"), "warmup" to JsonPrimitive(true),
                )
            }
        }
        val warmRow = if (mode == "reps" && isPerSide(target)) WorkoutModel.makeSideSet(warm) else warm
        val next = rows.toMutableList()
        next.add(at, warmRow)
        return next
    }

    // ── history.js:1061 canBeWarmup（仅直排工作行可转热身）──
    fun canBeWarmup(row: JsonObject?): Boolean =
        row != null && !WorkoutModel.isWarmupRow(row) && !WorkoutModel.isSideSet(row) &&
            WorkoutModel.setType(row) != "dropset" && WorkoutModel.setType(row) != "restpause"

    // ── history.js:1078-1083 placeRow（摘除 i 后把 row 放回首个工作行位）──
    private fun placeRow(rows: List<JsonObject>, i: Int, row: JsonObject): List<JsonObject> {
        val next = rows.filterIndexed { j, _ -> j != i }.toMutableList()
        val firstWork = next.indexOfFirst { !WorkoutModel.isWarmupRow(it) }
        next.add(if (firstWork == -1) next.size else firstWork, row)
        return next
    }

    // ── history.js:1063-1069 makeWarmupAt（行转热身：留 w/r/at/done，弃 drops/sides/effort）──
    fun makeWarmupAt(rows: List<JsonObject>, i: Int): List<JsonObject> {
        val src = rows.getOrNull(i)
        if (!canBeWarmup(src)) return rows
        var warm = Js.obj(
            "w" to jn(src.numOr("w")),
            "r" to src.num("r")?.let(::jn),
            "done" to JsonPrimitive(src.bool("done") == true),
            "phase" to JsonPrimitive("warmup"),
            "warmup" to JsonPrimitive(true),
        )
        if (src.hasNotNull("at")) warm = Js.with(warm, "at", src?.get("at"))
        return placeRow(rows, i, warm)
    }

    // ── history.js:1088-1093 setSpanAt（该组占的行区间 [start, count]；计时单侧成对）──
    fun setSpanAt(rows: List<JsonObject>, i: Int): Pair<Int, Int> {
        val row = rows.getOrNull(i)
        if (row?.str("side") == "L" && rows.getOrNull(i + 1)?.str("side") == "R") return i to 2
        if (row?.str("side") == "R" && rows.getOrNull(i - 1)?.str("side") == "L") return (i - 1) to 2
        return i to 1
    }

    // ── history.js:1101-1113 NOT_COPIED / bare / copyOfRow（复制行剥去完成态与子行）──
    private val NOT_COPIED = listOf("done", "at", "planSec", "weightOrigin", "type", "drops", "clusters")

    private fun bare(src: JsonObject): JsonObject {
        var out = src
        for (k in NOT_COPIED) out = Js.without(out, k)
        return out
    }

    private fun copyOfRow(src: JsonObject): JsonObject {
        if (WorkoutModel.isSideSet(src)) {
            val sides = src.sub("sides")!!
            fun side(sd: JsonObject?): JsonObject = Js.with(bare(sd ?: Js.emptyObj()), "done", JsonPrimitive(false))
            return WorkoutModel.syncSideAggregate(
                Js.with(
                    Js.with(bare(src), "sides", Js.obj("L" to side(sides.sub("L")), "R" to side(sides.sub("R")))),
                    "done", JsonPrimitive(false),
                )
            )
        }
        return Js.with(bare(src), "done", JsonPrimitive(false))
    }

    // ── history.js:1118-1130 copySpanAt / copyRowAt（成对复制，插在该组之后）──
    private fun copySpanAt(rows: List<JsonObject>, i: Int): Pair<Int, Int> {
        fun work(r: JsonObject?): String? = if (r != null && !WorkoutModel.isWarmupRow(r)) r.str("side") else null
        val start = if (work(rows.getOrNull(i)) == "R" && work(rows.getOrNull(i - 1)) == "L") i - 1 else i
        val end = if (work(rows.getOrNull(start)) == "L" && work(rows.getOrNull(start + 1)) == "R") start + 1 else start
        return start to end
    }

    fun copyRowAt(rows: List<JsonObject>, i: Int): List<JsonObject> {
        if (rows.getOrNull(i) == null) return rows.toList()
        val (start, end) = copySpanAt(rows, i)
        val copies = rows.subList(start, end + 1).map(::copyOfRow)
        val next = rows.toMutableList()
        next.addAll(end + 1, copies)
        return next
    }

    // ── history.js:1142-1148 removeRowAt（摘除该组；不清空条目）──
    fun removeRowAt(rows: List<JsonObject>, i: Int): List<JsonObject> {
        val (start, count) = setSpanAt(rows, i)
        if (rows.size <= count) return rows.toList()
        val next = rows.toMutableList()
        next.subList(start, start + count).clear()
        return next
    }

    // ── speed.js:12 / 22 / 31-34 / 41 KMH_PER_MPH / speedLabel / toSpeed / fmtSpeed ──
    private const val KMH_PER_MPH = 1.609344

    private fun fmtSpeed(kmh: Double?, unit: String?): String {
        val v = kmh ?: 0.0
        val shown = if (unit == "mph") jsRound(v / KMH_PER_MPH * 100) / 100 else v
        return "${fmtNum(shown)} ${if (unit == "mph") "mph" else "km/h"}"
    }

    // ── history.js:141-206 setLabel（单组一行摘要；t() 以英文包字面量承载，勘记①）──
    private val EXPLICIT_MODES = setOf("reps", "time", "cardio")

    fun setLabel(id: String?, s: JsonObject, cfg: JsonObject? = null, speedUnit: String? = null): String {
        val idEl: JsonElement? = id?.let { JsonPrimitive(it) }
        val c = if (cfg != null) {
            Js.with(cfg, "id", cfg.get("id")?.takeIf { it !is JsonNull } ?: idEl)
        } else {
            Js.obj("id" to idEl)
        }
        var mode = modeOf(c)
        val rGt0 = (s.num("r") ?: 0.0) > 0
        if (cfg == null && !rGt0) {
            if ((s.num("min") ?: 0.0) > 0 || (s.num("speed") ?: 0.0) > 0) mode = "cardio"
            else if ((s.num("sec") ?: 0.0) > 0) mode = "time"
        }
        val cfgMode = cfg?.str("mode")
        if ((cfgMode == null || !EXPLICIT_MODES.contains(cfgMode)) && mode != "reps" && rGt0 &&
            !((s.num("min") ?: 0.0) > 0 || (s.num("speed") ?: 0.0) > 0 || (s.num("sec") ?: 0.0) > 0)
        ) mode = "reps"
        if (mode == "cardio") {
            val tail = if (hasIncline(s)) " · ${fmtNum(s.num("incline") ?: Double.NaN)}% incline" else ""
            return "${numStr(s.numOr("min"))} min @ ${fmtSpeed(s.numOr("speed"), speedUnit)}$tail"
        }
        val fail = if (isFailureSet(s)) " F" else ""
        if (mode == "time") {
            val wt = if ((s.numOr("w")) > 0) " · ${fmtNum(s.numOr("w"))}" else ""
            return fmtSec(s.num("sec")) + wt + fail
        }
        val bw = isBw(c)
        val meaning = if (!bw && bellsIn(c) == 2) dbLoadOf(c.get("dbLoad")) else null
        val planned = c.sub("intensifier")?.str("type") == "restpause"
        fun load(w: Double?, reps: String): String {
            val ww = w ?: 0.0
            return if (bw) {
                (if (ww > 0) "+${fmtNum(ww)} × " else "") + reps
            } else when (meaning) {
                "each" -> "${fmtNum(ww)} each × $reps"
                "total" -> "${fmtNum(ww)} total × $reps"
                else -> "${fmtNum(ww)}×$reps"
            }
        }
        fun repsOf(side: JsonObject): String {
            val reps = side.numOr("r")
            val bursts = clustersOf(side).map { it.numOr("r") }.filter { it > 0 }
            val sum = bursts.sum()
            if (bursts.isEmpty() || sum > reps || (planned && sum != reps)) return numStr(reps)
            return (if (reps > sum) listOf(reps - sum) + bursts else bursts).joinToString("+") { numStr(it) }
        }
        fun oneSide(side: JsonObject): String {
            val drops = dropsOf(side).filter { it.numOr("r") > 0 }
            return load(side.num("w"), repsOf(side)) +
                drops.joinToString("") { " ↘ " + load(it.numOr("w"), numStr(it.num("r") ?: 0.0)) }
        }
        if (WorkoutModel.isSideSet(s)) {
            val sides = s.sub("sides")!!
            val partial = sides.sub("L").bool("done") != sides.sub("R").bool("done")
            return listOf("L", "R").joinToString(" · ") { key ->
                val sd = sides.sub(key) ?: Js.emptyObj()
                "$key " + if (partial && !jsTruthy(sd.get("done"))) "–" else oneSide(sd) + effortTail(sd)
            } + fail
        }
        return oneSide(s) + fail + effortTail(s)
    }

}
