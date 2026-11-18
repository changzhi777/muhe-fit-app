package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/**
 * dumbbells.js 的移植子集：哑铃库存（issue #376）——progression.js 的 nextPrescription 与
 * history.js 的 rerampWarmups 实际调用的部分。来源函数逐一注明 JS 行号（dumbbells.js）。
 */
object Dumbbells {

    private const val EPS = 1e-6   // dumbbells.js:214

    // dumbbells.js:37 tidy
    private fun tidy(w: Double): Double = jsRound(w * 1000) / 1000

    // dumbbells.js:35 unitOf
    private fun unitOf(S: JsonObject?): String = if (S.str("unit") == "lb") "lb" else "kg"

    // dumbbells.js:181-182 cleanWeights（去重、滤非法、升序）
    fun cleanWeights(list: List<JsonElement>?): List<Double> =
        (list ?: emptyList()).mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            .filter { it.isFinite() && it > 0 && it < 10000 }
            .map { tidy(it) }
            .distinct()
            .sorted()

    // dumbbells.js:185 dumbbellsOf（当前单位下的库存，轻者在前）
    fun dumbbellsOf(S: JsonObject?): List<Double> =
        cleanWeights(S.sub("dumbbells")?.sub(unitOf(S))?.arr("weights"))

    // dumbbells.js:45-48 dbLoadOf（'as' | 'each' | 'total'，否则 null）
    private fun dbLoadOf(value: JsonElement?): String? {
        val m = if (value is JsonObject) value.str("mode") else (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (m == "each" || m == "total" || m == "as") m else null
    }

    // dumbbells.js:54-57 dbLoadFor（例程自带 → S.dbLoad 默认 → 'as'）
    private fun dbLoadFor(S: JsonObject?, cfg: JsonObject?): String {
        dbLoadOf(cfg?.get("dbLoad"))?.let { return it }
        val id = cfg.str("id") ?: return "as"
        return dbLoadOf(S.sub("dbLoad")?.get(id)) ?: "as"
    }

    // dumbbells.js:68 ONE_ARM（单臂/单手命名）
    private val ONE_ARM = Regex("\\b(one|single)[- ]?(arm|hand|handed)\\b", RegexOption.IGNORE_CASE)

    // dumbbells.js:71 isOneArm（单侧记录或名字带 one/single arm）
    private fun isOneArm(cfg: JsonObject?): Boolean =
        jsTruthy(cfg?.get("side")) || ONE_ARM.containsMatchIn(Ex.exName(cfg?.get("id")) ?: "")

    // dumbbells.js:73 bellsIn
    private fun bellsIn(cfg: JsonObject?): Int = if (isOneArm(cfg)) 1 else 2

    // dumbbells.js:204-212 ownedWeightsFor（本动作可用的铃重；库存不适用 → null）
    fun ownedWeightsFor(S: JsonObject?, cfg: JsonObject?): List<Double>? {
        val id = cfg?.get("id")
        val exEq = Ex.eqOf(id)
        if (exEq != "dumbbell") return null
        val bw = if (cfg.hasNotNull("bodyweight")) jsTruthy(cfg?.get("bodyweight")) else Ex.isBodyweightEq(id)
        if (bw) return null
        val ws = dumbbellsOf(S)
        if (ws.isEmpty()) return null
        return if (dbLoadFor(S, cfg) == "total" && bellsIn(cfg ?: Js.emptyObj()) == 2) ws.map { tidy(it * 2) } else ws
    }

    // dumbbells.js:216-220 ownedUp（第 n 个更高的铃；没有 → null）
    fun ownedUp(ws: List<Double>?, w: Double, n: Int = 1): Double? {
        val above = (ws ?: emptyList()).filter { it > w + EPS }
        if (above.isEmpty()) return null
        return above[minOf(n, above.size) - 1]
    }

    // dumbbells.js:222-225 ownedDown
    fun ownedDown(ws: List<Double>?, w: Double): Double? {
        val below = (ws ?: emptyList()).filter { it < w - EPS }
        return below.lastOrNull()
    }

    // dumbbells.js:227-231 ownedFloor（≤x 的最重铃；无 → 最轻；空 → 0）
    fun ownedFloor(ws: List<Double>?, x: Double): Double {
        val list = ws ?: emptyList()
        val under = list.filter { it <= x + EPS }
        return under.lastOrNull() ?: (list.firstOrNull() ?: 0.0)
    }

    // dumbbells.js:241-246 ownedDeload（最接近 w×factor 且更轻的铃；平手取重）
    fun ownedDeload(ws: List<Double>?, w: Double, factor: Double): Double {
        val below = (ws ?: emptyList()).filter { it < w - EPS }
        if (below.isEmpty()) return w
        val ideal = w * factor
        return below.reduce { best, x ->
            if (abs(x - ideal) < abs(best - ideal) - EPS ||
                (abs(x - ideal) <= abs(best - ideal) + EPS && x > best)
            ) x else best
        }
    }

    // dumbbells.js:252-258 ownedAround（Epley deload 的候选铃：理想两侧各一，不超 max）
    fun ownedAround(ideal: Double, ws: List<Double>?, max: Double, strict: Boolean): List<Double> {
        if (!(ideal > 0) || !(max > 0)) return emptyList()
        val ok = (ws ?: emptyList()).filter { x -> x > 0 && x <= max + 1e-9 && (!strict || x < max - 1e-9) }
        val low = ok.lastOrNull { it <= ideal + EPS }
        val high = ok.firstOrNull { it >= ideal - EPS }
        return listOfNotNull(low, high).distinct().sorted()
    }
}
