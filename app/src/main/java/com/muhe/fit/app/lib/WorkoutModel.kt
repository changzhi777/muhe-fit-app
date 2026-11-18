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

    // ── 失败组 / 加重器：行级 type/drops/clusters/failure 面 ──

    // workout-model.js:34 isDropSet
    fun isDropSet(set: JsonObject?): Boolean = setType(set) == "dropset"

    // workout-model.js:35 isRestPauseSet
    fun isRestPauseSet(set: JsonObject?): Boolean = setType(set) == "restpause"

    // workout-model.js:43-46 isFailureSet（warm-up 恒不是——phase 先裁）
    fun isFailureSet(set: JsonObject?): Boolean = set?.bool("failure") == true && !isWarmupRow(set)

    // workout-model.js:49-54 toggleFailure（关时删键，行回到原样）
    fun toggleFailure(set: JsonObject?): JsonObject {
        val out = LinkedHashMap(set ?: Js.emptyObj())
        if (set?.bool("failure") == true) out.remove("failure")
        else out["failure"] = JsonPrimitive(true)
        return JsonObject(out)
    }

    // workout-model.js:57-60 dropsOf（只认 dropset 行，防御畸形数组）
    fun dropsOf(set: JsonObject?): List<JsonElement> {
        val src = set ?: Js.emptyObj()
        return if (isDropSet(src)) src.arr("drops") ?: emptyList() else emptyList()
    }

    // workout-model.js:63-66 clustersOf（只认 restpause 行）
    fun clustersOf(set: JsonObject?): List<JsonElement> {
        val src = set ?: Js.emptyObj()
        return if (isRestPauseSet(src)) src.arr("clusters") ?: emptyList() else emptyList()
    }

    // workout-model.js:77-87 extraVolumeOf（dropset 的 drops 加回总量；restpause 的 r 已是全部
    // burst 之和，clusters 只是拆解——零加回；per-side 行两侧 drops 各自求和后相加，issue #60）
    fun extraVolumeOf(set: JsonObject?): Double {
        val src = set ?: Js.emptyObj()
        fun dropsVol(drops: List<JsonElement>): Double = drops.sumOf { d ->
            val o = d as? JsonObject
            o.numOr("w") * o.numOr("r")
        }
        if (isSideSet(src)) {
            val sides = src.sub("sides")!!
            return dropsVol(dropsOf(sides.sub("L"))) + dropsVol(dropsOf(sides.sub("R")))
        }
        return dropsVol(dropsOf(src))
    }

    // workout-model.js:90-93 addDrop（追加 drop 并盖 dropset 章；键序 w→r 同 JS）
    fun addDrop(set: JsonObject?, drop: JsonObject?): JsonObject {
        val src = set ?: Js.emptyObj()
        val prev = src.arr("drops") ?: emptyList()
        val nd = Js.obj("w" to jn(drop.numOr("w")), "r" to jn(drop.numOr("r")))
        return Js.spread(src, Js.obj("type" to JsonPrimitive("dropset"), "drops" to JsonArray(prev + nd)))
    }

    // workout-model.js:96-99 addCluster（追加 burst 并盖 restpause 章；键序 r→restSec 同 JS）
    fun addCluster(set: JsonObject?, cluster: JsonObject?): JsonObject {
        val src = set ?: Js.emptyObj()
        val prev = src.arr("clusters") ?: emptyList()
        val nc = Js.obj("r" to jn(cluster.numOr("r")), "restSec" to jn(cluster.numOr("restSec")))
        return Js.spread(src, Js.obj("type" to JsonPrimitive("restpause"), "clusters" to JsonArray(prev + nc)))
    }

    // workout-model.js:102-105 removeDropAt（删一个；清空最后一个回 straight，drops 留空数组）
    fun removeDropAt(set: JsonObject?, i: Int): JsonObject {
        val src = set ?: Js.emptyObj()
        val drops = JsonArray((src.arr("drops") ?: emptyList()).filterIndexed { idx, _ -> idx != i })
        return if (drops.isNotEmpty()) Js.with(src, "drops", drops)
        else Js.spread(src, Js.obj("type" to JsonPrimitive("straight"), "drops" to drops))
    }

    // workout-model.js:108-111 removeClusterAt
    fun removeClusterAt(set: JsonObject?, i: Int): JsonObject {
        val src = set ?: Js.emptyObj()
        val clusters = JsonArray((src.arr("clusters") ?: emptyList()).filterIndexed { idx, _ -> idx != i })
        return if (clusters.isNotEmpty()) Js.with(src, "clusters", clusters)
        else Js.spread(src, Js.obj("type" to JsonPrimitive("straight"), "clusters" to clusters))
    }

    // workout-model.js:114-119 setDropAt（索引不存在/元素非对象 → 原参数原样返回，JS `!drops[i]` 口径）
    fun setDropAt(set: JsonObject?, i: Int, patch: JsonObject?): JsonObject? {
        val src = set ?: Js.emptyObj()
        val drops = (src.arr("drops") ?: emptyList()).toMutableList()
        val cur = drops.getOrNull(i) as? JsonObject ?: return set
        drops[i] = Js.spread(cur, patch)
        return Js.with(src, "drops", JsonArray(drops))
    }

    // workout-model.js:122-127 setClusterAt
    fun setClusterAt(set: JsonObject?, i: Int, patch: JsonObject?): JsonObject? {
        val src = set ?: Js.emptyObj()
        val clusters = (src.arr("clusters") ?: emptyList()).toMutableList()
        val cur = clusters.getOrNull(i) as? JsonObject ?: return set
        clusters[i] = Js.spread(cur, patch)
        return Js.with(src, "clusters", JsonArray(clusters))
    }

    // ── 猜重 / burst 助手 ──

    // workout-model.js:135-150 nextDropWeight（grid 为数字步长版）
    fun nextDropWeight(prevWeight: Double?, pct: Double? = null, grid: Double? = null): Double =
        nextDropWeightCore(prevWeight, pct, grid, null)

    // workout-model.js:140-142（grid 为 snap 函数版——lib/plates.js dropGrid 返回 w => ownedFloor(...)）
    fun nextDropWeight(prevWeight: Double?, pct: Double? = null, snap: ((Double) -> Double)?): Double =
        nextDropWeightCore(prevWeight, pct, null, snap)

    // workout-model.js:135-150 主体（tidy = Math.round(w*1000)/1000；snap 有限非负才采纳，否则落 .5 兜底）
    private fun nextDropWeightCore(prevWeight: Double?, pct: Double?, grid: Double?, snap: ((Double) -> Double)?): Double {
        val p = minOf(90.0, maxOf(1.0, pct?.takeIf { it != 0.0 && it.isFinite() } ?: 20.0))
        val prev = maxOf(0.0, prevWeight?.takeIf { it.isFinite() } ?: 0.0)
        val raw = prev * (1 - p / 100)
        if (snap != null) {
            val w = snap(raw)
            if (w.isFinite() && w >= 0) return jsRound(w * 1000) / 1000
        } else if ((grid ?: 0.0) > 0) {
            val step = grid!!
            var w = jsRound(raw / step) * step
            if (prev > 0 && w >= prev) w = prev - step
            return jsRound(maxOf(0.0, w) * 1000) / 1000
        }
        return jsRound(raw * 2) / 2
    }

    // workout-model.js:153-155 nextBurstReps（约上次一半，下限 1；Math.round 半值向 +∞ 与 jsRound 同）
    fun nextBurstReps(prevReps: Double?): Double =
        maxOf(1.0, jsRound((prevReps?.takeIf { it.isFinite() && it != 0.0 } ?: 0.0) / 2))

    // workout-model.js:163-172 splitBurstReps（总量 → 约半递减序列，和恰为总量；0/非法 → 空数组）
    fun splitBurstReps(total: Double?): JsonArray {
        val bursts = ArrayList<JsonElement>()
        var remaining = maxOf(0.0, jsRound(total?.takeIf { it.isFinite() && it != 0.0 } ?: 0.0))
        while (remaining > 0) {
            val burst = minOf(remaining, nextBurstReps(remaining))
            bursts.add(jn(burst))
            remaining -= burst
        }
        return JsonArray(bursts)
    }

    // ── 完成量 ──

    // workout-model.js:204-208 completedVolumeOf（per-side 两侧递归相加；未 done → 0；
    // 行 own w×r + drops 加回——`!set?.done` 是 loose truthiness，用 jsTruthy 对位）
    fun completedVolumeOf(set: JsonObject?): Double {
        if (isSideSet(set)) {
            val sides = set.sub("sides")!!
            return completedVolumeOf(sides.sub("L")) + completedVolumeOf(sides.sub("R"))
        }
        if (!jsTruthy(set?.get("done"))) return 0.0
        val s = set!!
        return s.numOr("w") * s.numOr("r") + extraVolumeOf(s)
    }

    // ── per-side 编辑（单侧 patch 与双侧对称 patch；addDrop/dropsOf 等行级助手复用在侧对象上）──

    // workout-model.js:289-293 patchSide（对单侧施加编辑并重算聚合；非 per-side 或非法 side → 原样副本）
    private fun patchSide(row: JsonObject?, side: String?, fn: (JsonObject) -> JsonObject): JsonObject {
        val base = row ?: Js.emptyObj()
        if (!isSideSet(base) || (side != "L" && side != "R")) return JsonObject(LinkedHashMap(base))
        val sides = base.sub("sides")!!
        val newSides = LinkedHashMap(sides)
        newSides[side!!] = fn(sideOf(sides.sub(side)))
        return syncSideAggregate(Js.with(base, "sides", JsonObject(newSides)))
    }

    // workout-model.js:295-299 patchBothSides（对两侧施加编辑并重算聚合，fn 第二参为侧名 'L'|'R'）
    private fun patchBothSides(row: JsonObject?, fn: (JsonObject, String) -> JsonObject): JsonObject {
        val base = row ?: Js.emptyObj()
        if (!isSideSet(base)) return JsonObject(LinkedHashMap(base))
        val sides = base.sub("sides")!!
        val newSides = LinkedHashMap(sides)
        newSides["L"] = fn(sideOf(sides.sub("L")), "L")
        newSides["R"] = fn(sideOf(sides.sub("R")), "R")
        return syncSideAggregate(Js.with(base, "sides", JsonObject(newSides)))
    }

    // workout-model.js:264-271 setSideField（null 清 rir/rpe 对位 JS delete；其余字段 null 写
    // JsonNull 值键，对位 JS `cur[field] = null`；等价于 JS 内联展开——与 patchSide 同构）
    fun setSideField(row: JsonObject?, side: String?, field: String, value: JsonElement?): JsonObject =
        patchSide(row, side) { cur ->
            if (value == null && (field == "rir" || field == "rpe")) Js.without(cur, field)
            else Js.with(cur, field, value ?: JsonNull)
        }

    // workout-model.js:274-279 toggleSide（翻一侧 done；两侧齐 → 行 done）
    fun toggleSide(row: JsonObject?, side: String?): JsonObject =
        patchSide(row, side) { cur -> Js.with(cur, "done", JsonPrimitive(!(cur.bool("done") == true))) }

    // workout-model.js:303-309 addSideDrop（两侧各按自己末个 drop/侧重 seed pct drop；grid 数字版）
    fun addSideDrop(row: JsonObject?, pct: Double? = null, grid: Double? = null): JsonObject =
        patchBothSides(row) { sd, _ ->
            val drops = dropsOf(sd)
            val base = if (drops.isNotEmpty()) (drops.last() as? JsonObject).numOr("w") else sd.numOr("w")
            addDrop(sd, Js.obj("w" to jn(nextDropWeight(base, pct, grid)), "r" to jn(sd.numOr("r"))))
        }

    // workout-model.js:303-309（grid 为 snap 函数版）
    fun addSideDrop(row: JsonObject?, pct: Double? = null, snap: ((Double) -> Double)?): JsonObject =
        patchBothSides(row) { sd, _ ->
            val drops = dropsOf(sd)
            val base = if (drops.isNotEmpty()) (drops.last() as? JsonObject).numOr("w") else sd.numOr("w")
            addDrop(sd, Js.obj("w" to jn(nextDropWeight(base, pct, snap)), "r" to jn(sd.numOr("r"))))
        }

    // workout-model.js:310-312 removeSideDropAt（两侧对称删同位 drop）
    fun removeSideDropAt(row: JsonObject?, i: Int): JsonObject =
        patchBothSides(row) { sd, _ -> removeDropAt(sd, i) }

    // workout-model.js:313-315 setSideDropAt（单侧改 drop）
    fun setSideDropAt(row: JsonObject?, side: String?, i: Int, patch: JsonObject?): JsonObject =
        patchSide(row, side) { sd -> setDropAt(sd, i, patch) ?: sd }

    // workout-model.js:320-327 addSideCluster（两侧各按自己末个 burst/侧 reps seed，侧 r 自增 added）
    fun addSideCluster(row: JsonObject?, restSec: Double?): JsonObject =
        patchBothSides(row) { sd, _ ->
            val clusters = clustersOf(sd)
            val base = if (clusters.isNotEmpty()) (clusters.last() as? JsonObject).numOr("r") else sd.numOr("r")
            val added = nextBurstReps(base)
            Js.with(addCluster(sd, Js.obj("r" to jn(added), "restSec" to restSec?.let { jn(it) })),
                "r", jn(sd.numOr("r") + added))
        }

    // workout-model.js:328-333 removeSideClusterAt（两侧对称删 burst，侧 r 减被删 burst 的 reps）
    fun removeSideClusterAt(row: JsonObject?, i: Int): JsonObject =
        patchBothSides(row) { sd, _ ->
            val removed = (clustersOf(sd).getOrNull(i) as? JsonObject).numOr("r")
            Js.with(removeClusterAt(sd, i), "r", jn(maxOf(0.0, sd.numOr("r") - removed)))
        }

    // workout-model.js:334-339 setSideClusterAt（单侧改 burst reps，侧 r 随 delta 调整；r null → 0 对位 `Number(r) || 0`）
    fun setSideClusterAt(row: JsonObject?, side: String?, i: Int, r: Double?): JsonObject =
        patchSide(row, side) { sd ->
            val old = (clustersOf(sd).getOrNull(i) as? JsonObject).numOr("r")
            val delta = (r?.takeIf { it.isFinite() && it != 0.0 } ?: 0.0) - old
            Js.with(setClusterAt(sd, i, Js.obj("r" to r?.let { jn(it) })) ?: sd,
                "r", jn(maxOf(0.0, sd.numOr("r") + delta)))
        }

}
