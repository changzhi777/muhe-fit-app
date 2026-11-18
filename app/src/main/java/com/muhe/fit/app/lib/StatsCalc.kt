package com.muhe.fit.app.lib

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 统计域聚合纯函数（统计 Tab；裁定 #5 修订：统计回归独立 Tab，5 Tab 架构）。
 *
 * 本文件无单一 JS 移植源——语义拼装自前端各处，逐函数注来源行号：
 * - epley 1RM = ck/frontend/src/lib/onerm.js estimate1RM:108-134 的 epley 单式路径
 *   （全量移植在 test source set 的 lib/Onerm.kt，main 不可依赖 test，故此处落同式最小子集；
 *   数值由 StatsCalcTest 对拍 golden onerm.json 既有用例闭环）
 * - 组数计数 = doneUnits 单侧计 ×2（history.js:787-797）+ workoutVolume 的热身排除纪律
 *   （history.js:738-743，「left out of volume, records and progression」）
 * - 热图着色 = components/Heatmap.jsx:43-46 四分位阈值语义（q(.25/.5/.75) → level 1-4），
 *   窗口改为近 12 周（裁定批注），阈值在窗口内重算而非全史
 * - streak 口径与 viewmodel/ProfileViewModel.kt 同（今天未练不断链）
 *
 * 周肌肉量按肌群细分需要动作→肌群映射（EXIDX），Room 八表未含目录数据——首版只做
 * 总组数趋势，细分随 M2+ catalogue 接入（android-app.md 深度图表下钻路线）。
 * Room 只读纪律：聚合全部内存算，不建新 Dao 方法。
 */
object StatsCalc {

    // ── 1RM 估算（onerm.js epley 单式路径） ────────────────────────────────

    // onerm.js:20 REP_CAP：超过上限公式反映工作能力而非最大力量，宁可不猜
    private const val REP_CAP = 12

    /** JS Math.round = floor(x + 0.5)（半值向 +∞）；非 kotlin.math.round 的 ties-to-even */
    private fun jsRound(x: Double): Double = floor(x + 0.5)

    private fun round1(x: Double): Double = jsRound(x * 10) / 10

    /**
     * epley 单式估算（onerm.js:112-133）。无法诚实回答返回 null：
     * 重量缺失/非正、次数 < 1、非有限值、次数超上限；单次组是测量不是估算，原样取整返回。
     */
    fun epley1RM(w: Double, r: Double): Double? {
        if (!w.isFinite() || !r.isFinite()) return null
        if (w <= 0 || r < 1) return null
        if (r == 1.0) return round1(w)
        if (r > REP_CAP) return null
        val est = w * (1 + jsRound(r) / 30)
        return if (est.isFinite() && est > 0) round1(est) else null
    }

    // ── JSON 读数助手（raw 透传 JSON 的最小读取面；JS 动态对象语义收窄） ────

