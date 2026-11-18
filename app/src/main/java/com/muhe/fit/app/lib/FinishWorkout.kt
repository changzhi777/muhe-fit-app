package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * finish-workout.js 全量移植（完成会话的持久化边界——保持纯函数，兼容性测试无需挂 UI）。
 *
 * 移植自 ck/frontend/src/lib/finish-workout.js（golden 对齐见 FinishWorkoutGoldenTest）。
 * 依赖闭包：history.js 的 bestWeightForEntry / cleanupSg（History）、workout-model.js 的
 * hasCompletedWork / isSideSet（WorkoutModel）。
 * 未移植：buildCompletedWorkout 的 `snapshotFor` 回调参数（muscleSnapshot 快照）——回调无法
 * 经 JSON fixture 表达，由 JS 侧原测试覆盖。
 */
object FinishWorkout {

    // finish-workout.js:38 FORGOTTEN_GAP_MS（20 分钟以上的 tick 间隔 = 会话早已结束）
    const val FORGOTTEN_GAP_MS = 20.0 * 60 * 1000

    /** finish-workout.js:19-29 finishedRow——落盘前剥掉 live bookkeeping（planSec/weightOrigin）。 */
    private fun finishedRow(set: JsonObject?): JsonElement? {
        if (set == null) return null
        val sides = set.sub("sides")
        val sideMarked = WorkoutModel.isSideSet(set) &&
            (sides.sub("L").hasNotNull("weightOrigin") || sides.sub("R").hasNotNull("weightOrigin"))
        if (!set.hasNotNull("planSec") && !set.hasNotNull("weightOrigin") && !sideMarked) return set
        var rest = Js.without(Js.without(set, "planSec"), "weightOrigin")
        if (sideMarked) {
            val s2 = rest.sub("sides")!!
            rest = Js.with(rest, "sides", Js.obj(
                "L" to Js.without(s2.sub("L") ?: Js.emptyObj(), "weightOrigin"),
                "R" to Js.without(s2.sub("R") ?: Js.emptyObj(), "weightOrigin"),
            ))
        }
        return if (!set.hasNotNull("planSec") || jsTruthy(rest.get("done"))) rest
        else Js.with(rest, "sec", set.get("planSec"))
    }

    /** finish-workout.js:43-47 rowLengthMs——一行的时长：有氧按分钟、按秒（per-side 两侧相加）。 */
    private fun rowLengthMs(set: JsonObject): Double {
        fun n(v: JsonElement?): Double = v.numValue()?.takeIf { it > 0 } ?: 0.0
        if (WorkoutModel.isSideSet(set)) {
            val sides = set.sub("sides")!!
            return (n(sides.sub("L")?.get("sec")) + n(sides.sub("R")?.get("sec"))) * 1000
        }
        return n(set.get("min")) * 60000 + n(set.get("sec")) * 1000
    }

