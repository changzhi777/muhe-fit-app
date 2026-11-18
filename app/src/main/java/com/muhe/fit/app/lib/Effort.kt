package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * effort.js 的移植（努力度作为统计量：一把内部尺（RIR），两副显示尺（RIR/RPE））。
 * 来源函数逐一注明 JS 行号（effort.js，2026-10-20 版）。
 *
 * 一组要么带 rir 要么带 rpe，永不回写；图表聚合一律在 RIR 里做（有真零点：力竭组），
 * 显示时换回去。RPE 8 == RIR 2。
 * 依赖小函数随本文件移植（标注来源）：EFFORT/effortOf/scaleName 语义 ← history.js:101-130、
 * workoutAt ← history.js:35-41、weekStartOf/startOfWeek/weekKey ← format.js:101-136（均未单列移植）；
 * isWarmupRow/isFailureSet 复用 WorkoutModel，isAssisted 复用 Ex。
 * UI 耦合裁剪：EFFORT_BANDS/EFFORT_PRESETS 的 color 字段（CSS var）不移植——颜色映射由
 * Compose 侧按 band 序号接 Material 配色（effortColor → effortBand 返回序号，判定保留）；
 * feel 文案保留英文原句（JS 字面），UI 批次可换 stringResource。
 */
object Effort {

    /** effort.js:17 HARD_RIR——≤ 此值算够近力竭的驱动适应组 */
    const val HARD_RIR = 3

    /** effort.js:20 MIN_RATED——不足此数个评分组的均值是噪声，调用侧显示 "-" */
    const val MIN_RATED = 5

    /** effort.js:157 BUCKETS——0,1,2,3 与 "4+" 尾桶 */
    const val BUCKETS = 4

    /**
     * effort.js:27-28 rirOf——一组的努力（RIR），没评 → null。0 是评分不是"空"。
     * 力竭标记组（WorkoutModel.isFailureSet）按定义 RIR 0；手输的评分仍赢（更精确的那个）。
     * 自身字段优先：rir 与 rpe 并存的文件按 rir 先读。
     */
    fun rirOf(s: JsonObject?): Double? {
        if (s == null) return null
        s.num("rir")?.let { return it }
        s.num("rpe")?.let { return 10.0 - it }
        return if (WorkoutModel.isFailureSet(s)) 0.0 else null
    }

    /** effort.js:31-32 toScale——RIR → 所显示的尺（rirOf 的逆，对单个数；两位防浮点尘） */
    fun toScale(kind: String?, rir: Double?): Double? {
        if (rir == null) return null
        return jsRound((if (kind == "rpe") 10.0 - rir else rir) * 10) / 10
    }

    // ── history.js 小依赖（history.js 未单列移植，随本文件走） ──

    private val EFFORT_KINDS = setOf("rir", "rpe")            // history.js:101-104 EFFORT 两键

    /** history.js:127-130 effortOf——档案记的尺；'none' 必须赢过旧 showRir 旗标 */
    private fun effortOf(S: JsonObject?): String {
        val e = S.str("effort")
        if (e == "none" || (e != null && EFFORT_KINDS.contains(e))) return e
        return if (jsTruthy(S?.get("showRir"))) "rir" else "none"
    }

    /** history.js:101 EFFORT[kind].hd——聚类的标注名（"RIR"/"RPE"；未知按 RIR，JS 此处会 throw） */
    fun scaleName(kind: String?): String = if (kind == "rpe") "RPE" else "RIR"

    /** history.js:35-41 workoutAt——自带 start 用之；否则日期正午（noon 抗一切时区/夏令时漂移）。NaN = 无从定位 */
    private val ISO_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private fun workoutAt(w: JsonObject?): Double {
        val start = w.num("start")
        if (start != null && start.isFinite()) return start
        val d = w.str("d") ?: return Double.NaN
        if (!ISO_DAY.matches(d)) return Double.NaN
        val date = runCatching { LocalDate.parse(d) }.getOrNull() ?: return Double.NaN
        return date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble()
    }

    // ── 遍历与窗口 ──

    /** effort.js:50-54 eachDoneSet——档案里每个完成的组，旧→新（热身不算）；fn 得 组/训练/条目 */
    private fun eachDoneSet(S: JsonObject?, fn: (JsonObject, JsonObject, JsonObject) -> Unit) {
        for (w in S?.arr("workouts") ?: emptyList()) {
            val wo = w as? JsonObject ?: continue
            for (e in wo.arr("entries") ?: emptyList()) {
                val eo = e as? JsonObject ?: continue
                for (s in eo.arr("sets") ?: emptyList()) {
                    val so = s as? JsonObject ?: continue
                    if (jsTruthy(so.get("done")) && !WorkoutModel.isWarmupRow(so)) fn(so, wo, eo)
                }
            }
        }
    }

    /** effort.js:58-59 inWindow——days 天的窗口（自今回数）；0 = 全部（空史册同义） */
    private fun inWindow(w: JsonObject, days: Int): Boolean =
        days == 0 || workoutAt(w) > System.currentTimeMillis() - days * 86_400_000.0