    private fun objArr(o: JsonObject?, key: String): List<JsonObject> =
        (o?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun num(o: JsonObject?, key: String): Double? =
        (o?.get(key) as? JsonPrimitive)?.doubleOrNull

    private fun str(o: JsonObject?, key: String): String? =
        (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(o: JsonObject?, key: String): Boolean =
        (o?.get(key) as? JsonPrimitive)?.booleanOrNull == true

    /** history.js:764-775 workoutDay 的主路径：ISO yyyy-MM-dd 直接可信，坏值放弃（不回退时间戳） */
    fun workoutDate(w: JsonObject): LocalDate? =
        str(w, "d")?.takeIf { it.length == 10 }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    // ── 组数聚合 ────────────────────────────────────────────────────────────

    /** history.js:15-22 phaseForSet 子集：显式 phase 赢过 legacy warmup 布尔 */
    private fun isWarmupRow(set: JsonObject): Boolean =
        str(set, "phase") == "warmup" || bool(set, "warmup")

    /** history.js:787-789 doneUnits：单侧行每边独立计 1，普通行 0/1 */
    private fun doneUnits(set: JsonObject): Int {
        val sides = set["sides"] as? JsonObject ?: return if (bool(set, "done")) 1 else 0
        return listOf("L", "R").count { bool(sides?.get(it) as? JsonObject, "done") }
    }

    /**
     * 单场会话完成组数：完成行计 doneUnits，热身行排除（对齐 workoutVolume 排除纪律）。
     * 与 JS setsDone（history.js:793-797）的差异只有热身排除一处，注记在此。
     */
    fun setsDone(workout: JsonObject): Int =
        objArr(workout, "entries").sumOf { entry ->
            objArr(entry, "sets").sumOf { set ->
                if (isWarmupRow(set)) 0 else doneUnits(set)
            }
        }

    /** 日期 → 当日完成组数（含 0 组会话日，供热图 level 1 弱点亮起，对齐 Heatmap.jsx agg） */
    fun dailySetTally(workouts: List<JsonObject>): Map<LocalDate, Int> {
        val tally = LinkedHashMap<LocalDate, Int>()
        for (w in workouts) {
            val day = workoutDate(w) ?: continue
            tally[day] = (tally[day] ?: 0) + setsDone(w)
        }
        return tally
    }

    // ── 热图（Heatmap.jsx 着色语义） ────────────────────────────────────────

    /** 格值四分位阈值（Heatmap.jsx:43-45）：升序第 floor(p·n) 位，越界取末位 */
    data class HeatThresholds(val t1: Int, val t2: Int, val t3: Int)

    internal fun heatThresholds(values: Collection<Int>): HeatThresholds? {
        val sorted = values.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return null
        fun q(p: Double): Int = sorted[floor(p * sorted.size).toInt().coerceAtMost(sorted.size - 1)]
        return HeatThresholds(q(0.25), q(0.5), q(0.75))
    }

    /** Heatmap.jsx:46 level()：无记录 0 / 有记录零值或低于 t1 → 1 / ≥t1 2 / ≥t2 3 / ≥t3 4 */
    fun heatLevel(value: Int, th: HeatThresholds): Int = when {
        value <= 0 -> 1
        value >= th.t3 -> 4
        value >= th.t2 -> 3
        value >= th.t1 -> 2
        else -> 1
    }

    /** 周一为一周起点（demoSeed.js monday() 同口径；JS weekStartOf 是档案设置，首版固定周一） */
    fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays(((date.dayOfWeek.value + 6) % 7).toLong())

    data class HeatCell(val date: LocalDate, val level: Int)

    /**
     * 近 N 周热图格子（列 = 周，行 = 周一..周日，列序旧→新）。未来日恒 0。
     * 阈值在窗口内的正格值上重算（JS 全史算，窗口化是本批适配，注记在案）。
     */
    fun heatGrid(tally: Map<LocalDate, Int>, today: LocalDate, weeks: Int = 12): List<List<HeatCell>> {
        val currentMonday = mondayOf(today)
        val start = currentMonday.minusDays((weeks - 1) * 7L)
        val inWindow = HashMap<LocalDate, Int>()
        for ((day, sets) in tally) {
            if (!day.isBefore(start) && !day.isAfter(today)) inWindow[day] = sets
        }
        val th = heatThresholds(inWindow.values) ?: return List(weeks) { wk ->
            List(7) { d -> HeatCell(start.plusDays(wk * 7L + d), 0) }
        }
        return List(weeks) { wk ->
            List(7) { d ->
                val day = start.plusDays(wk * 7L + d)
                val level = when {
                    day.isAfter(today) -> 0
                    else -> inWindow[day]?.let { heatLevel(it, th) } ?: 0
                }
                HeatCell(day, level)
            }
        }
    }

    // ── 周训练量 ────────────────────────────────────────────────────────────

    data class WeekVolume(val weekStart: LocalDate, val sets: Int, val isCurrent: Boolean)

    /** 近 N 周（含本周）每周完成组数，升序、末位 = 本周；周一为界 */
    fun weeklySetTotals(tally: Map<LocalDate, Int>, today: LocalDate, weeks: Int = 4): List<WeekVolume> {
        val currentMonday = mondayOf(today)
        val start = currentMonday.minusDays((weeks - 1) * 7L)
        return (0 until weeks).map { wk ->
            val weekStart = start.plusDays(wk * 7L)
            val sets = (0..6).sumOf { d -> tally[weekStart.plusDays(d.toLong())] ?: 0 }
            WeekVolume(weekStart, sets, wk == weeks - 1)
        }
    }

    // ── 汇总瓦片 ────────────────────────────────────────────────────────────

    /** 连续训练天数（与 ProfileViewModel.streakOf 同口径：今天未练不断链） */
    fun dayStreak(days: Set<LocalDate>, today: LocalDate): Int {
        if (days.isEmpty()) return 0
        var cursor = if (today in days) today else today.minusDays(1)
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** 本月训练天数（Stats.jsx monthW = 当月 workoutDay 计数） */
    fun monthWorkoutCount(days: Collection<LocalDate>, today: LocalDate): Int {
        val month = YearMonth.from(today)
        return days.count { YearMonth.from(it) == month }
    }

    data class BwPoint(val date: LocalDate, val weightKg: Double)

    /** 30 天体重变化（Stats.jsx:432-433 bwDelta30：窗内 >1 点才有差值，首末相减） */
    fun bwDelta30(points: List<BwPoint>, today: LocalDate): Double? {
        val inWindow = points.filter { it.date.isAfter(today.minusDays(30)) }
        if (inWindow.size < 2) return null
        return inWindow.last().weightKg - inWindow.first().weightKg
    }

    // ── 数字格式化（format.js fmtNum 的最小近似） ───────────────────────────

    /** 一位小数舍入后去尾零：116.7 → "116.7"，80.0 → "80" */
    fun fmtNum(v: Double): String {
        val r = round1(v)
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }

    /** 带正号的一舍入值：正数加 "+"，负数自带 "-"（Stats.jsx 瓦片口径） */
    fun fmtSigned(v: Double): String = (if (v > 0) "+" else "") + fmtNum(v)

    // ── 重点动作 Est.1RM ────────────────────────────────────────────────────

    data class LiftEstimate(
        val name: String,
        val est: Double,
        val weight: Double,
        val reps: Double,
        val date: LocalDate,
    )

    /** 会话内单条目的最优估算：取完成工作组中 epley 估算最大的一组（其 w×r 一并带回） */
    private fun bestEstimateOf(entry: JsonObject): Triple<Double, Double, Double>? {
        var best: Triple<Double, Double, Double>? = null
        for (set in objArr(entry, "sets")) {
            if (isWarmupRow(set)) continue
            val sides = set["sides"] as? JsonObject
            val done = bool(set, "done") || listOf("L", "R").any { bool(sides?.get(it) as? JsonObject, "done") }
            if (!done) continue
            val w = num(set, "w") ?: continue
            val r = num(set, "r") ?: continue
            val est = epley1RM(w, r) ?: continue
            if (best == null || est > best.first) best = Triple(est, w, r)
        }
        return best
    }

    /**
     * 每个动作最新会话的 Est.1RM 列表，按估算降序。
     * 动作名 = 条目 n 快照，缺则退目录 id（目录 i18n 名随 M2+ catalogue 接入补）；
     * 心肺/计时组无 reps，天然出局（onerm.js 设计哲学：宁可不猜，不印幻想）。
     */
    fun latestEstimates(workouts: List<JsonObject>): List<LiftEstimate> {
        val chrono = workouts.sortedBy { num(it, "start") ?: 0.0 }
        val latest = LinkedHashMap<String, LiftEstimate>()
        for (w in chrono) {
            val day = workoutDate(w) ?: continue
            for (entry in objArr(w, "entries")) {
                val id = str(entry, "id") ?: continue
                val (est, weight, reps) = bestEstimateOf(entry) ?: continue
                latest[id] = LiftEstimate(
                    // 更晚会话覆盖时保留已知名：新会话无 n 快照（如只记 sets 的导入数据）不该抹掉已知名
                    name = str(entry, "n") ?: latest[id]?.name ?: id,
                    est = est,
                    weight = weight,
                    reps = reps,
                    date = day,
                )
            }
        }
        return latest.values.sortedByDescending { it.est }
    }
}
