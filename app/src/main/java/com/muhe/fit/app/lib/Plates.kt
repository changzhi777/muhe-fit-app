package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * plates.js 的移植（板载配重：这一组该上哪些片，按你拥有的库存算）。
 * 来源函数逐一注明 JS 行号（plates.js，2026-10-20 版）。
 *
 * 宿主模型：S（档案 JsonObject）/ cfg（动作配置 JsonObject）/ 片库存条目 = 纯数值闭包走
 * data class（PlatePair）；JS 对象键名（plates.{kg,lb} 的尺寸键）原样保留为字符串键。
 * 依赖小函数随本文件移植（标注来源）：usesBar/defaultBarWeight ← bar.js（bar.js 未单列移植）；
 * exOf 目录面走 Ex（fixture catalog 注入）；isBw/weightIncrement/ownedWeightsFor/ownedFloor
 * 复用既有 History/Progression/Dumbbells。
 *
 * 与 WorkoutModel 的协同：dropGrid 返回 sealed DropGrid（JS 是「函数 | 数字」union——
 * workout-model.js nextDropWeight 以 typeof grid === 'function' 分流）；
 * WorkoutModel.nextDropWeight 已拆 snap/grid 双重载，调用侧按分支接：
 *   is Snap → WorkoutModel.nextDropWeight(prev, pct, snap = g.snap)
 *   is Step → WorkoutModel.nextDropWeight(prev, pct, grid = g.step)
 */
object Plates {