    /** effort.js:65-69 normRir——解析器的返回归一（数字 = 已记录努力；对象 = 可能带 estimated/source 的估值） */
    private class NormRir(val rir: Double?, val estimated: Boolean)

    private fun normRir(r: JsonElement?): NormRir {
        if (r == null || r is JsonNull) return NormRir(null, false)
        val o = r as? JsonObject
            ?: return NormRir((r as? JsonPrimitive)?.doubleOrNull, false)
        return NormRir(o.num("rir"), o.str("source")?.let { it != "logged" } ?: jsTruthy(o.get("estimated")))
    }

    /** 默认解析器：只认已记录的努力（effort.js:83 resolve = rirOf 的宿主形） */
    private fun defaultResolve(s: JsonObject, _w: JsonObject, _e: JsonObject): JsonElement? =
        rirOf(s)?.let { JsonPrimitive(it) }

    /** effort.js:71-74 avgRir——评分组的均值，其余剔除；没评过 → null（不是 0） */
    fun avgRir(sets: List<JsonElement>?): Double? {
        val vs = (sets ?: emptyList()).mapNotNull { el -> (el as? JsonObject)?.let { rirOf(it) } }
        return if (vs.isNotEmpty()) vs.sum() / vs.size else null
    }

    /** effort.js:96-100 effortSummary 返回——覆盖面是诚实的一半：avg/hardPct 只在样本足时给数 */
    data class EffortSummary(
        val done: Int, val rated: Int, val est: Int, val covered: Int, val hard: Int,
        val avg: Double?, val hardPct: Double?,
    )

    /**
     * effort.js:83-101 effortSummary——窗口内的头牌数字：多难、多难的部分占多少、
     * 以及让其余可信的那半——多少训练被评过分。努力可选且默认关，部分覆盖是常态；
     * 没有分母的均值会替没评过分的组说话。
     */
    fun effortSummary(S: JsonObject?, days: Int, resolve: (JsonObject, JsonObject, JsonObject) -> JsonElement? = ::defaultResolve): EffortSummary {
        var done = 0
        var rated = 0
        var est = 0
        var sum = 0.0
        var hard = 0
        eachDoneSet(S) { s, w, e ->
            if (!inWindow(w, days)) return@eachDoneSet
            done++
            val r = normRir(resolve(s, w, e))
            if (r == null || r.rir == null) return@eachDoneSet
            if (r.estimated) est++ else rated++
            sum += r.rir
            if (r.rir <= HARD_RIR) hard++
        }
        val covered = rated + est
        return EffortSummary(
            done, rated, est, covered, hard,
            if (covered >= MIN_RATED) sum / covered else null,
            if (covered >= MIN_RATED) hard.toDouble() / covered else null,
        )
    }

    /** effort.js:104-108 hasEffort——档案里有没有任何评分组：决定努力 UI 存不存在 */
    fun hasEffort(S: JsonObject?): Boolean {
        var any = false
        eachDoneSet(S) { s, _, _ -> if (!any && rirOf(s) != null) any = true }
        return any
    }

    /**
     * effort.js:116-127 hasEstimableEffort——有没有组可被 RIR 估算（有载重、次数在估值域、非辅助）：
     * 决定估值回落 UI 存不存在——Jefit/Hevy 导入有重量没评分。热身不算：估它们只会把卡片
     * 填满打折的爬坡行。
     */
    fun hasEstimableEffort(S: JsonObject?): Boolean {
        var any = false
        eachDoneSet(S) { s, _, e ->
            if (any) return@eachDoneSet
            val wgt = s.num("w") ?: 0.0
            val reps = s.num("r") ?: 0.0
            if (!(wgt > 0) || !(reps >= 1) || reps > 12) return@eachDoneSet
            val assistedArg = e.str("id")?.let { Js.obj("id" to JsonPrimitive(it)) } ?: e
            if (Ex.isAssisted(assistedArg)) return@eachDoneSet
            any = true
        }
        return any
    }

    /** effort.js:148 effortWeeks 返回：{ t, rir, n, est, sets } */
    data class EffortWeek(val t: Double, val rir: Double, val n: Int, val est: Int, val sets: Int)

    private class WeekAccum(val t: Double) {
        var sum = 0.0
        var n = 0
        var est = 0
        var sets = 0
    }

    // format.js:101/110/116 weekStartOf/weekDayOffset——档案的首日（0=Sunday，缺省 Monday）
    private fun weekStartOf(S: JsonObject?): Int = if (S.numN("weekStart") == 0.0) 0 else 1

    private fun weekDayOffset(day: Int, ws: Int): Int = (day - ws + 7) % 7

    /** format.js:123-126 startOfWeek——`iso` 所在周首日的日期（Date 正午锚定抗 DST；此处 LocalDate 面） */
    private fun startOfWeekDate(iso: String, ws: Int): LocalDate? {
        val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return null
        val day = d.dayOfWeek.value % 7      // JS getDay() 口径：Sunday = 0
        return d.minusDays(weekDayOffset(day, ws).toLong())
    }

