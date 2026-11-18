package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * progression.js 全量移植（自动渐进引擎：6 策略 off/linear/greyskull/double/triple/time +
 * deload 0.9 因子 + 会话诚实读法 + Epley deload 选择器 + triple 循环）。
 *
 * 移植自 ck/frontend/src/lib/progression.js（golden 对齐见 ProgressionGoldenTest，机制见
 * android-app/docs/golden-testing.md）。依赖闭包一并移植并注明来源：
 *   rep-range.js  normalizeRepRange（本文件下方）
 *   pyramid.js    isPyramid（本文件下方）
 *   backoff.js    backoffAt（Backoff）
 *   history.js    modeOf / isBw / isPerSide / repStep / entryExcluded / entryRoutineId / rerampWarmups（History）
 *   exercises.js  isAssisted / isLoadedEq / defaultIncrement（Ex；EXIDX 数据经 fixture catalog 注入）
 *   dumbbells.js  ownedWeightsFor / ownedUp / ownedDeload / ownedAround / ownedFloor（Dumbbells）
 *   workout-model.js  isWarmupRow / isSideSet / syncSideAggregate / makeSideSet（WorkoutModel）
 * 未移植（非本批 golden 面）：POLICY_NAME / POLICY_DESC（展示文案）、rerampWarmups 的
 * owned-bells 数组步长分支（issue #376，随 dumbbell 批次）。
 *
 * ⚠️ 移植纪律：JS 侧语义改动 → 先改 ck/frontend/src/lib/progression.test.js → 再生 fixture → 再动本文件。
 */
object Progression {

    // progression.js:27-34 策略集合与「哪个策略驱动哪种记录模式」
    val POLICIES = listOf("off", "linear", "greyskull", "double", "triple", "time")
    val POLICIES_FOR: Map<String, List<String>> = linkedMapOf(
        "reps" to listOf("off", "linear", "greyskull", "double", "triple"),
        "time" to listOf("off", "time"),
        "cardio" to listOf("off"),
    )

    // progression.js:55-58 deload 常量（Epley 目标是软目标，落在真实载重网格上）
    const val DELOAD_FACTOR = 0.9
    val DELOAD_AFTER: Map<String, Int> = linkedMapOf(
        "linear" to 3, "greyskull" to 1, "double" to 3, "triple" to 3, "time" to 3,
    )
    const val DELOAD_FACTOR_MIN = 0.5
    const val DELOAD_FACTOR_MAX = 0.95

    // progression.js:91 / 94 / 369
    const val DEFAULT_SEC_INCREMENT = 5.0
    const val MAX_BW_SETS = 6
    const val MAX_TRIPLE_SETS = 10

    // ── rep-range.js:3-19 normalizeRepRange（double progression 的区间归一；来源 rep-range.js）──
    data class RepRange(val reps: Double, val repsMin: Double)

    fun normalizeRepRange(reps: Double, repsMin: Double?, stride: Double = 1.0): RepRange {
        val step = if (stride > 0 && stride == floor(stride)) stride else 1.0
        val upper = repRangeAlign(repRangePositiveInt(reps, 10.0), step)
        val lower = repRangeAlign(repRangePositiveInt(repsMin, max(1.0, upper - 2)), step)
        return if (lower >= upper) RepRange(lower + step, lower) else RepRange(upper, lower)
    }

    private fun repRangeAlign(value: Double, stride: Double): Double = max(stride, ceil(value / stride) * stride)

    private fun repRangePositiveInt(value: Double?, fallback: Double): Double =
        if (value != null && value.isFinite() && value > 0) max(1.0, jsRound(value)) else fallback

    // ── pyramid.js:19-21 isPyramid（金字塔组从不渐进；来源 pyramid.js）──
    fun isPyramid(cfg: JsonObject?): Boolean =
        cfg != null && cfg.arr("pyramid")?.isNotEmpty() == true && (cfg.str("mode") ?: "reps") == "reps"

    // progression.js:60-66 isValidDeloadFactor / deloadFactorOf
    fun isValidDeloadFactor(value: Double?): Boolean =
        value != null && value.isFinite() && value >= DELOAD_FACTOR_MIN && value <= DELOAD_FACTOR_MAX

    fun deloadFactorOf(cfg: JsonObject?): Double {
        val f = cfg.num("deloadFactor")
        return if (isValidDeloadFactor(f)) f!! else DELOAD_FACTOR
    }

    // progression.js:70-76 epley1RM（单侧动作按单侧次数读；无法诚实回答 → null）
    fun epley1RM(weight: Double, reps: Double): Double? {
        val w = weight
        val r = reps
        if (!w.isFinite() || !r.isFinite() || w <= 0 || r < 1) return null
        val result = w * (1 + r / 30)
        return if (result.isFinite() && result > 0) round1(result) else null
    }

    // progression.js:77-81 deloadTarget1RM
    fun deloadTarget1RM(weight: Double, reps: Double, factor: Double = DELOAD_FACTOR, perSide: Boolean = false): Double? {
        val base = epley1RM(weight, if (perSide) reps / 2 else reps)
        val f = if (isValidDeloadFactor(factor)) factor else DELOAD_FACTOR
        return base?.let { round1(it * f) }
    }

    // progression.js:88-90 weightIncrement（reps 模式载重步长；计时动作用秒，不调用本函数）
    fun weightIncrement(cfg: JsonObject?, unit: String?): Double =
        if (cfg != null && (cfg.num("inc") ?: 0.0) > 0) cfg.num("inc")!! else Ex.defaultIncrement(cfg?.get("id"), unit)

    // progression.js:98-105 policyFor（自带覆盖 → 例程默认 → 模式默认；金字塔恒 off）
    fun policyFor(cfg: JsonObject?, routine: JsonObject?, mode: String?): String {
        if (isPyramid(cfg)) return "off"
        val m = mode?.takeIf { it.isNotEmpty() } ?: History.modeOf(cfg ?: Js.emptyObj())   // JS: mode || modeOf(cfg || {})
        val allowed = POLICIES_FOR[m] ?: listOf("off")
        val pick = cfg.str("prog")?.takeIf { it.isNotEmpty() }
            ?: routine.str("prog")?.takeIf { it.isNotEmpty() }
            ?: if (m == "reps") "linear" else "off"
        return if (allowed.contains(pick)) pick else "off"
    }

