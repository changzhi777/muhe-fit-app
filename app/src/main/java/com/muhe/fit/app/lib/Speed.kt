package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonObject
import kotlin.math.floor

/**
 * speed.js 的移植（cardio 速度，km/h 或 mph）。
 * 来源函数逐一注明 JS 行号（speed.js，2026-10-20 版）。
 *
 * 速度恒以 km/h 存：计划里、记录里、共享计划文件里、Coach 读的里都是。只有显示与输入
 * 跟档案的速度单位走——换单位不改写存量，两台设备不同设置也不会争一个数。
 * 依赖：fmtNum（format.js:58-60，一位小数舍入 + 千分位分组；format.js 未单列移植——
 * 速度 < 1000 无分组差异，本文件按同口径私有一份，与 StatsCalc.fmtNum 同源注记）。
 */
object Speed {

    /** speed.js:12 KMH_PER_MPH——国际英里精确值：1609.344 m */
    const val KMH_PER_MPH = 1.609344

    /** speed.js:19 speedUnitOf——显示单位 'kmh'|'mph'：档案没选就跟体重单位（lb → mph） */
    fun speedUnitOf(S: JsonObject?): String =
        when (S.str("speedUnit")) {
            "mph" -> "mph"
            "kmh" -> "kmh"
            else -> if (S.str("unit") == "lb") "mph" else "kmh"
        }

    /** speed.js:22 speedLabel——数字带的短标："km/h" 或 "mph" */
    fun speedLabel(unit: String?): String = if (unit == "mph") "mph" else "km/h"

    /**
     * speed.js:31-34 toSpeed——km/h 到屏幕单位。km/h 是恒等（没选 mph 的档案所见即所存）；
     * mph 侧存量 km/h 留两位小数——两位小数输入的任何速度按输入读回，"6" 也不会存成 9.656064。
     * 非 mph 或无效值（null/非有限）原样透传（JS 还透传 ''，Double 宿主无空串态，注记在案）。
     */
    fun toSpeed(kmh: Double?, unit: String?): Double? {
        if (unit != "mph" || kmh == null || !kmh.isFinite()) return kmh
        return jsRound(kmh / KMH_PER_MPH * 100) / 100
    }

    /** speed.js:35-38 fromSpeed——屏幕单位回存储 km/h（同两位小数口径） */
    fun fromSpeed(value: Double?, unit: String?): Double? {
        if (unit != "mph" || value == null || !value.isFinite()) return value
        return jsRound(value * KMH_PER_MPH * 100) / 100
    }

    /** format.js:58-60 fmtNum 一位小数口径（速度域私有同源；千分位分组对 <1000 无差异） */
    private fun fmtNum1(n: Double): String {
        val r = jsRound(n * 10) / 10
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }

    /** speed.js:41 fmtSpeed——"8 km/h" 或 "5 mph"：存储的 km/h 按屏幕单位显示 */
    fun fmtSpeed(kmh: Double?, unit: String?): String {
        val v = if (kmh != null && kmh.isFinite() && kmh != 0.0) kmh else 0.0   // JS: Number(kmh) || 0
        return "${fmtNum1(toSpeed(v, unit) ?: 0.0)} ${speedLabel(unit)}"
    }
}
