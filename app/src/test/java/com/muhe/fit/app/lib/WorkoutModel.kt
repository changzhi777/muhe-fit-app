package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * workout-model.js 的移植子集：progression / backoff / finish-workout 实际调用的部分。
 * 来源函数逐一注明 JS 行号（workout-model.js，2026-11-17 版）。
 */
object WorkoutModel {

    const val WEIGHT_ORIGIN_MANUAL = "manual"   // workout-model.js:196

    // workout-model.js:4 行形态集合
    private val MODES = listOf("reps", "time", "cardio")

    // workout-model.js:7-12 normalizedPhase
    fun normalizedPhase(value: String?, fallback: String = "work"): String {
        val token = value?.trim()?.lowercase()
        if (token == "warmup" || token == "warm-up" || token == "warm_up") return "warmup"
        if (token == "work") return "work"
        return if (fallback == "warmup") "warmup" else "work"
    }

    // workout-model.js:15-19 phaseForSet：显式 phase 胜过 legacy warmup 布尔
    fun phaseForSet(set: JsonObject?, fallback: String = "work"): String {
        val phase = set?.get("phase")
        if (phase != null && phase !is JsonNull) {
            val token = (phase as? JsonPrimitive)?.takeIf { it.isString }?.content
            return normalizedPhase(token, fallback)
        }
        return if (set?.bool("warmup") == true) "warmup" else normalizedPhase(null, fallback)
    }

    // workout-model.js:21-23
    fun isWarmupRow(set: JsonObject?): Boolean = phaseForSet(set) == "warmup"

    // workout-model.js:30-33 setType
    fun setType(set: JsonObject?): String {
        val t = set.str("type")
        return if (t == "dropset" || t == "restpause") t else "straight"
    }

    // workout-model.js:188-191 isSideSet
    fun isSideSet(set: JsonObject?): Boolean {
        val sides = set?.sub("sides") ?: return false
        return sides.sub("L") != null && sides.sub("R") != null
    }

    // workout-model.js:199-201 hasCompletedWork（单侧完成即算）
    fun hasCompletedWork(set: JsonObject?): Boolean =
        if (isSideSet(set)) set.sub("sides")?.sub("L")?.bool("done") == true || set.sub("sides")?.sub("R")?.bool("done") == true
        else set?.bool("done") == true

    // workout-model.js:210-223 sideOf：侧对象的规范形
    fun sideOf(value: JsonObject?): JsonObject {
        val v = value ?: Js.emptyObj()
        val out = LinkedHashMap<String, JsonElement>()
        out["w"] = jn(v.numOr("w"))
        out["r"] = jn(v.numOr("r"))
        out["done"] = JsonPrimitive(v.bool("done") == true)
        v.get("rir")?.takeIf { it !is JsonNull }?.let { out["rir"] = it }
        v.get("rpe")?.takeIf { it !is JsonNull }?.let { out["rpe"] = it }
        if (v.str("weightOrigin") == WEIGHT_ORIGIN_MANUAL) out["weightOrigin"] = JsonPrimitive(WEIGHT_ORIGIN_MANUAL)
        val t = v.str("type")
        if (t == "dropset" || t == "restpause") out["type"] = JsonPrimitive(t)
        (v.get("drops") as? JsonArray)?.let { out["drops"] = it }
        (v.get("clusters") as? JsonArray)?.let { out["clusters"] = it }
        return JsonObject(out)
    }

    // workout-model.js:227-237 makeSideSet：一行拆 L/R，各半次数
    fun makeSideSet(row: JsonObject? = null): JsonObject {
        val base = row ?: Js.emptyObj()
        val half = jsRound(base.numOr("r") / 2)
        val w = base.numOr("w")
        val next = LinkedHashMap(base)
        next["sides"] = Js.obj("L" to Js.obj("w" to jn(w), "r" to jn(half), "done" to JsonPrimitive(false)),
            "R" to Js.obj("w" to jn(w), "r" to jn(half), "done" to JsonPrimitive(false)))
        next.remove("rir")
        next.remove("rpe")
        return syncSideAggregate(JsonObject(next))
    }

