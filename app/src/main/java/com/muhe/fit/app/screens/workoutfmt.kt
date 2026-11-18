package com.muhe.fit.app.screens

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Workout 屏显示层小格式（elapsed/num）；批 3 并入 Effort.kt 等 lib 展示面时收口。 */
internal object workoutfmt {

    /** 会话已进行时长 mm:ss（S.active.start → now；whdr Elapsed.jsx 语义）。 */
    fun elapsed(active: JsonObject): String {
        val start = (active["start"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: return "0:00"
        val sec = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0)
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    /** 数值显示：整数去小数点，小数留一位（fmtNum.js 一位小数口径的本地版）。 */
    fun num(v: Double): String {
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        return "%.1f".format(v)
    }
}