    /** format.js:136 weekKey——同周同键：周首日的 ISO 日期（只当映射键用，从不显示/存储） */
    private fun weekKey(iso: String, ws: Int): String? = startOfWeekDate(iso, ws)?.toString()

    private fun noonMs(d: LocalDate): Double =
        d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble()

    /**
     * effort.js:135-149 effortWeeks——每历周的均值 + 周组数并肩：量上努力也上是疲劳在堆，
     * 量上努力平才是练出来的适应。只有一记评分的周丢弃不画——一记不该成曲线的尖峰。
     */
    fun effortWeeks(S: JsonObject?, days: Int, resolve: (JsonObject, JsonObject, JsonObject) -> JsonElement? = ::defaultResolve): List<EffortWeek> {
        val ws = weekStartOf(S)
        val wk = LinkedHashMap<String, WeekAccum>()
        eachDoneSet(S) { s, w, entry ->
            if (!inWindow(w, days)) return@eachDoneSet
            val d = w.str("d") ?: return@eachDoneSet
            val k = weekKey(d, ws) ?: return@eachDoneSet
            var b = wk[k]
            if (b == null) {
                val start = startOfWeekDate(d, ws) ?: return@eachDoneSet
                b = WeekAccum(noonMs(start))
                wk[k] = b
            }
            b.sets++
            val r = normRir(resolve(s, w, entry))
            if (r != null && r.rir != null) {
                b.sum += r.rir
                b.n++
                if (r.estimated) b.est++
            }
        }
        return wk.values.filter { it.n >= 2 }.sortedBy { it.t }
            .map { EffortWeek(it.t, it.sum / it.n, it.n, it.est, it.sets) }
    }

    /** effort.js:168 effortHistogram 返回：{ rir, tail, n, pct } */
    data class EffortBin(val rir: Int, val tail: Boolean, val n: Int, val pct: Double)

    /**
     * effort.js:158-169 effortHistogram——评分组沿整步分布，顶端全收进尾桶。回答「练得离
     * 力竭太远还是一点不留」的那张图：均值两者都藏——一半 0 一半 4 均出个健康的 2。
     */
    fun effortHistogram(S: JsonObject?, days: Int, resolve: (JsonObject, JsonObject, JsonObject) -> JsonElement? = ::defaultResolve): List<EffortBin> {
        val bins = IntArray(BUCKETS + 1)
        var rated = 0
        eachDoneSet(S) { s, w, e ->
            if (!inWindow(w, days)) return@eachDoneSet
            val r = normRir(resolve(s, w, e))
            val rr = r?.rir ?: return@eachDoneSet
            rated++
            bins[min(BUCKETS, max(0, floor(rr).toInt()))]++
        }
        return (0..BUCKETS).map { i ->
            EffortBin(i, i == BUCKETS, bins[i], if (rated > 0) bins[i].toDouble() / rated else 0.0)
        }
    }

    /** effort.js:172 isHardSet——算「难」的组：肌图的 hard sets 模式背后的过滤器 */
    fun isHardSet(s: JsonObject?): Boolean {
        val r = rirOf(s) ?: return false
        return r <= HARD_RIR
    }

    // ── 色带（effort.js:174-212；颜色由 Compose 侧承接，lib 只留判定） ──

    /** effort.js:187-194 EFFORT_BANDS——RIR 内定义一次，RIR/RPE 两尺同用；color 不移植（文件头注记） */
    data class EffortBand(val rir: Double, val max: Double, val feel: String)

    val EFFORT_BANDS: List<EffortBand> = listOf(
        EffortBand(0.0, 0.25, "Nothing left, went to failure"),      // JS var(--purple)
        EffortBand(0.5, 0.75, "Maybe half a rep left"),              // JS var(--red)
        EffortBand(1.0, 1.5, "One more rep in the tank"),            // JS var(--orange)
        EffortBand(2.0, 2.5, "Two more reps"),                       // JS var(--yellow)
        EffortBand(3.0, 3.5, "Three more reps"),                     // JS var(--green)
        EffortBand(4.0, Double.POSITIVE_INFINITY, "Easy, warm-up territory"),   // JS var(--acc-2)
    )

    /** effort.js:198-200 EFFORT_PRESETS——拾取器预设，最难在前；tail = 收拢的顶桶（"4+"，值为下限 4） */
    data class EffortPreset(val rir: Double, val feel: String, val tail: Boolean)

    val EFFORT_PRESETS: List<EffortPreset> =
        EFFORT_BANDS.mapIndexed { i, b -> EffortPreset(b.rir, b.feel, i == EFFORT_BANDS.lastIndex) }

    /**
     * effort.js:208-212 effortColor 的判定半——JS 返回 CSS var 颜色串；此处返回落进的 band 序号
     * （null = 没评过，调用侧画中性控件而非绿色）。精确预设落自己的带；输入的中间值（RIR 1.5）
     * 落它坐的带，永不着色为空。band 序号 → 颜色由 Compose 侧 Material 配色映射。
     */
    fun effortBand(rir: Double?): Int? {
        if (rir == null) return null
        EFFORT_BANDS.forEachIndexed { i, b -> if (rir <= b.max) return i }
        return EFFORT_BANDS.lastIndex
    }
}