    // workout-model.js:241-260 syncSideAggregate：从两侧重算行级镜像
    fun syncSideAggregate(row: JsonObject): JsonObject {
        if (!isSideSet(row)) return JsonObject(LinkedHashMap(row))
        val sides = row.sub("sides")!!
        val l = sideOf(sides.sub("L"))
        val r = sideOf(sides.sub("R"))
        val out = LinkedHashMap(row)
        out["sides"] = Js.obj("L" to l, "R" to r)
        out["r"] = jn(l.numOr("r") + r.numOr("r"))
        out["w"] = jn(maxOf(l.numOr("w"), r.numOr("w")))
        out["done"] = JsonPrimitive(l.bool("done") == true && r.bool("done") == true)
        out.remove("rir")
        out.remove("rpe")
        val rirs = listOfNotNull(l.get("rir")?.takeIf { it !is JsonNull }, r.get("rir")?.takeIf { it !is JsonNull })
        val rpes = listOfNotNull(l.get("rpe")?.takeIf { it !is JsonNull }, r.get("rpe")?.takeIf { it !is JsonNull })
        if (rirs.isNotEmpty()) out["rir"] = jn(rirs.minOf { it.numValue() ?: 0.0 })
        else if (rpes.isNotEmpty()) out["rpe"] = jn(rpes.maxOf { it.numValue() ?: 0.0 })
        out.remove("drops")
        out.remove("clusters")
        out.remove("type")
        val sideType = if (setType(l) != "straight") setType(l) else setType(r)
        if (sideType != "straight") out["type"] = JsonPrimitive(sideType)
        return JsonObject(out)
    }

    // workout-model.js:341-345 normalizeMode
    fun normalizeMode(value: String?, fallback: String = "reps"): String {
        val token = value?.trim()?.lowercase()
        if (token != null && MODES.contains(token)) return token
        return if (MODES.contains(fallback)) fallback else "reps"
    }

    // workout-model.js:347-353 modeFromUnit
    private fun modeFromUnit(value: JsonObject?): String? {
        val token = value.str("unit")?.trim()?.lowercase()
        return when (token) {
            "rep", "reps", "repetition", "repetitions" -> "reps"
            "sec", "secs", "second", "seconds" -> "time"
            "min", "mins", "minute", "minutes" -> "cardio"
            else -> null
        }
    }

    // workout-model.js:355-359 explicitMode
    private fun explicitMode(source: JsonObject?): String? {
        val token = source.str("mode")?.trim()?.lowercase()
        if (token != null && MODES.contains(token)) return token
        return modeFromUnit(source)
    }

    // workout-model.js:361-370 inferredMode
    private fun inferredMode(source: JsonObject?): String? {
        explicitMode(source)?.let { return it }
        if (source.str("mode")?.trim()?.lowercase() == "amrap") return "reps"
        if (source.hasNotNull("min") || source.hasNotNull("speed")) return "cardio"
        if (source.hasNotNull("sec") || source.hasNotNull("seconds") || source.hasNotNull("durationSec")) return "time"
        if (source.hasNotNull("r") || source.hasNotNull("reps") || source.hasNotNull("actualReps")) return "reps"
        return null
    }

    // workout-model.js:373-375 modeForSet
    fun modeForSet(set: JsonObject?, target: JsonObject? = null): String =
        explicitMode(set) ?: inferredMode(target ?: Js.emptyObj()) ?: inferredMode(set ?: Js.emptyObj()) ?: "reps"

    // workout-model.js:378-390 modeForEntry（混合行模式故意返回 null）
    fun modeForEntry(entry: JsonObject?, fallback: String? = null): String? {
        val source = entry ?: Js.emptyObj()
        val target = source.sub("target") ?: source
        val sets = source.arr("sets") ?: emptyList()
        val work = sets.mapNotNull { it as? JsonObject }.filter { !isWarmupRow(it) }
        val observed = work.ifEmpty { sets.mapNotNull { it as? JsonObject } }
        val modes = observed.map { modeForSet(it, target) }.distinct()
        if (modes.size > 1) return null
        if (modes.size == 1) return modes[0]
        inferredMode(target)?.let { return it }
        return if (fallback == null) modeForSet(source, target) else normalizeMode(fallback)
    }
}