    // progression.js:107 round1
    private fun round1(v: Double): Double = jsRound(v * 10) / 10

    // progression.js:110-113 snapWeight（吸附到可载的步长倍数）
    fun snapWeight(v: Double, step: Double): Double =
        if (!(step > 0)) round1(v) else round1(jsRound(v / step) * step)

    // progression.js:121-126 addStep（在格上吸附、离格直接加——issue #175 的同一规则）
    fun addStep(w: Double, step: Double, inc: Double): Double {
        val v = w
        val onGrid = inc > 0 && abs(v - jsRound(v / inc) * inc) <= 0.1
        val next = v + step
        return max(0.0, if (onGrid) snapWeight(next, inc) else round1(next))
    }

    // progression.js:127-130 stepWeight（方向化单步）
    fun stepWeight(value: Double, step: Double, direction: Double): Double =
        addStep(value, direction * step, step)

    // progression.js:133-137 deloadTo（按因子回退，落在可载重量上）
    fun deloadTo(cur: Double, step: Double, factor: Double = DELOAD_FACTOR): Double {
        var next = snapWeight(cur * factor, step)
        if (next >= cur) next = snapWeight(cur - step, step)
        return max(step, next)
    }

    // progression.js:139-147 positiveGridAround（理想值两侧的载重网格点）
    private fun positiveGridAround(ideal: Double, step: Double, maxWeight: Double, strictLower: Boolean): List<Double> {
        if (!(ideal > 0) || !(step > 0) || !(maxWeight > 0)) return emptyList()
        val low = floor(ideal / step) * step
        val high = ceil(ideal / step) * step
        val values = listOf(low, high).map { snapWeight(it, step) }.distinct()
        return values
            .filter { v -> v > 0 && v <= maxWeight + 1e-9 && (!strictLower || v < maxWeight - 1e-9) }
            .sorted()
    }

    /**
     * progression.js:156-207 selectDeloadCandidate——为 Epley 目标挑一组有界的载重/次数。
     * 字典序搜索：先硬约束，再最接近的估算 1RM、更少的次数变化、更大的载重。
     * `owned` 给定时（哑铃库存）候选来自库存而非步长网格。
     */
    private class Candidate(
        val weight: Double, val reps: Double, val epley: Double,
        val error: Double, val repChange: Double, val fallback: Boolean,
    )

    fun selectDeloadCandidate(a: JsonObject): JsonObject? {
        val current = a.num("currentWeight") ?: Double.NaN
        val baseWeight = a.num("targetWeight") ?: Double.NaN
        val baseReps = a.num("targetReps") ?: Double.NaN
        val perSide = a.bool("perSide") == true
        val stride = if (perSide) 2.0 else 1.0
        val upper = max(stride, ceil(baseReps / stride) * stride)
        val repsMinRaw = a.get("repsMin")?.takeIf { it !is JsonNull }
        val range = repsMinRaw?.let { normalizeRepRange(a.num("reps") ?: Double.NaN, it.numValue(), stride) }
        val top = range?.let { min(it.reps, max(it.repsMin, upper)) } ?: upper
        val bottom = range?.repsMin ?: top
        val repValues = mutableListOf<Double>()
        var r = top
        while (r >= bottom) {
            repValues.add(r)
            r -= stride
        }
        val step = a.num("step") ?: 0.0
        val target1RM = deloadTarget1RM(baseWeight, upper, a.num("factor") ?: DELOAD_FACTOR, perSide)
        if (!(current > 0) || target1RM == null || repValues.isEmpty() || !(step > 0)) return null

        val owned = (a.get("owned") as? JsonArray)?.mapNotNull { el -> el.numValue() }?.takeIf { it.isNotEmpty() }
        val candidates = mutableListOf<Candidate>()
        for (candidateReps in repValues) {
            val ideal = target1RM / (1 + (if (perSide) candidateReps / 2 else candidateReps) / 30)
            val allowCurrent = repsMinRaw != null && candidateReps < upper
            val grid = owned?.let { Dumbbells.ownedAround(ideal, it, current, !allowCurrent) }
                ?: positiveGridAround(ideal, step, current, !allowCurrent)
            val full = if (allowCurrent && !grid.contains(current)) grid + current else grid
            for (candidateWeight in full) {
                val epley = epley1RM(candidateWeight, if (perSide) candidateReps / 2 else candidateReps) ?: continue
                candidates.add(Candidate(candidateWeight, candidateReps, epley, abs(epley - target1RM), abs(candidateReps - upper), false))
            }
        }
        // 微小/低于步长的举法可能没有更低的网格点：原地守住是显式且确定的兜底
        if (candidates.isEmpty()) {
            for (candidateReps in repValues) {
                val epley = epley1RM(current, if (perSide) candidateReps / 2 else candidateReps) ?: continue
                candidates.add(Candidate(current, candidateReps, epley, abs(epley - target1RM), abs(candidateReps - upper), true))
            }
        }
        if (candidates.isEmpty()) return null
        val best = candidates.sortedWith(compareBy({ it.error }, { it.repChange }, { -it.weight }, { -it.reps }))[0]
        return Js.obj(
            "weight" to jn(best.weight),
            "reps" to jn(best.reps),
            "epley" to jn(best.epley),
            "error" to jn(best.error),
            "repChange" to jn(best.repChange),
            "fallback" to JsonPrimitive(best.fallback),
            "target1RM" to jn(target1RM),
            "factor" to jn(deloadFactorOf(Js.obj("deloadFactor" to a.get("factor")))),
            "upper" to jn(upper),
            "bottom" to jn(bottom),
        )
    }