    /** plates.js:19-22 PLATE_SIZES——编辑器列出的全部片尺寸，重的在前，按单位 */
    val PLATE_SIZES: Map<String, List<Double>> = mapOf(
        "kg" to listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5),
        "lb" to listOf(45.0, 35.0, 25.0, 15.0, 10.0, 5.0, 2.5, 1.25),
    )

    /** plates.js:24 UNCOMMON——无自家库存的馆默认「没有」的尺寸 */
    private val UNCOMMON: Map<String, Set<Double>> = mapOf(
        "kg" to setOf(0.5),
        "lb" to setOf(15.0, 1.25),
    )

    /** plates.js:26 DEFAULT_PAIRS——默认库存每尺寸的对数 */
    const val DEFAULT_PAIRS = 6

    /** plates.js:29 SINGLE_EQ——单桩加载（吊重带/地雷/片装器械/雪橇） */
    private val SINGLE_EQ = setOf("weighted", "sled machine")

    // ── bar.js 小依赖（bar.js:17/20/22/27/30-31；bar.js 未单列移植，随本文件走） ──

    private val BAR_EQ = setOf("barbell", "olympic barbell", "ez barbell", "smith machine", "trap bar")
    private val DEFAULT_BAR_KG = mapOf(
        "barbell" to 20.0, "olympic barbell" to 20.0, "ez barbell" to 10.0,
        "smith machine" to 9.0, "trap bar" to 25.0,
    )
    private val DEFAULT_BAR_LB = mapOf(
        "barbell" to 45.0, "olympic barbell" to 45.0, "ez barbell" to 25.0,
        "smith machine" to 20.0, "trap bar" to 55.0,
    )

    private fun usesBar(exOrId: JsonElement?): Boolean = Ex.eqOf(exOrId)?.let { BAR_EQ.contains(it) } == true

    /** bar.js:30-31 defaultBarWeight——器材默认杆重，null = 列表外 */
    private fun defaultBarWeight(eq: String?, unit: String?): Double? {
        if (eq == null) return null
        return (if (unit == "lb") DEFAULT_BAR_LB else DEFAULT_BAR_KG)[eq]
    }

    // ── 局部读值助手（plates.js:31-33 num/unitOf；对位 JS `Number(v) || 0` 与宽松数字面） ──

    /** plates.js:32 num——数字面用值；字符串面 JS Number(v)||0 语义（"2.5"→2.5，"x"/对象→0） */
    private fun numLoose(v: JsonElement?): Double = when (v) {
        null, is JsonNull -> 0.0                     // JS Number(null) === 0
        is JsonPrimitive ->
            if (v.isString) v.content.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
            else v.doubleOrNull?.takeIf { it.isFinite() } ?: 0.0
        else -> 0.0
    }

    /** 尺寸键读数（plates.js:44 num(k)——JS 对象键恒为字符串） */
    private fun numKey(k: String): Double = k.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

    /** plates.js:33 unitOf */
    private fun unitOf(S: JsonObject?): String = if (S.str("unit") == "lb") "lb" else "kg"

    /** 尺寸 Double → JS 对象键字符串（45 → "45"，2.5 → "2.5"；Kotlin Double.toString 会出 "45.0"） */
    private fun sizeKey(w: Double): String = if (w == floor(w)) w.toLong().toString() else w.toString()

    /** plates.js:43-45 sizesOf——库存表里「是片」的键值对（_ts 与杂物键剔出；原始值类型保留） */
    private fun sizesOf(entry: JsonObject?): List<Pair<String, JsonElement>> =
        entry?.filterKeys { numKey(it) > 0 }?.entries?.map { it.key to it.value } ?: emptyList()

    /** plates.js:48 ownsPlates——该档案该单位是否自点片数 */
    fun ownsPlates(S: JsonObject?): Boolean = sizesOf(S.sub("plates").sub(unitOf(S))).isNotEmpty()

    /** 库存条目（plates.js 的 { w, n }——n 为对数） */
    data class PlatePair(val w: Double, val n: Int)

    /** plates.js:55-68 inventoryFor——可上的片，重的在前；无自家库存 → 单位的默认套装 */
    fun inventoryFor(S: JsonObject?): List<PlatePair> {
        val unit = unitOf(S)
        val own = sizesOf(S.sub("plates").sub(unit))
        val out = ArrayList<PlatePair>()
        if (own.isNotEmpty()) {
            for ((k, v) in own) {
                val w = numKey(k)
                val n = floor(numLoose(v)).toInt()
                if (w > 0 && n > 0) out.add(PlatePair(w, n))
            }
        } else {
            for (w in PLATE_SIZES[unit] ?: emptyList())
                if (!UNCOMMON[unit].orEmpty().contains(w)) out.add(PlatePair(w, DEFAULT_PAIRS))
        }
        return out.sortedByDescending { it.w }
    }

    /**
     * plates.js:74-81 withPlatePairs——S.plates 该单位补 `n` 对 `w` 片并盖章；首次改动把
     * 标准套装拷进来（编辑器所见即行上所载的表）。返回整个 S.plates 对象（调用侧回写）。
     */
    fun withPlatePairs(S: JsonObject?, w: Double, n: Double, now: Long = System.currentTimeMillis()): JsonObject {
        val unit = unitOf(S)
        val list = LinkedHashMap<String, JsonElement>()
        if (ownsPlates(S)) {
            for ((k, v) in sizesOf(S.sub("plates").sub(unit))) list[k] = v
        } else {
            for (p in inventoryFor(S)) list[sizeKey(p.w)] = jn(p.n.toDouble())
        }
        list[sizeKey(w)] = jn(max(0.0, jsRound(n)))
        val inner = LinkedHashMap(list)
        inner["_ts"] = JsonPrimitive(now)
        return Js.spread(S.sub("plates"), Js.obj(unit to JsonObject(inner)))
    }

    /** plates.js:84 withStandardPlates——该单位回标准套装：无尺寸的盖章表 */
    fun withStandardPlates(S: JsonObject?, now: Long = System.currentTimeMillis()): JsonObject =
        Js.spread(S.sub("plates"), Js.obj(unitOf(S) to Js.obj("_ts" to JsonPrimitive(now))))

    /**
     * plates.js:91-94 loadKindOf——动作自带加载方式 'pairs'|'single'|'none'，null = 器材说了算；
     * 早期构建存的是裸字符串，同读；盖过章的 { kind } 也读。
     */
    fun loadKindOf(value: JsonElement?): String? {
        val k: JsonElement? = if (value is JsonObject) value["kind"] else if (value is JsonNull) null else value
        val s = (k as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return if (s == "pairs" || s == "single" || s == "none") s else null
    }

    /** plates.js:97-98 withLoadKind——S.loadKind 记该动作的加载方式；null = 回器材自带（kind 写 JSON null 参与同步） */
    fun withLoadKind(map: JsonObject?, exId: String, kind: JsonElement?, now: Long = System.currentTimeMillis()): JsonObject {
        val stored = loadKindOf(kind)
        val entry = Js.obj(
            "kind" to (stored?.let { JsonPrimitive(it) } ?: JsonNull),
            "_ts" to JsonPrimitive(now),
        )
        return Js.with(map ?: Js.emptyObj(), exId, entry)
    }

    /** plates.js:101 pairsOf——档案库存里某尺寸的对数（没有 → 0） */
    fun pairsOf(S: JsonObject?, w: Double): Int =
        inventoryFor(S).firstOrNull { it.w == w }?.n ?: 0

    // ── 一切以四分之一单位做，1.25 与 2.5 才是精确整数（plates.js:103-105） ──

    private const val Q = 4
    private fun q(w: Double): Int = jsRound(w * Q).toInt()

    /** plates.js:112 plateStack 返回：片（重的在前）+ 缺口（0 = 正好） */
    data class PlateStack(val plates: List<Double>, val missing: Double)

    /**
     * plates.js:114-165 plateStack——`weight`（每侧或单桩总量）从 `inv` 里凑片：贪心（重的在前）
     * 正好即用；贪心差一点（自家一对手力的怪库存）→ 凑整所需的「最少片数」，平手偏向重片；
     * 凑不齐 → 低于目标的最接近载荷与缺口。全有的总量兜底短路：9999 手滑不再全库搜索。
     */
    fun plateStack(weight: Double?, inv: List<PlatePair>?): PlateStack {
        val requested = max(0.0, weight?.takeIf { it.isFinite() } ?: 0.0)
        val target = floor(requested * Q).toInt()
        if (target == 0) return PlateStack(emptyList(), requested)
        val items = (inv ?: emptyList()).filter { it.w > 0 && it.n > 0 }.sortedByDescending { it.w }
        // 1. 贪心
        val greedy = ArrayList<Double>()
        var left = target
        for (p in items) {
            val unit = q(p.w)
            var k = min(p.n, left / unit)
            while (k > 0) { greedy.add(p.w); left -= unit; k-- }
        }
        // 全上或超有：贪心已把每片上完，其余即缺口（JS 注：下面的搜索只会再找到一遍）
        val all = items.sumOf { q(it.w) * it.n }
        if (left == 0 || target >= all) return PlateStack(greedy, requested - (target - left).toDouble() / Q)
        // 2. 最少片数的精确凑法：f[i][s] = 用尺寸 i.. 凑 s 的最少片数
        val n = items.size
        val INF = 1_000_000_000
        val f = Array(n + 1) { IntArray(target + 1) { INF } }
        f[n][0] = 0
        for (i in n - 1 downTo 0) {
            val unit = q(items[i].w)
            val cap = items[i].n
            for (s in 0..target) {
                var best = f[i + 1][s]
                var k = 1
                while (k <= cap && k * unit <= s) {
                    val v = f[i + 1][s - k * unit]
                    if (v + k < best) best = v + k
                    k++
                }
                f[i][s] = best
            }
        }
        // ≤ 目标的最重可达和（凑得齐时即目标本身）
        var sum = target
        while (sum > 0 && f[0][sum] >= INF) sum--
        val plates = ArrayList<Double>()
        var s = sum
        for (i in 0 until n) {
            val unit = q(items[i].w)
            val cap = items[i].n
            // 仍留最优余量的最大 k → 平手偏向重片
            var k = min(cap, s / unit)
            while (k >= 0) {
                if (f[i + 1][s - k * unit] + k == f[i][s]) {
                    repeat(k) { plates.add(items[i].w) }
                    s -= k * unit
                    break
                }
                k--
            }
        }
        return PlateStack(plates, requested - sum.toDouble() / Q)
    }

    /** plates.js:169 plateDelta 返回：卸下/装上（多重集差，各自重的在前） */
    data class PlateDelta(val strip: List<Double>, val add: List<Double>)

    /** plates.js:171-179 plateDelta——两桩之间：卸什么上什么 */
    fun plateDelta(prev: List<Double>?, next: List<Double>?): PlateDelta {
        fun count(arr: List<Double>?): HashMap<Double, Int> {
            val m = HashMap<Double, Int>()
            for (w in arr ?: emptyList()) m[w] = (m[w] ?: 0) + 1
            return m
        }
        val a = count(prev)
        val b = count(next)
        val strip = ArrayList<Double>()
        val add = ArrayList<Double>()
        for ((w, n) in a) repeat(max(0, n - (b[w] ?: 0))) { strip.add(w) }
        for ((w, n) in b) repeat(max(0, n - (a[w] ?: 0))) { add.add(w) }
        strip.sortDescending()
        add.sortDescending()
        return PlateDelta(strip, add)
    }

    /**
     * plates.js:190-199 loadKindFor——动作怎么载：'pairs'（杆/双柱机，超出杆部分每侧摊）、
     * 'single'（单桩全挂）、'none'（哑铃/壶铃/绳索/销片机/弹力带）。用户自选（loadKindOf）胜出；
     * 否则杆器材 → pairs，自重与 weighted 及雪橇 → single，其余 → none。
     * 弹力带的"重量"是张力不是片 → band 器材恒 none（isBw 虽算它）。
     */
    fun loadKindFor(S: JsonObject?, cfgOrId: JsonElement?): String {
        val cfg: JsonObject = if (cfgOrId is JsonObject) cfgOrId else Js.obj("id" to cfgOrId)
        val exId = cfg.str("id")
        val own = if (exId == null) null else S?.sub("loadKind")?.get(exId)?.let { loadKindOf(it) }
        if (own != null) return own
        if (usesBar(cfg["id"])) return "pairs"
        val eq = Ex.eqOf(cfg["id"])
        if (eq == "band" || eq == "resistance band") return "none"
        if ((eq != null && SINGLE_EQ.contains(eq)) || History.isBw(cfg)) return "single"
        return "none"
    }

    /**
     * plates.js:206-212 baseWeightFor——上片前就在的重量（档案单位）：杆动作是杆
     * （S.barWeights 覆盖，0 = "没杆"，否则器材默认），其余是显式覆盖（雪橇自重），否则 0。
     */
    fun baseWeightFor(S: JsonObject?, exOrId: JsonElement?): Double {
        val exId: JsonElement? = if (exOrId is JsonObject) exOrId["id"] else exOrId
        // JS: exOf(exOrId) 未命中（目录查无此 id）→ 0；传入对象则恒有 ex
        val hasEx = when (exOrId) {
            is JsonObject -> true
            null, is JsonNull -> false
            else -> Ex.eqOf(exOrId) != null
        }
        if (!hasEx) return 0.0
        val own = (exId as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            ?.let { S?.sub("barWeights")?.get(it) }
        val ownNum = (own as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
        if (ownNum != null && ownNum >= 0) return ownNum
        return defaultBarWeight(Ex.eqOf(exOrId), S.str("unit")) ?: 0.0
    }

    /** plates.js:218 rowLoad 返回 */
    data class RowLoad(val kind: String, val barOnly: Boolean, val perSide: Double, val plates: List<Double>, val missing: Double)

    /**
     * plates.js:220-232 rowLoad——一组行的载重线：kind none 或无重量 → null；
     * ≤ 基座 → barOnly；否则每侧（pairs）或总量（single）的片堆 + 缺口。
     * 库存计对数：杆每侧一片；单桩一对可上两片（一对 20 在雪橇上是两个 20）。
     */
    fun rowLoad(kind: String?, weight: Double?, base: Double?, inv: List<PlatePair>?): RowLoad? {
        if (kind != "pairs" && kind != "single") return null
        val w = weight?.takeIf { it.isFinite() } ?: 0.0
        if (!(w > 0)) return null
        val b = max(0.0, base?.takeIf { it.isFinite() } ?: 0.0)
        if (w <= b) return RowLoad(kind, true, 0.0, emptyList(), 0.0)
        val perSide = jsRound((w - b) / (if (kind == "pairs") 2.0 else 1.0) * 100) / 100
        val stock = if (kind == "single") (inv ?: emptyList()).map { it.copy(n = it.n * 2) } else inv
        val stack = plateStack(perSide, stock)
        return RowLoad(kind, false, perSide, stack.plates, stack.missing)
    }

    /** plates.js:235-237 sameLoad——两行载同一堆：堆同、barOnly 同、缺口同 */
    fun sameLoad(a: RowLoad?, b: RowLoad?): Boolean =
        a != null && b != null && a.barOnly == b.barOnly && a.missing == b.missing
            && a.plates.size == b.plates.size && a.plates.indices.all { a.plates[it] == b.plates[it] }

    /** plates.js:246-263 dropGrid 的联合返回——JS 里是「函数 | 数字」，workout-model.js 按 typeof 分流 */
    sealed interface DropGrid {
        /** snap 函数版：喂建议重量，回可实际载上的重量 */
        data class Snap(val snap: (Double) -> Double) : DropGrid
        /** 步长版：该动作该单位的重量步进 */
        data class Step(val step: Double) : DropGrid
    }

    /**
     * plates.js:246-263 dropGrid——递减组的下一档落点：哑铃动作落在拥有的铃上（issue #376）；
     * 自点片数的片载动作落在片能凑出的 ≤ 递减量的最重载荷（QA 1.3.9：2.5 步的杆不吃 48.5）；
     * 否则落在动作自己的重量步长。结果接 WorkoutModel.nextDropWeight 的 snap/grid 双重载。
     */
    fun dropGrid(S: JsonObject?, cfg: JsonObject?): DropGrid {
        val owned = Dumbbells.ownedWeightsFor(S, cfg)
        if (owned != null) return DropGrid.Snap { w -> Dumbbells.ownedFloor(owned, w) }
        val kind = loadKindFor(S, cfg)
        if (ownsPlates(S) && (kind == "pairs" || kind == "single")) {
            val base = baseWeightFor(S, cfg?.get("id"))
            val inv = inventoryFor(S)
            val per = if (kind == "pairs") 2.0 else 1.0
            return DropGrid.Snap { w ->
                if (w <= base) (if (base > 0) base else w)
                else {
                    val rl = rowLoad(kind, w, base, inv)!!
                    base + per * max(0.0, rl.perSide - rl.missing)
                }
            }
        }
        return DropGrid.Step(Progression.weightIncrement(cfg, if (S.str("unit") == "lb") "lb" else "kg"))
    }
}
