package com.muhe.fit.app.lib

// ── 周连击 streakWeeks 对拍（P9 批 B；机制见 docs/golden-testing.md） ──
// JS 真源：ck/frontend/src/lib/history.js:882-894 streakWeeks；
// 测试真源：ck/frontend/src/lib/week-start.test.js（streakWeeks 直接断言仅 2 例：
// :94 Sun/Mon 跨界对 SUNDAY 口径 =1、:98 空列表 =0）——Android 周起始定死周一
// （Streak.kt 头注裁定），SUNDAY 口径不移植，:94 场景改按 MONDAY 口径推演期望值
// 并注明差异。其余用例按 JS 实现内蕴语义手工推演（StatsCalcTest 同款纪律）。
// 全部 today 显式固定（JS 用 new Date() 即时取；显式锚点比动态更可测且语义一致）。
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class StreakTest {

    // today = 2026-11-19（周四）→ 周一先本周 = 2026-11-16..11-22，mondayOf = 11-16
    private val today: LocalDate = LocalDate.parse("2026-11-19")

    /** streakWeeks 只读条目 d 字段（ISO 日期），其余字段与周归属无关 */
    private fun workout(d: String): JsonObject = JsonObject(linkedMapOf("d" to JsonPrimitive(d)))

    private fun mondayOf(date: LocalDate): LocalDate = StatsCalc.mondayOf(date)

    // JS week-start.test.js:98 — streakWeeks({ workouts: [], weekStart: SUNDAY }) === 0
    // （周起始与空列表正交：周一先口径同值）
    @Test
    fun emptyWorkoutsIsZero() {
        assertEquals(0, Streak.streakWeeks(emptyList(), today))
    }

    // 单周 1（JS 语义：i=0 本周命中即 1，上周空即 break）【手工推演】
    // 2026-11-18（周三）∈ 本周 → 1
    @Test
    fun singleCurrentWeekIsOne() {
        assertEquals(1, Streak.streakWeeks(listOf(workout("2026-11-18")), today))
    }

    // 连续两周 = 2（本周 11-18 + 上周 11-11）【手工推演】
    @Test
    fun consecutiveWeeksCountUp() {
        assertEquals(
            2,
            Streak.streakWeeks(listOf(workout("2026-11-18"), workout("2026-11-11")), today),
        )
    }

    // 断周归零：本周有、上周空、上上周有 → 只数到 1（JS `else if (i > 0) break`）
    // 11-04（周三）∈ 11-02 周【手工推演】
    @Test
    fun gapWeekResetsStreak() {
        assertEquals(
            1,
            Streak.streakWeeks(listOf(workout("2026-11-18"), workout("2026-11-04")), today),
        )
    }

    // 本周未练不断链（JS i=0 miss 不 break 特例；dayStreak「今天未练不断链」周粒度版）
    // 上周 11-11 + 上上周 11-04，本周空窗 → 2【手工推演】
    @Test
    fun currentWeekWithoutWorkoutsDoesNotBreakChain() {
        assertEquals(
            2,
            Streak.streakWeeks(listOf(workout("2026-11-11"), workout("2026-11-04")), today),
        )
    }

    // 只有上周 → 1（本周空窗跳过后从上周起数）【手工推演】
    @Test
    fun lastWeekOnlyIsOne() {
        assertEquals(1, Streak.streakWeeks(listOf(workout("2026-11-11")), today))
    }

    // 同周多会话只算一周（JS 周键 Set 归并）：本周一/三/五三场 → 1【手工推演】
    @Test
    fun weekKeyDeduplicatesSessions() {
        assertEquals(
            1,
            Streak.streakWeeks(
                listOf(workout("2026-11-16"), workout("2026-11-18"), workout("2026-11-20")),
                today,
            ),
        )
    }

    // JS week-start.test.js:85-95 场景（原断言 SUNDAY 口径 = 1）：周日 2026-11-15 与
    // 周一 2026-11-16 跨周一先边界——mondayOf(周日)=11-09、mondayOf(周一)=11-16，
    // 两个相邻周各一场，锚点 2026-11-17（周二）→ **MONDAY 口径 = 2**（Android 定死
    // 周一的差异值；JS 同一 fixture 在 SUNDAY 下两场同周故 = 1）
    @Test
    fun sunMonBoundaryPairSpansTwoMondayFirstWeeks() {
        assertEquals(
            2,
            Streak.streakWeeks(
                listOf(workout("2026-11-15"), workout("2026-11-16")),
                LocalDate.parse("2026-11-17"),
            ),
        )
    }

    // 周一先归并边界：2026-11-15（周日）落 11-09 周、2026-11-22（周日）落 11-16 周——
    // 两个相邻周日分属不同周一先周（JS weekDayOffset(0, MONDAY)=6 的直证）【手工推演】
    // 锚点本周四：i=0 命中 11-16 周（11-22 场）→ 1；i=1 命中 11-09 周（11-15 场）→ 2
    @Test
    fun sundayFirstBoundaryDaysLandInAdjacentMondayWeeks() {
        assertEquals(
            2,
            Streak.streakWeeks(
                listOf(workout("2026-11-15"), workout("2026-11-22")),
                today,
            ),
        )
    }

    // 520 周上限（JS 循环界 i < 520）：本周往回 520 周每周一场 → 520【边界推演】
    @Test
    fun capAt520Weeks() {
        val weeks = (0 until 520).map { k -> workout(mondayOf(today).minusDays(k * 7L).toString()) }
        assertEquals(520, Streak.streakWeeks(weeks, today))
    }

    // 超上限旧数据不无限回退：窗外一周（回退循环最远只到 mondayOf(today) - 519·7，
    // 再往前的周键永不命中）→ i=0 miss（不 break）、i=1 miss → break → 0【边界推演】
    @Test
    fun workoutsBeyondCapDoNotLoop() {
        val beyond = mondayOf(today).minusDays(521L * 7)
        assertEquals(0, Streak.streakWeeks(listOf(workout(beyond.toString())), today))
    }

    // 坏 d 行跳过（StatsCalc.workoutDate 长度校验 + 容错；JS 侧坏 d 产垃圾周键永不
    // 命中回退窗，语义等效）：坏行 + 本周正常行 → 1【手工推演】
    @Test
    fun malformedDateRowsAreSkipped() {
        assertEquals(
            1,
            Streak.streakWeeks(listOf(workout("garbage"), workout("2026-11-18")), today),
        )
    }
}