    // progression.js:217-228 plannedOf（会话开建时的处方快照，#275）
    fun plannedOf(cfg: JsonObject?): JsonObject {
        val c = cfg ?: Js.emptyObj()
        val mode = History.modeOf(c)
        val out = LinkedHashMap<String, JsonElement>()
        out["sets"] = jn(max(1.0, c.numOr("sets", 1.0)))
        if (mode == "reps" && (c.num("reps") ?: 0.0) > 0) out["reps"] = jn(c.num("reps")!!)
        if (mode == "reps" && (c.num("repsMin") ?: 0.0) > 0) out["repsMin"] = jn(c.num("repsMin")!!)
        if (mode == "time" && (c.num("sec") ?: 0.0) > 0) out["sec"] = jn(c.num("sec")!!)
        if (mode == "reps" && (c.num("setsMax") ?: 0.0) > 0) out["setsMax"] = jn(c.num("setsMax")!!)
        if (c.hasNotNull("weight")) out["weight"] = c.get("weight")!!
        return JsonObject(out)
    }

    // progression.js:234 climbsReps（无载可录的工作以次数/组数爬升）
    private fun climbsReps(cfg: JsonObject?): Boolean =
        History.isBw(cfg) || !Ex.isLoadedEq(cfg?.get("id")) || Ex.isAssisted(cfg ?: Js.emptyObj())

    // progression.js:236-241 PLAN_KEYS / samePlan / planChanged
    private val PLAN_KEYS = listOf("sets", "reps", "repsMin", "sec", "setsMax")

    private fun planKeyEq(x: JsonElement?, y: JsonElement?): Boolean {
        val xv = if (x == null || x is JsonNull) null else x
        val yv = if (y == null || y is JsonNull) null else y
        if (xv == null && yv == null) return true
        if (xv == null || yv == null) return false
        // JS === 无整型/浮点之分：数字原语按数值比较（"2" vs "2.0" 的 content 串等在 JS 里恒真）
        val xn = (xv as? JsonPrimitive)?.doubleOrNull
        val yn = (yv as? JsonPrimitive)?.doubleOrNull
        return if (xn != null && yn != null) xn == yn else xv == yv
    }

    private fun samePlan(a: JsonObject?, b: JsonObject?): Boolean =
        PLAN_KEYS.all { planKeyEq(a?.get(it), b?.get(it)) }

    fun planChanged(planned: JsonObject?, cfg: JsonObject?): Boolean =
        planned != null && !samePlan(planned, plannedOf(cfg))

    // ── progression.js:255-260 loadOf（会话被谁衡量：辅助器械取最小助力，其余取最大载重）──
    private fun loadOf(entry: JsonObject?, sets: List<JsonObject>): Double {
        val done = sets.filter { jsTruthy(it.get("done")) }.map { it.numOr("w") }
        val id = entry.str("id")
        val assistedArg: JsonElement? = if (id != null) JsonPrimitive(id) else entry
        return if (!Ex.isAssisted(assistedArg)) {
            maxOf(0.0, *done.toDoubleArray())
        } else {
            val loaded = done.filter { it > 0 }
            if (loaded.isNotEmpty()) loaded.min() else 0.0
        }
    }

    /**
     * progression.js:262-320 readSession——把一个完成条目还原成策略可判的读数。
     * 热身行是准备不是会话；per-side 计时的行数翻倍；只有计划要求的组数说了算（#233）；
     * back-off 组取轻了不算完成（backoffStep 只属于带着它构建的会话）。
     */
    fun readSession(entry: JsonObject?, fallback: JsonObject? = null): JsonObject {
        val rawTarget = entry?.get("target")
        val target = (rawTarget as? JsonObject) ?: fallback ?: Js.emptyObj()
        val mode = History.modeOf(Js.spread(target, Js.obj("id" to entry?.get("id"))))
        val logged = ((entry?.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject })
            .filter { !WorkoutModel.isWarmupRow(it) }
        val rowGoals: List<Double>? =
            if (mode == "reps" && entry != null && rawTarget is JsonObject &&
                (target.arr("rowReps")?.isNotEmpty() == true)
            ) target.arr("rowReps")!!.map { max(0.0, it.doubleOr0()) } else null
        val plannedRows = rowGoals?.size?.toDouble() ?: target.numOr("sets", logged.size.toDouble())
        val planned = if (mode == "time" && History.isPerSide(target)) plannedRows * 2 else plannedRows
        val enough = logged.size.toDouble() >= planned
        val sets = if (entry != null && rawTarget != null && rawTarget !is JsonNull)
            logged.take(max(1.0, planned).toInt()) else logged

        if (mode == "time") {
            val goal = target.numOr("sec")
            val held = sets.map { if (jsTruthy(it.get("done"))) it.numOr("sec") ?: 0.0 else 0.0 }
            return Js.obj(
                "mode" to JsonPrimitive(mode),
                "target" to target,
                "goal" to jn(goal),
                "held" to jarrD(held),
                "weight" to jn(loadOf(entry, sets)),
                "best" to jn(maxOf(0.0, *held.toDoubleArray())),
                "ok" to JsonPrimitive((goal ?: 0.0) > 0.0 && enough && held.isNotEmpty() && held.all { it >= (goal ?: 0.0) }),
            )
        }
        val goal = rowGoals?.max() ?: target.numOr("reps")
        val step = if (entry != null && rawTarget != null && rawTarget !is JsonNull)
            (rawTarget as? JsonObject)?.num("backoffStep")?.takeIf { it.isFinite() && it != 0.0 } ?: 0.0
        else 0.0
        val top = if (step > 0 && sets.isNotEmpty()) sets[0].numOr("w") else 0.0
        val reps = sets.mapIndexed { k, s ->
            val heavyEnough = !(top > 0) || k == 0 || s.numOr("w") >= Backoff.backoffAt(top, k.toDouble(), step) - 0.05
            if (jsTruthy(s.get("done")) && heavyEnough) s.numOr("r") else 0.0
        }
        return Js.obj(
            "mode" to JsonPrimitive(mode),
            "target" to target,
            "goal" to jn(goal),
            "reps" to jarrD(reps),
            "weight" to jn(loadOf(entry, sets)),
            "count" to jn(reps.size),
            "low" to jn(if (reps.isNotEmpty()) reps.min() else 0.0),
            "amrap" to jn(if (reps.isNotEmpty()) reps.last() else 0.0),
            "ok" to JsonPrimitive(
                goal > 0 && enough && reps.isNotEmpty() &&
                    reps.indices.all { k -> reps[k] >= (rowGoals?.getOrNull(k) ?: goal) }
            ),
        )
    }

