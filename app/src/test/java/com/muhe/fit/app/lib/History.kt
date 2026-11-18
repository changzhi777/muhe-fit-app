package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

/**
 * history.js 的移植子集：progression / finish-workout 实际调用的部分（纯函数，零状态写入）。
 * 另含 superset-meta.js 的 syncSupersetMeta（cleanupSg 的依赖）。来源函数逐一注明 JS 行号。
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
}