    /**
     * finish-workout.js:49-66 sessionEnd——会话结束时刻：最后一次不间断工作的 reach
     * （tick + 该行自身时长）；超过 gap 的补记 tick 不拉长会话；落在 gap 内的 Finish 保留。
     */
    fun sessionEnd(active: JsonObject?, now: Double, gap: Double = FORGOTTEN_GAP_MS): Double {
        val ticks = (active?.arr("entries") ?: emptyList())
            .flatMap { e -> (e as? JsonObject)?.arr("sets") ?: emptyList() }
            .mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                val at = so.get("at").numValue() ?: return@mapNotNull null
                if (WorkoutModel.hasCompletedWork(so)) at to rowLengthMs(so) else null
            }
            .sortedBy { it.first }
        if (ticks.isEmpty()) return now
        var reach = ticks[0].first + ticks[0].second
        for ((at, len) in ticks.drop(1)) {
            if (at - len - reach > gap) break
            reach = maxOf(reach, at + len)
        }
        return if (now - reach > gap) reach else now
    }

    /**
     * finish-workout.js:68-129 buildCompletedWorkout——完成会话的落盘形态。
     * 白名单语义逐字段对齐：没标记的行按引用透传；rid/noProg/planned/sg 只在置位时写；
     * target 是显式 null（absent → JS `|| null` → JSON null 落盘），topW 为真值才写。
     */
    fun buildCompletedWorkout(active: JsonObject, end: Double, prs: JsonElement = JsonArray(emptyList())): JsonObject {
        val entries = mutableListOf<JsonObject>()
        for (e0 in active.arr("entries") ?: emptyList()) {
            val entry = e0 as? JsonObject ?: continue
            val completed = LinkedHashMap<String, JsonElement>()
            entry.str("id")?.let { completed["id"] = JsonPrimitive(it) }
            completed["sets"] = JsonArray((entry.arr("sets") ?: emptyList()).map { s -> finishedRow(s as? JsonObject) ?: s })
            completed["topW"] = run {
                val best = History.bestWeightForEntry(entry)
                if (best.isFinite() && best > 0) jn(best) else JsonNull   // JS `|| null`
            }
            completed["target"] = entry.get("target")?.takeIf { it !is JsonNull } ?: JsonNull
            entry.str("rid")?.takeIf { it.isNotEmpty() }?.let { completed["rid"] = JsonPrimitive(it) }
            if (entry.bool("noProg") == true) completed["noProg"] = JsonPrimitive(true)
            entry.get("planned")?.takeIf { it !is JsonNull }?.let { completed["planned"] = it }
            entry.str("sg")?.takeIf { it.isNotEmpty() }?.let { completed["sg"] = JsonPrimitive(it) }
            // muscleSnapshot：snapshotFor 回调不入 fixture（JS 侧覆盖）
            val note = (entry.str("note") ?: "").trim()
            if (note.isNotEmpty()) {
                completed["note"] = JsonPrimitive(note)
                if (entry.bool("notePin") == true) completed["notePin"] = JsonPrimitive(true)
            }
            entries.add(JsonObject(completed))
        }
        // 一组都没完成的条目落掉，落单的超集伙伴清除组标（落的是本函数的副本，进行中的会话不受影响）
        val kept = entries.filter { e ->
            (e.arr("sets") ?: emptyList()).any { s -> (s as? JsonObject)?.let(WorkoutModel::hasCompletedWork) == true }
        }
        val cleaned = History.cleanupSg(kept)

        val sessionNote = (active.str("note") ?: "").trim()
        val routineIds: List<JsonElement> = active.get("routineIds")?.takeIf { it !is JsonNull }?.let { el ->
            if (el is JsonArray) el.toList() else listOf(el)   // JS [].concat(routineIds ?? …)
        } ?: run {
            val rid = active.str("routineId")?.takeIf { it.isNotEmpty() }
            if (rid != null) listOf<JsonElement>(JsonPrimitive(rid)) else emptyList()
        }
        val allNoProg = cleaned.isNotEmpty() && cleaned.all { it.bool("noProg") == true }

        val out = LinkedHashMap<String, JsonElement>()
        active.get("id")?.let { out["id"] = it }          // JS echo：undefined → 键不落，显式 null 保留
        active.get("d")?.let { out["d"] = it }
        active.get("start")?.let { out["start"] = it }
        out["end"] = jn(end)
        out["routineIds"] = JsonArray(routineIds)
        out["routineId"] = routineIds.firstOrNull() ?: JsonNull   // JS routineIds[0] ?? null（显式 null 落盘）
        active.get("name")?.let { out["name"] = it }
        active.get("bw")?.let { out["bw"] = it }
        out["entries"] = JsonArray(cleaned)
        out["prs"] = prs
        if (allNoProg) out["excludeFromProgression"] = JsonPrimitive(true)
        if (sessionNote.isNotEmpty()) out["note"] = JsonPrimitive(sessionNote)
        return JsonObject(out)
    }
}