    // progression.js:332-338 sessionsFor（例程自己的会话优先，否则全历史；#216）
    fun sessionsFor(S: JsonObject?, exId: String?, fallback: JsonObject?, rid: String?): List<JsonObject> {
        if (rid != null && rid.isNotEmpty()) {
            val own = sessionsIn(S, exId, fallback, rid)
            if (own.isNotEmpty()) return own
        }
        return sessionsIn(S, exId, fallback, null)
    }

    // progression.js:340-356 sessionsIn
    private fun sessionsIn(S: JsonObject?, exId: String?, fallback: JsonObject?, rid: String?): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (w0 in S?.arr("workouts") ?: emptyList()) {
            val w = w0 as? JsonObject ?: continue
            val entry = (w.arr("entries") ?: emptyList()).mapNotNull { it as? JsonObject }.firstOrNull { e ->
                e.str("id") == exId && (rid == null || History.entryRoutineId(w, e) == rid)
            } ?: continue
            if (History.entryExcluded(w, entry)) continue
            val hasCounted = (entry.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject }
                .any { jsTruthy(it.get("done")) && !WorkoutModel.isWarmupRow(it) }
            if (!hasCounted) continue
            val slot = History.entryRoutineId(w, entry)
            var merged = Js.obj("d" to w.get("d"))
            if (slot != null) merged = Js.with(merged, "rid", JsonPrimitive(slot))
            val planned = entry.get("planned")
            if (planned != null && planned !is JsonNull) merged = Js.with(merged, "planned", planned)
            merged = Js.spread(merged, readSession(entry, fallback))
            out.add(merged)
        }
        return out
    }

    // ── triple progression（progression.js:358-433，issue #179）──

    // progression.js:370-374 tripleSetsOf
    data class TripleSets(val setsMin: Double, val setsMax: Double)

    fun tripleSetsOf(cfg: JsonObject?): TripleSets {
        val setsMin = max(1.0, cfg.num("sets")?.let { jsRound(it).takeIf { v -> v.isFinite() } } ?: 1.0)
        val setsMax = max(setsMin, min(MAX_TRIPLE_SETS.toDouble(), cfg.num("setsMax")?.let { jsRound(it).takeIf { v -> v.isFinite() } } ?: setsMin))
        return TripleSets(setsMin, setsMax)
    }

    // progression.js:377-383 tripleRowsOf（会话被要求的每组目标）
    private fun tripleRowsOf(session: JsonObject?, setsMin: Double, setsMax: Double, range: RepRange): List<Double> {
        val own = session?.sub("target")?.arr("rowReps")
        if (own != null && own.isNotEmpty()) return own.map { max(0.0, it.doubleOr0()) }
        val t = session?.sub("target")
        val n = min(setsMax, max(setsMin, t.numOr("sets", setsMin)))
        val goalFallback = session.numOr("goal", range.repsMin)
        val reps = min(range.reps, max(range.repsMin, t.numOr("reps", goalFallback)))
        return List(n.toInt()) { reps }
    }

    // progression.js:387-393 tripleActive（triple 推进所依据的数字）
    private fun tripleActive(session: JsonObject?): Double {
        val rows = session?.sub("target")?.arr("rowReps")
        val reps = session?.arr("reps") ?: emptyList()
        if (rows == null || rows.isEmpty()) return session.numOr("low")
        val rowVals = rows.map { it.doubleOr0() }
        return if (rowVals.all { it == rowVals.first() })
            rowVals.indices.minOf { k -> reps.getOrNull(k).doubleOr0() }
        else reps.getOrNull(rowVals.size - 1).doubleOr0()
    }

    // progression.js:394 sameRows（JSON.stringify 对比 rowReps）
    private fun sameRows(a: JsonObject?, b: JsonObject?): Boolean {
        val x = a?.sub("target")?.get("rowReps") ?: JsonNull
        val y = b?.sub("target")?.get("rowReps") ?: JsonNull
        return x == y
    }

    /**
     * progression.js:401-433 stallCount——从最近往前数连续 miss。
     * 换重量、计划被编辑、double/triple 下打破本重量段的最优，都会终结 streak。
     */
    fun stallCount(sessions: List<JsonObject>, policy: String? = null): Int {
        var n = 0
        for (i in sessions.indices.reversed()) {
            val s = sessions[i]
            if (s.bool("ok") == true) break
            if (i < sessions.size - 1 && s.numN("weight") != sessions[i + 1].numN("weight")) break
            if (i < sessions.size - 1 && s.hasNotNull("planned") && sessions[i + 1].hasNotNull("planned") &&
                !samePlan(s.sub("planned"), sessions[i + 1].sub("planned"))
            ) break
            if (policy == "double") {
                val run = mutableListOf<Double>()
                var j = i - 1
                while (j >= 0 && sessions[j].numN("weight") == s.numN("weight")) {
                    run.add(sessions[j].numOr("low"))
                    j--
                }
                if (run.isNotEmpty() && s.numOr("low") > run.max()) break
            }
            if (policy == "triple") {
                val run = mutableListOf<Double>()
                var j = i - 1
                while (j >= 0 && sessions[j].numN("weight") == s.numN("weight") && sameRows(sessions[j], s)) {
                    run.add(tripleActive(sessions[j]))
                    j--
                }
                if (run.isNotEmpty() && tripleActive(s) > run.max()) break
            }
            n++
        }
        return n
    }

    /**
     * progression.js:445-745 nextPrescription——一个动作的下一次处方。
     * 返回 { policy, kind, weight?, reps?, sec?, sets?, rowReps?, target1RM?, deloadFactor?, why }，
     * kind ∈ first | up | hold | deload | off；why 是「模板 + 参数」数组，App 侧永远答得出「为什么是这个数」。
     */
    fun nextPrescription(S: JsonObject?, cfg: JsonObject, routine: JsonObject? = null): JsonObject {
        val mode = History.modeOf(cfg)
        val policy = policyFor(cfg, routine, mode)
        val unit = S.str("unit")?.takeIf { it.isNotEmpty() } ?: "kg"   // JS S.unit || 'kg'
        val inc = if (mode == "time") {
            if ((cfg.num("inc") ?: 0.0) > 0) cfg.num("inc")!! else DEFAULT_SEC_INCREMENT
        } else weightIncrement(cfg, unit)
        if (policy == "off") return Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("off"))

        // 辅助器械向下渐进（#232）；哑铃库存替代增量网格（#376）
        val assisted = Ex.isAssisted(cfg)
        val owned = if (mode == "reps" && !assisted) Dumbbells.ownedWeightsFor(S, cfg) else null
        fun harder(weight: Double, step: Double): Double =
            if (assisted) max(0.0, addStep(weight, -step, inc))
            else owned?.let { Dumbbells.ownedUp(it, weight, if (step > inc) 2 else 1) ?: weight }
                ?: addStep(weight, step, inc)
        fun easier(weight: Double): Double =
            if (assisted) addStep(weight, inc, inc)
            else owned?.let { Dumbbells.ownedDeload(it, weight, DELOAD_FACTOR) } ?: deloadTo(weight, inc)
        fun topped(weight: Double): Boolean = owned != null && Dumbbells.ownedUp(owned, weight) == null
        fun toppedOut(weight: Double, extra: JsonObject): JsonObject = Js.spread(
            Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold"), "weight" to jn(weight)),
            extra,
            Js.obj("why" to why("You’ve outgrown the rack: {0} {1} is your heaviest dumbbell, so the weight stays.", weight, unit)),
        )

        val sessions = sessionsFor(S, cfg.str("id"), cfg, routine.str("id")).filter { it.str("mode") == mode }
        val last = sessions.lastOrNull()

        // triple progression 从基组数与区间底部开起步（progression.js:477-480）
        val triple = policy == "triple" && mode == "reps"
        val range3 = if (triple) normalizeRepRange(cfg.num("reps") ?: 10.0, cfg.num("repsMin"), History.repStep(cfg)) else null
        val sets3 = if (triple) tripleSetsOf(cfg) else null
        fun startRows(reps: Double): List<Double> = List(sets3!!.setsMin.toInt()) { reps }

        if (last == null) {
            return if (triple) Js.obj(
                "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("first"),
                "reps" to jn(range3!!.repsMin), "sets" to jn(sets3!!.setsMin),
                "rowReps" to jarrD(startRows(range3.repsMin)),
                "why" to why("Nothing logged yet, so this session sets the baseline."),
            ) else Js.obj(
                "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("first"),
                "why" to why("Nothing logged yet, so this session sets the baseline."),
            )
        }

        // 计划改动 / 借来的会话 → 从计划重开（#275，progression.js:486-520）
        val borrowed = routine.str("id")?.isNotEmpty() == true && last.str("rid") != routine.str("id")
        val restart = if (last.hasNotNull("planned")) planChanged(last.sub("planned"), cfg) else borrowed
        if (restart) {
            val whyArr = if (borrowed) why("First time in this routine, so starting from its own target.")
            else why("Plan changed, so starting from your new target.")
            val cw = cfg.num("weight")
            val pw = last.sub("planned").numN("weight")
            val set = if ((cw ?: 0.0) > 0 && last.hasNotNull("planned") && pw != cw) Js.obj("weight" to jn(cw!!)) else null
            if (mode == "time") {
                val sec = chainNum(cfg.num("sec"), last.numN("goal"))
                return Js.spread(
                    Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold")),
                    set ?: Js.emptyObj(),
                    (if (sec != null) Js.obj("sec" to jn(sec), "why" to whyArr) else Js.obj("why" to whyArr)),
                )
            }
            val lw = last.numN("weight")
            if (set == null && lw != null && lw <= 0 && climbsReps(cfg)) {
                val repsV = chainNum(cfg.num("reps"))
                return Js.spread(
                    Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold"), "weight" to jn(0.0)),
                    (if (repsV != null) Js.obj("reps" to jn(repsV), "why" to whyArr) else Js.obj("why" to whyArr)),
                )
            }
            val held = set ?: when {
                (lw ?: Double.NaN) > 0 -> Js.obj("weight" to jn(lw!!))
                (cw ?: 0.0) > 0 -> Js.obj("weight" to jn(cw!!))
                else -> Js.emptyObj()
            }
            if (triple) {
                val aim = if (set != null) range3!!.repsMin
                else min(range3!!.reps, max(range3.repsMin, last.numOr("low") + History.repStep(cfg)))
                return Js.spread(
                    Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold")),
                    held,
                    Js.obj("reps" to jn(aim), "sets" to jn(sets3!!.setsMin), "rowReps" to jarrD(startRows(aim)), "why" to whyArr),
                )
            }
            if (policy == "double") {
                val range = normalizeRepRange(chainNum(cfg.num("reps"), last.numN("goal")) ?: 10.0, cfg.num("repsMin"), History.repStep(cfg))
                val aim = if (set != null) range.repsMin
                else min(range.reps, max(range.repsMin, last.numOr("low") + History.repStep(cfg)))
                return Js.spread(
                    Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold")),
                    held,
                    Js.obj("reps" to jn(aim), "why" to whyArr),
                )
            }
            val repsV = chainNum(cfg.num("reps"))
            return Js.spread(
                Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold")),
                held,
                (if (repsV != null) Js.obj("reps" to jn(repsV), "why" to whyArr) else Js.obj("why" to whyArr)),
            )
        }

        val stalls = stallCount(sessions, policy).toDouble()
        val deloadAt = (DELOAD_AFTER[policy] ?: 3).toDouble()

        // 计时策略（progression.js:525-535）
        if (mode == "time") {
            if (last.bool("ok") == true) {
                val sec = chainNum(last.numN("goal"), cfg.num("sec"))?.let { it + inc } ?: inc
                return Js.obj(
                    "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("up"), "sec" to jn(sec),
                    "why" to why("Held every set for the full time. Target goes up by {0}s.", inc),
                )
            }
            if (stalls >= deloadAt) {
                val sec = deloadTo(chainNum(last.numN("goal"), cfg.num("sec")) ?: 0.0, 5.0)
                return Js.obj(
                    "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("deload"), "sec" to jn(sec),
                    "why" to why("Short {0} sessions in a row. Back off to {1}s and build up again.", stalls, sec),
                )
            }
            val holdSec = chainNum(last.numN("goal"), cfg.num("sec"))
            return Js.spread(
                Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold")),
                (if (holdSec != null) Js.obj("sec" to jn(holdSec), "why" to why("Came up short last time. Same target again."))
                else Js.obj("why" to why("Came up short last time. Same target again."))),
            )
        }

        val w = last.numN("weight") ?: 0.0

        // 自重工作以次数/组数爬升（progression.js:543-569）
        if (w <= 0 && climbsReps(cfg)) {
            val goal = chainNum(last.numN("goal"), cfg.num("reps")) ?: 0.0
            val planSets = max(1.0, cfg.numOr("sets", 1.0))
            val reached = if (last.hasNotNull("planned"))
                max(planSets, last.sub("target")?.numOr("sets", 0.0) ?: 0.0) else planSets
            val keep = if (reached > planSets) Js.obj("sets" to jn(reached)) else Js.emptyObj()
            if (last.bool("ok") != true || goal <= 0) {
                val repsV = chainNum(goal)
                return Js.spread(
                    Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold"), "weight" to jn(0.0)),
                    keep,
                    (if (repsV != null) Js.obj("reps" to jn(repsV), "why" to why("Bodyweight: same target again until every set is clean."))
                    else Js.obj("why" to why("Bodyweight: same target again until every set is clean."))),
                )
            }
            val top = if ((cfg.num("repsMax") ?: 0.0) > 0) cfg.num("repsMax")!! else 0.0
            if (top > 0 && goal >= top) {
                val setsN = reached + 1
                val bottom = max(1.0, min(chainNum(cfg.num("reps")) ?: top, top))
                if (setsN <= MAX_BW_SETS) {
                    return Js.obj(
                        "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("up"),
                        "weight" to jn(0.0), "reps" to jn(bottom), "sets" to jn(setsN),
                        "why" to why("{0} reps in every set! Add a set and go back to {1}.", goal, bottom),
                    )
                }
                return Js.spread(
                    Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold"), "weight" to jn(0.0), "reps" to jn(goal)),
                    keep,
                    Js.obj("why" to why("{0} sets of {1}. Time to add weight or try a harder variation.", setsN - 1, goal)),
                )
            }
            val next = goal + History.repStep(cfg)
            return Js.spread(
                Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("up"), "weight" to jn(0.0), "reps" to jn(next)),
                keep,
                Js.obj("why" to why("Bodyweight: every rep last time, so go for {0} this time.", next)),
            )
        }

        // 载入 0 kg 的负重组——要重量，不爬次数（progression.js:574）
        if (w <= 0) {
            val hasPlanWeight = (cfg.num("weight") ?: 0.0) > 0
            return Js.spread(
                Js.obj("policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold")),
                (if (hasPlanWeight) Js.obj("weight" to jn(cfg.num("weight")!!)) else Js.emptyObj()),
                Js.obj("why" to why("No weight logged last time. Enter what you lift and progression takes it from there.")),
            )
        }

        // Epley deload 只作用于外加负重的 rep 工作（progression.js:579-626）
        fun epleyDeload(): JsonObject? {
            if (assisted) return null
            if (mode != "reps" || (policy != "linear" && policy != "double")) return null
            val previous = last.sub("target") ?: Js.emptyObj()
            val merged = LinkedHashMap<String, JsonElement>(cfg)
            for ((k, v) in previous) merged[k] = v
            fun coal(first: JsonElement?, second: JsonElement?): JsonElement? =
                first?.takeIf { it !is JsonNull } ?: second?.takeIf { it !is JsonNull }
            // weight/reps/repsMin/bodyweight/side/intensifier：上次会话优先（?? 语义）；sets 永远是计划的（cfg 优先）
            for (key in listOf("weight", "reps", "repsMin", "bodyweight", "side", "intensifier")) {
                val v = coal(previous.get(key), cfg.get(key))
                if (v == null) merged.remove(key) else merged[key] = v
            }
            val setsV = coal(cfg.get("sets"), previous.get("sets"))
            if (setsV == null) merged.remove("sets") else merged["sets"] = setsV
            val t = JsonObject(merged)
            if (History.isBw(t) || t.sub("intensifier")?.str("type") == "restpause") return null
            val candidate = selectDeloadCandidate(Js.obj(
                "currentWeight" to jn(w),
                "targetWeight" to t.get("weight"),
                "targetReps" to t.get("reps"),
                "step" to jn(inc),
                "factor" to jn(deloadFactorOf(cfg)),
                "reps" to t.get("reps"),
                "repsMin" to (if (policy == "double") t.get("repsMin") else null),
                "perSide" to JsonPrimitive(History.isPerSide(t)),
                "owned" to owned?.let { JsonArray(it.map { bell -> JsonPrimitive(bell) }) },
            )) ?: return null
            val candW = candidate.num("weight")!!
            val candReps = candidate.num("reps")!!
            val held = candW >= w
            return Js.obj(
                "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("deload"),
                "weight" to jn(candW), "reps" to jn(candReps),
                "sets" to t.numN("sets")?.let { jn(it) },   // JS undefined → 键不落
                "target1RM" to jn(candidate.num("target1RM")!!),
                "deloadFactor" to jn(candidate.num("factor")!!),
                "why" to (if (held)
                    why("Stuck for {0} sessions. Hold {1} {2} and use {3} reps.", stalls, candW, unit, candReps)
                else why("Stuck for {0} sessions. Epley deload to {1} {2} for {3} reps.", stalls, candW, unit, candReps)),
            )
        }

        // triple：次数 → 组数 → 载重（progression.js:628-675）
        if (triple) {
            val top = range3!!.reps
            val bottom = range3.repsMin
            val setsMin = sets3!!.setsMin
            val setsMax = sets3.setsMax
            val step = History.repStep(cfg)
            val rows = tripleRowsOf(last, setsMin, setsMax, range3)
            fun out(kind: String, weight: Double, next: List<Double>, whyArr: JsonArray): JsonObject = Js.obj(
                "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive(kind), "weight" to jn(weight),
                "reps" to jn(next.first()), "sets" to jn(next.size), "rowReps" to jarrD(next), "why" to whyArr,
            )
            if (stalls >= deloadAt) {
                var dw: Double? = null
                if (!assisted) {
                    val perSide = History.isPerSide(cfg)
                    val t1rm = deloadTarget1RM(w, rows.max(), deloadFactorOf(cfg), perSide)
                    val ideal = t1rm?.let { it / (1 + (if (perSide) bottom / 2 else bottom) / 30) }
                    if (ideal != null) {
                        val grid = positiveGridAround(ideal, inc, w, true)
                        if (grid.isNotEmpty()) dw = grid.reduce { a, b -> if (abs(b - ideal) < abs(a - ideal)) b else a }
                    }
                }
                if (dw == null) dw = easier(w)
                return out(
                    "deload", dw, startRows(bottom),
                    if (assisted) why("Stuck for {0} sessions. Back to {1} {2} of help, start again at {3} sets of {4}.", stalls, dw, unit, setsMin, bottom)
                    else why("Stuck for {0} sessions. Deload to {1} {2} and start again at {3} sets of {4}.", stalls, dw, unit, setsMin, bottom),
                )
            }
            if (last.bool("ok") != true) {
                return out(
                    "hold", w, rows,
                    if (stalls == 0.0) why("Short of the target, but more reps than before. Same sets and reps again.")
                    else why("Missed reps last time. Same sets and reps again ({0} of {1} to go).", deloadAt - stalls, deloadAt),
                )
            }
            val lastReps = last.arr("reps") ?: emptyList()
            val got = rows.mapIndexed { k, r -> min(top, max(r, lastReps.getOrNull(k).doubleOr0())) }
            fun climb(v: Double): Double = min(top, max(bottom, v + step))
            val n = got.size
            val baseLow = got.take(min(n, setsMin.toInt())).min()
            if (n <= setsMin && baseLow < top) {
                val aim = climb(baseLow)
                return out("hold", w, List(max(n, setsMin.toInt())) { aim },
                    why("Same weight, aim for {0} reps in every set.", aim))
            }
            val active = got[n - 1]
            if (n > setsMin && active < top) {
                val aim = climb(active)
                return out("hold", w, got.take(n - 1).map { top } + aim,
                    why("Same weight, aim for {0} reps on the last set.", aim))
            }
            if (n < setsMax) return out("up", w, got.map { top } + bottom,
                why("{0} reps in every set! Add a set at {1} reps.", top, bottom))
            return out(
                "up", harder(w, inc), startRows(bottom),
                if (assisted) why("Top of the range on all {0} sets. {1} {2} less help, back to {3} sets of {4}.", n, inc, unit, setsMin, bottom)
                else why("Top of the range on all {0} sets. {1} {2} more, back to {3} sets of {4}.", n, inc, unit, setsMin, bottom),
            )
        }

        // double progression（progression.js:677-711）
        if (policy == "double") {
            val range = normalizeRepRange(chainNum(cfg.num("reps"), last.numN("goal")) ?: 10.0, cfg.num("repsMin"), History.repStep(cfg))
            val top = range.reps
            val bottom = range.repsMin
            if (last.bool("ok") == true && last.numOr("low") >= top) {
                if (topped(w)) return toppedOut(w, Js.obj("reps" to jn(top)))
                val up = harder(w, inc)
                val by = owned?.let { jsRound((up - w) * 100) / 100 } ?: inc
                return Js.obj(
                    "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("up"),
                    "weight" to jn(up), "reps" to jn(bottom),
                    "why" to (if (assisted) why("Top of the rep range in every set. {0} {1} less help, back to {2} reps.", inc, unit, bottom)
                    else why("Top of the rep range in every set. {0} {1} more, back to {2} reps.", by, unit, bottom)),
                )
            }
            if (stalls >= deloadAt) {
                epleyDeload()?.let { return it }
                val dw = easier(w)
                return Js.obj(
                    "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("deload"),
                    "weight" to jn(dw), "reps" to jn(bottom),
                    "why" to (if (assisted) why("Stuck for {0} sessions. Back to {1} {2} of help, then build up again.", stalls, dw, unit)
                    else why("Stuck for {0} sessions. Deload to {1} {2}.", stalls, dw, unit)),
                )
            }
            val aim = min(top, max(bottom, last.numOr("low") + History.repStep(cfg)))
            return Js.obj(
                "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold"),
                "weight" to jn(w), "reps" to jn(aim),
                "why" to why("Same weight, aim for {0} reps this time.", aim),
            )
        }

        // linear + greyskull（progression.js:713-744）
        if (last.bool("ok") == true) {
            val dbl = policy == "greyskull" && last.numOr("goal") > 0 && last.numOr("amrap") >= last.numOr("goal") * 2
            if (topped(w)) return toppedOut(w, Js.emptyObj())
            val up = harder(w, if (dbl) inc * 2 else inc)
            val stepTxt = owned?.let { jsRound((up - w) * 100) / 100 } ?: (if (dbl) inc * 2 else inc)
            return Js.obj(
                "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("up"), "weight" to jn(up),
                "why" to (when {
                    dbl -> why("Last set hit {0} reps, twice the target! Take a double jump of {1} {2}.", last.numOr("amrap"), stepTxt, unit)
                    assisted -> why("Every rep last time. {0} {1} less help.", stepTxt, unit)
                    else -> why("Every rep last time. {0} {1} more.", stepTxt, unit)
                }),
            )
        }
        if (stalls >= deloadAt) {
            epleyDeload()?.let { return it }
            val dw = easier(w)
            return Js.obj(
                "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("deload"), "weight" to jn(dw),
                "why" to (when {
                    assisted -> why("Missed reps. {0} {1} of help while you build back up.", dw, unit)
                    stalls > 1 -> why("Missed reps {0} sessions running. Reset to {1} {2} and work back up.", stalls, dw, unit)
                    else -> why("Missed reps. Reset to {0} {1} and work back up.", dw, unit)
                }),
            )
        }
        return Js.obj(
            "policy" to JsonPrimitive(policy), "kind" to JsonPrimitive("hold"), "weight" to jn(w),
            "why" to why("Missed reps last time. Same weight again ({0} of {1} to go).", deloadAt - stalls, deloadAt),
        )
    }

    /** why 数组：模板 + 参数（progression.js 各返回点） */
    private fun why(template: String, vararg args: Any?): JsonArray {
        val out = mutableListOf<JsonElement>(JsonPrimitive(template))
        for (a in args) {
            out.add(when (a) {
                is String -> JsonPrimitive(a)
                is Int -> JsonPrimitive(a)
                is Long -> JsonPrimitive(a)
                is Double -> JsonPrimitive(a)
                else -> JsonPrimitive(a.toString())
            })
        }
        return JsonArray(out)
    }

    /**
     * progression.js:752-819 applyPrescription——把处方落到新构建的组上。
     * 只动策略真正决定的字段，只动还没记录的组；`floor` 是杠重（history.js barFloor），
     * 重 ramp 的热身组不会低于它。
     */
    fun applyPrescription(sets: List<JsonObject>, p: JsonObject?, step: Double = 2.5, floor: Double = 0.0): List<JsonObject> {
        if (p == null || p.str("kind") == "off" || (p.str("kind") == "first" && p.arr("rowReps")?.isNotEmpty() != true)) return sets
        var out = sets.map { s ->
            if (jsTruthy(s.get("done"))) return@map s
            if (WorkoutModel.isWarmupRow(s)) {
                if (p.numN("reps") != null && s.numN("r") != null) return@map Js.with(s, "r", jn(p.numN("reps")!!))
                if (p.numN("sec") != null && s.numN("sec") != null) return@map Js.with(s, "sec", jn(p.numN("sec")!!))
                return@map s
            }
            if (WorkoutModel.isSideSet(s)) {
                val sides = s.sub("sides")!!
                fun sideRow(side: JsonObject?): JsonObject {
                    if (side?.bool("done") == true) return side
                    var o = side ?: Js.emptyObj()
                    p.numN("weight")?.let { o = Js.with(o, "w", jn(it)) }
                    p.numN("reps")?.let { o = Js.with(o, "r", jn(it / 2)) }
                    return o
                }
                return@map WorkoutModel.syncSideAggregate(
                    Js.with(s, "sides", Js.obj("L" to sideRow(sides.sub("L")), "R" to sideRow(sides.sub("R"))))
                )
            }
            var o = s
            p.numN("weight")?.let { o = Js.with(o, "w", jn(it)) }
            p.numN("reps")?.let { o = Js.with(o, "r", jn(it)) }
            p.numN("sec")?.let { o = Js.with(o, "sec", jn(it)) }
            o
        }
        // 策略决定了组数 → 只向上长，且只复制已有行（#33）
        val pSets = p.numN("sets")
        if (pSets != null) {
            val workRows = out.filter { !WorkoutModel.isWarmupRow(it) }
            if (pSets > workRows.size) {
                if (workRows.isEmpty()) return History.rerampWarmups(out, step, floor)
                val seed = workRows.last()
                // 新追加的行不继承已记录的 drops/clusters；`type` 保留（那是计划，不是某组记下的）
                val plainSeed = Js.without(Js.without(seed, "drops"), "clusters")
                while (out.filter { !WorkoutModel.isWarmupRow(it) }.size < pSets.toInt()) {
                    val grown: JsonObject = if (WorkoutModel.isSideSet(seed)) {
                        val arg = LinkedHashMap<String, JsonElement>()
                        (p.numN("weight") ?: seed.numN("w"))?.let { arg["w"] = jn(it) }
                        (p.numN("reps") ?: seed.numN("r"))?.let { arg["r"] = jn(it) }
                        WorkoutModel.makeSideSet(JsonObject(arg))
                    } else Js.with(plainSeed, "done", JsonPrimitive(false))
                    out = out + grown
                }
            }
        }
        // triple 的每组目标：按顺序落到每个开放工作行；已记录的行保留所记（progression.js:804-815）
        val rowReps = p.arr("rowReps")
        if (rowReps != null) {
            var k = -1
            val listOut = out.toMutableList()
            for (i in listOut.indices) {
                if (WorkoutModel.isWarmupRow(listOut[i])) continue
                k++
                val r = rowReps.getOrNull(k)?.numValue()
                if (jsTruthy(listOut[i].get("done")) || r == null || !(r > 0)) continue
                listOut[i] = if (WorkoutModel.isSideSet(listOut[i])) {
                    val sides = listOut[i].sub("sides")!!
                    fun sideRow(side: JsonObject?): JsonObject {
                        if (side?.bool("done") == true) return side
                        return Js.with(side ?: Js.emptyObj(), "r", jn(r / 2))
                    }
                    WorkoutModel.syncSideAggregate(
                        Js.with(listOut[i], "sides", Js.obj("L" to sideRow(sides.sub("L")), "R" to sideRow(sides.sub("R"))))
                    )
                } else {
                    Js.with(listOut[i], "r", jn(r))
                }
            }
            out = listOut
        }
        // 最后：工作行已带最终重量，热身块朝真正要举的重量 ramp（progression.js:818）
        return History.rerampWarmups(out, step, floor)
    }
}
