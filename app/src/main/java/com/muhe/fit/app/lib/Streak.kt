package com.muhe.fit.app.lib

import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

/**
 * 周连击（P9 批 B；JS 真源：ck/frontend/src/lib/history.js:882-894 `streakWeeks(S)`）。
 *
 * 语义（逐行对齐 JS 实现）：
 * - 空训练列表 → 0（JS `if (!S.workouts.length) return 0`）
 * - 周键 = 该周第一天的日期（JS `weekKey(iso, ws)` = startOfWeek 的 ISO 日期，format.js:136）；
 *   训练日归并成周键集合（同周多会话只算一周）
 * - 从本周（i=0）起逐周回退 7 天，命中周键 streak++；**本周（i=0）没练不断链**——
 *   JS `else if (i > 0) break` 只在 i>0 时 break，本周空窗继续看上周，
 *   与 dayStreak「今天未练不断链」同一哲学（StatsCalc.kt:176 口径的周粒度版）
 * - 上限 520 周（JS 循环界 `i < 520`，约 10 年防护，防全史全绿时无限回退）
 *
 * **周起始裁定**：JS 周起始随档案设置 `S.weekStart` 可配（format.js:116 weekStartOf，
 * 只有 0 是周日先、其余默认周一）；Android 档案无该设置，本端**定死周一**
 * （中国习惯；统计页热图/周量已统一周一界，StatsCalc.mondayOf 同口径）。差异注记：
 * JS 测试（ck/frontend/src/lib/week-start.test.js:85-95）的 Sun/Mon 跨界对在 SUNDAY
 * 口径下是 1 周，在 MONDAY 口径下是 2 周——Android 走后者，StreakTest 逐例注明。
 */
object Streak {

    /**
     * 连续训练周数（history.js:882-894）。`workouts` = Room raw 透传的整条目 JsonObject
     * 列表（StatsViewModel.parseRaw 同款路径），周归属只读会话日期 `d`（ISO yyyy-MM-dd，
     * 走 StatsCalc.workoutDate 既有解析：长度校验 + 容错，坏行跳过——JS 侧坏 d 产出的
     * 垃圾周键永不命中回退窗口，跳过与等效）；`today` = 判定锚点（可测性显式传入，
     * JS 用 `new Date()` 即时取，语义一致）。
     */
    fun streakWeeks(workouts: List<JsonObject>, today: LocalDate): Int {
        if (workouts.isEmpty()) return 0
        // JS weeks = new Set(S.workouts.map(w => weekKey(w.d, ws)))；Android 周键直接用
        // LocalDate（值语义等价 JS 的 ISO 字符串集合键），周一先归并复用 StatsCalc.mondayOf
        // （= format.js startOfWeek 的 weekDayOffset(MONDAY) 回退，两处偏移算式一致）
        val weeks = HashSet<LocalDate>()
        for (w in workouts) {
            val day = StatsCalc.workoutDate(w) ?: continue
            weeks += StatsCalc.mondayOf(day)
        }
        var streak = 0
        var cursor = today
        for (i in 0 until 520) {
            if (StatsCalc.mondayOf(cursor) in weeks) streak++
            else if (i > 0) break
            cursor = cursor.minusDays(7)
        }
        return streak
    }
}
