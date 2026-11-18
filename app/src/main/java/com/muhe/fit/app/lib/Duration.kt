package com.muhe.fit.app.lib

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * duration.js 的移植（整秒时长：休息计时器存的 S.restSec / S.restPauseSec / target.restSec）。
 * 来源函数逐一注明 JS 行号（duration.js，2026-10-20 版）。
 *
 * 注记：本 object 名 Duration 与 kotlin.time.Duration 同名——本包内文件勿 import kotlin.time，
 * UI 侧如需两者请用别名 import（kotlin.time.Duration as KtDuration）。
 * i18n 口径：fmtDuration 的 "s" 与 "m:ss" 数字面为 JS 硬编码（非 i18n，照搬）；
 * durationText 的复数文案 JS 走 tn()——Android 由 UI 层 plural resources 承接，此处保留
 * 数值判定骨架，英文口径为占位实现。
 */
object Duration {

    /** duration.js:8 REST_MAX——休息计时器量程：0 = 不休息，15 分钟封顶 */
    const val REST_MAX = 15 * 60

    /** duration.js:10 REST_PAUSE_MIN——rest-pause burst 的休息永不为 0（history.js 钳到 5s） */
    const val REST_PAUSE_MIN = 5

    /** duration.js:11 */
    const val REST_PAUSE_MAX = 5 * 60

    /** duration.js:14-18 clampDuration——[min, max] 内的整秒；非数读作 min
     *  （参名 lo/hi 对位 JS min/max——Kotlin 参数会遮蔽 kotlin.math.min/max，故改名） */
    fun clampDuration(sec: Double?, lo: Double = 0.0, hi: Double = REST_MAX.toDouble()): Double {
        val n = jsRound(sec?.takeIf { it.isFinite() } ?: Double.NaN)
        if (!n.isFinite()) return lo
        return min(hi, max(lo, n))
    }

    /** duration.js:21 splitDuration 返回 { m, s }：90 → m=1, s=30 */
    data class Split(val m: Int, val s: Int)

    /** duration.js:21-24 splitDuration */
    fun splitDuration(sec: Double?): Split {
        // JS: Math.max(0, Math.round(Number(sec)) || 0)——Number(null)=0 同落 0
        val n = max(0.0, jsRound(sec?.takeIf { it.isFinite() } ?: 0.0))
        return Split(floor(n / 60).toInt(), (n % 60).toInt())
    }

    /** duration.js:27-29 joinDuration——分秒合回秒，钳到轮盘量程（lo/hi 同 clampDuration 口径） */
    fun joinDuration(m: Double?, s: Double?, lo: Double = 0.0, hi: Double = REST_MAX.toDouble()): Double =
        clampDuration(
            (m?.takeIf { it.isFinite() && it != 0.0 } ?: 0.0) * 60 +
                (s?.takeIf { it.isFinite() && it != 0.0 } ?: 0.0),
            lo, hi,
        )

    /**
     * duration.js:35-41 fmtDuration——行的短形：一分内 "45s"，一 分起 "1:30"，
     * 调用侧说 0 = off 时 0 显示 off（"Off"）。数字恒西文，与全 App 数字一致。
     */
    fun fmtDuration(sec: Double?, off: String? = null): String {
        val n = max(0.0, jsRound(sec?.takeIf { it.isFinite() } ?: 0.0))
        if (n == 0.0 && off != null) return off
        if (n < 60) return "${n.toInt()}s"
        val sp = splitDuration(n)
        return "${sp.m}:" + sp.s.toString().padStart(2, '0')
    }

    /**
     * duration.js:47-52 durationText——读屏长形："1 minute 30 seconds" / "45 seconds" / "2 minutes"。
     * 0 在给了 off 时显示 off，否则 "0 seconds"。文案复数（tn）由 UI 层承接（见文件头注记）。
     */
    fun durationText(sec: Double?, off: String? = null): String {
        val sp = splitDuration(sec)
        val m = sp.m
        val s = sp.s
        if (m == 0 && s == 0) return off ?: "0 seconds"
        val parts = ArrayList<String>(2)
        if (m > 0) parts.add(if (m == 1) "1 minute" else "$m minutes")
        if (s > 0) parts.add(if (s == 1) "1 second" else "$s seconds")
        return parts.joinToString(" ")
    }

    /** duration.js:55 fmtRest——休息计时器自己的行文案："1:30" 或 "Off"（off 文案调用侧传 t('Off')） */
    fun fmtRest(sec: Double?, off: String): String = fmtDuration(sec, off)
}
