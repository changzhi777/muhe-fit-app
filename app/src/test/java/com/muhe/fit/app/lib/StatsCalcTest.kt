package com.muhe.fit.app.lib

// ── 统计域聚合纯函数对拍（统计 Tab 批次；机制见 docs/golden-testing.md） ──
// epley1RM 数值对拍 golden onerm.json 既有用例（estimate1RM epley 路径 4 例直取）；
// 组数聚合/热图阈值/周量/streak/bwDelta 为 Android 侧新增聚合，无 JS fixture 直接对拍——
// 语义逐函数注 JS 来源（StatsCalc.kt 文件头），期望值按该语义手工推演，注记在此。
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsCalcTest {

    // ── JSON 构造助手（raw 透传形状：workout{entries[{id,n,sets[...]}]}） ────

    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(linkedMapOf(*pairs))

    private fun arr(items: List<JsonElement>): JsonArray = JsonArray(items)

    private fun num(v: Double): JsonElement = JsonPrimitive(v)
    private fun str(v: String): JsonElement = JsonPrimitive(v)
    private fun bool(v: Boolean): JsonElement = JsonPrimitive(v)

    private fun set(done: Boolean? = null, w: Double? = null, r: Double? = null, phase: String? = null): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        if (done != null) map["done"] = bool(done)
        if (w != null) map["w"] = num(w)
        if (r != null) map["r"] = num(r)
        if (phase != null) map["phase"] = str(phase)
        return JsonObject(map)
    }

    private fun sideSet(lDone: Boolean, rDone: Boolean, w: Double, r: Double): JsonObject = obj(
        "w" to num(w),
        "r" to num(r),
        "sides" to obj(
            "L" to obj("done" to bool(lDone), "w" to num(w), "r" to num(r / 2)),
            "R" to obj("done" to bool(rDone), "w" to num(w), "r" to num(r / 2)),
        ),
    )

    private fun entry(id: String, sets: List<JsonObject>, name: String? = null): JsonObject {
        val map = linkedMapOf<String, JsonElement>("id" to str(id), "sets" to arr(sets))
        if (name != null) map["n"] = str(name)
        return JsonObject(map)
    }

    private fun workout(d: String, start: Double, entries: List<JsonObject>): JsonObject =
        obj("d" to str(d), "start" to num(start), "entries" to arr(entries))

    // ── epley1RM（golden onerm.json estimate1RM epley 路径直取 4 例 + 边界） ──

    @Test
    fun epley_matches_golden_onerm_fixture_values() {
        assertEquals(116.7, StatsCalc.epley1RM(100.0, 5.0)!!, 1e-9)
        assertEquals(80.0, StatsCalc.epley1RM(60.0, 10.0)!!, 1e-9)
        assertEquals(180.5, StatsCalc.epley1RM(142.5, 8.0)!!, 1e-9)
        assertEquals(140.0, StatsCalc.epley1RM(100.0, 12.0)!!, 1e-9)
    }

    @Test
    fun epley_single_rep_is_a_measurement_not_an_estimate() {
        assertEquals(100.0, StatsCalc.epley1RM(100.0, 1.0)!!, 1e-9)
        assertEquals(97.5, StatsCalc.epley1RM(97.5, 1.0)!!, 1e-9)
    }

    @Test
    fun epley_refuses_to_guess_outside_honest_range() {
        assertNull(StatsCalc.epley1RM(100.0, 13.0))   // REP_CAP=12（onerm.js:20）
        assertNull(StatsCalc.epley1RM(0.0, 5.0))
        assertNull(StatsCalc.epley1RM(-10.0, 5.0))
        assertNull(StatsCalc.epley1RM(100.0, 0.0))
    }

    // ── setsDone（doneUnits 单侧 ×2 + 热身排除） ─────────────────────────────

    @Test
    fun sets_done_counts_only_completed_work_sets() {
        val w = workout(
            "2026-11-16", 1.0,
            listOf(entry("0025", listOf(set(true, 60.0, 8.0), set(false, 60.0, 8.0), set(true, 20.0, 10.0, "warmup")))),
        )
        assertEquals(1, StatsCalc.setsDone(w))
    }

    @Test
    fun sets_done_counts_each_side_of_a_unilateral_row() {
        val w = workout(
            "2026-11-16", 1.0,
            listOf(
                entry("0241", listOf(sideSet(lDone = true, rDone = true, w = 14.0, r = 10.0))),
                entry("0251", listOf(sideSet(lDone = true, rDone = false, w = 0.0, r = 12.0))),
            ),
        )
        assertEquals(3, StatsCalc.setsDone(w))
    }

    @Test
    fun sets_done_of_empty_workout_is_zero() {
        assertEquals(0, StatsCalc.setsDone(obj("d" to str("2026-11-16"))))
    }

    // ── dailySetTally ────────────────────────────────────────────────────────

    @Test
    fun tally_sums_sets_per_day_and_skips_bad_dates() {
        val tally = StatsCalc.dailySetTally(
            listOf(
                workout("2026-11-16", 1.0, listOf(entry("a", listOf(set(true), set(true))))),
                workout("2026-11-16", 2.0, listOf(entry("b", listOf(set(true))))),
                workout("oops", 3.0, listOf(entry("c", listOf(set(true), set(true), set(true))))),
            ),
        )
        assertEquals(mapOf(LocalDate.of(2026, 11, 16) to 3), tally)
    }

    // ── 热图着色（Heatmap.jsx 四分位语义） ───────────────────────────────────

    @Test
    fun heat_levels_follow_quartile_thresholds() {
        // [2,6,12] → t1=2 t2=6 t3=12（q(p)=sorted[floor(p*n)]）
        val th = StatsCalc.heatThresholds(listOf(2, 6, 12))!!
        assertEquals(2, th.t1)
        assertEquals(6, th.t2)
        assertEquals(12, th.t3)
        assertEquals(1, StatsCalc.heatLevel(0, th))
        assertEquals(1, StatsCalc.heatLevel(1, th))
        assertEquals(2, StatsCalc.heatLevel(2, th))
        assertEquals(3, StatsCalc.heatLevel(6, th))
        assertEquals(4, StatsCalc.heatLevel(12, th))
    }

    @Test
    fun heat_grid_is_week_columns_monday_first_with_future_blank() {
        // 2026-11-17 周二 → 本周一 = 11-16；两周窗起点 = 11-09
        val today = LocalDate.of(2026, 11, 17)
        val tally = mapOf(
            LocalDate.of(2026, 11, 9) to 2,
            LocalDate.of(2026, 11, 10) to 6,
            LocalDate.of(2026, 11, 15) to 12,
        )
        val grid = StatsCalc.heatGrid(tally, today, weeks = 2)
        assertEquals(2, grid.size)
        assertEquals(7, grid[0].size)
        assertEquals(LocalDate.of(2026, 11, 9), grid[0][0].date)
        assertEquals(2, grid[0][0].level)          // ≥ t1=2
        assertEquals(3, grid[0][1].level)          // ≥ t2=6
        assertEquals(4, grid[0][6].level)          // 11-15 周日 ≥ t3=12
        assertEquals(LocalDate.of(2026, 11, 16), grid[1][0].date)
        assertEquals(0, grid[1][0].level)          // 今天周一未练
        assertEquals(0, grid[1][3].level)          // 未来日恒空
    }

    @Test
    fun heat_grid_blank_when_window_has_no_positive_day() {
        val grid = StatsCalc.heatGrid(emptyMap(), LocalDate.of(2026, 11, 17), weeks = 3)
        assertEquals(3, grid.size)
        assertTrue(grid.all { col -> col.all { it.level == 0 } })
    }

    // ── 周起点与周量 ─────────────────────────────────────────────────────────

    @Test
    fun monday_of_handles_week_boundaries() {
        assertEquals(LocalDate.of(2026, 11, 16), StatsCalc.mondayOf(LocalDate.of(2026, 11, 17)))  // 周二
        assertEquals(LocalDate.of(2026, 11, 9), StatsCalc.mondayOf(LocalDate.of(2026, 11, 15)))   // 周日
        assertEquals(LocalDate.of(2026, 11, 16), StatsCalc.mondayOf(LocalDate.of(2026, 11, 16)))  // 周一自身
    }

    @Test
    fun weekly_totals_sum_per_monday_week_ascending() {
        val today = LocalDate.of(2026, 11, 17)
        val monday = LocalDate.of(2026, 11, 16)
        val tally = mapOf(
            monday to 10,
            monday.plusDays(1) to 5,
            LocalDate.of(2026, 11, 9) to 7,   // 上周一，两周窗起点
        )
        val rows = StatsCalc.weeklySetTotals(tally, today, weeks = 2)
        assertEquals(2, rows.size)
        assertEquals(LocalDate.of(2026, 11, 9), rows[0].weekStart)
        assertEquals(7, rows[0].sets)
        assertFalse(rows[0].isCurrent)
        assertEquals(15, rows[1].sets)
        assertTrue(rows[1].isCurrent)
    }

    // ── 汇总瓦片 ─────────────────────────────────────────────────────────────

    @Test
    fun day_streak_does_not_break_while_today_is_pending() {
        val today = LocalDate.of(2026, 11, 17)
        assertEquals(2, StatsCalc.dayStreak(setOf(today, today.minusDays(1)), today))
        assertEquals(1, StatsCalc.dayStreak(setOf(today.minusDays(1)), today))   // 今天未练不断链
        assertEquals(0, StatsCalc.dayStreak(setOf(today.minusDays(2)), today))
        assertEquals(0, StatsCalc.dayStreak(emptySet(), today))
    }

    @Test
    fun month_count_only_counts_current_month() {
        val today = LocalDate.of(2026, 11, 17)
        val days = listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 16), LocalDate.of(2026, 10, 30))
        assertEquals(2, StatsCalc.monthWorkoutCount(days, today))
    }

    @Test
    fun bw_delta30_takes_last_minus_first_inside_window() {
        val today = LocalDate.of(2026, 11, 17)
        val pts = listOf(
            StatsCalc.BwPoint(LocalDate.of(2026, 10, 15), 83.0),   // 窗外（窗 = > T-30 = 10-18）
            StatsCalc.BwPoint(LocalDate.of(2026, 10, 25), 82.0),   // 窗内首点
            StatsCalc.BwPoint(LocalDate.of(2026, 11, 7), 81.2),
            StatsCalc.BwPoint(LocalDate.of(2026, 11, 14), 79.6),
        )
        assertEquals(-2.4, StatsCalc.bwDelta30(pts, today)!!, 1e-9)
        val one = listOf(StatsCalc.BwPoint(LocalDate.of(2026, 11, 16), 79.6))
        assertNull(StatsCalc.bwDelta30(one, today))
    }

    @Test
    fun fmt_num_trims_trailing_zero_and_signed_keeps_plus() {
        assertEquals("116.7", StatsCalc.fmtNum(116.7))
        assertEquals("80", StatsCalc.fmtNum(80.0))
        assertEquals("+2.9", StatsCalc.fmtSigned(2.85))
        assertEquals("-2.4", StatsCalc.fmtSigned(-2.4))
    }

    // ── 重点动作 Est.1RM ─────────────────────────────────────────────────────

    @Test
    fun latest_estimates_keep_newest_session_per_exercise_sorted_desc() {
        val w1 = workout(
            "2026-11-01", 1000.0,
            listOf(
                entry("0025", listOf(set(true, 100.0, 5.0)), name = "深蹲"),
                entry("0047", listOf(set(true, 60.0, 10.0))),
                entry("c1", listOf(set(done = true))),                 // 计时组无 reps → 出局
                entry("0100", listOf(set(false, 100.0, 5.0))),         // 未完成 → 出局
            ),
        )
        val w2 = workout(
            "2026-11-08", 2000.0,
            listOf(entry("0025", listOf(set(true, 105.0, 5.0)))),      // 更晚会话覆盖
        )
        val rows = StatsCalc.latestEstimates(listOf(w1, w2))
        assertEquals(2, rows.size)
        assertEquals("深蹲", rows[0].name)                               // n 快照优先
        assertEquals(122.5, rows[0].est, 1e-9)                          // 105 × (1 + 5/30)
        assertEquals(LocalDate.of(2026, 11, 8), rows[0].date)
        assertEquals("0047", rows[1].name)                              // 无 n 快照 → 退 id
        assertEquals(80.0, rows[1].est, 1e-9)
    }

    @Test
    fun estimates_empty_without_completed_reps_sets() {
        val w = workout("2026-11-01", 1.0, listOf(entry("c1", listOf(set(true, 0.0, 0.0)))))
        assertTrue(StatsCalc.latestEstimates(listOf(w)).isEmpty())
    }
}
