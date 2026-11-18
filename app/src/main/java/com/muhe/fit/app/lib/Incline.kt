package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * incline.js 的移植（跑步机坡度：cardio set 上的 grade，百分比，可选字段）。
 * 来源函数逐一注明 JS 行号（incline.js，2026-10-20 版）。
 *
 * 没带它的 set 是平跑——此前记录的一切都如此，存量不回写；不识此键的旧端把它原样留着。
 * 依赖：isCustomEx（exercises.js:100，exercises.js 未单列移植）——Android 侧先以 ex.custom
 * 标记判定；registerCustom/customIds（用户自定义目录索引）随自定义动作库批次接入（注记在案）。
 */
object Incline {

    /** incline.js:12-13——跑步机 ~15% 封顶、坡度训练台 40%；半个百分点是最细步进 */
    const val INCLINE_MAX = 40.0
    const val INCLINE_STEP = 0.5

    /** JS Number(v) 宽松读数：数字面直读，字符串面按内容，其余 → NaN */
    private fun numOfJs(v: JsonElement?): Double = when (v) {
        null, is JsonNull -> Double.NaN
        is JsonPrimitive ->
            if (v.isString) v.content.toDoubleOrNull() ?: Double.NaN
            else v.doubleOrNull ?: Double.NaN
        else -> Double.NaN
    }

    /**
     * incline.js:19-24 clampIncline——输入/步进的坡度落库形：半个百分点上，0–40。
     * 「没有」（清空的字段）保持没有——键随删，不留没人输过的 0。
     */
    fun clampIncline(v: JsonElement?): Double? {
        if (v == null || v is JsonNull) return null
        if (v is JsonPrimitive && v.isString && v.content.isEmpty()) return null   // JS: v === ''
        val n = numOfJs(v)
        if (!n.isFinite()) return null
        return min(INCLINE_MAX, max(0.0, jsRound(n / INCLINE_STEP) * INCLINE_STEP))
    }

    // incline.js:29-31——坡度在哪有意义：有坡度的机器，与户外徒步/跑（坡是同一物）。
    // 读目录英文名与器材；车/划船/绳/开合跳没坡可设，名字里怎么说都不算。
    private val HILLY = Regex(
        "treadmill|\\bwalk|\\brun\\b|running|\\bjog|sprint|stepmill|stair|stepper|elliptical|cross ?trainer|\\bhik(e|ing)|incline|trot|march",
        RegexOption.IGNORE_CASE,
    )
    private val FLAT = Regex(
        "bike|bicycl|cycl|row(ing|er)?\\b|rope|skat|swim|\\bski\\b|erg\\b|jack|burpee|punch|shuffle|hop|jump|climber|crawl",
        RegexOption.IGNORE_CASE,
    )
    private val HILLY_EQ = setOf("elliptical machine", "stepmill machine")

    /**
     * incline.js:39-45 inclineFits——cardio 动作有没有坡度列：目录动作看名字/器材说不说
     * treadmill、walk、run、stairs、elliptical；自己的动作默认有——名字可以是任何语言，
     * 从不填的可选字段不要钱（名字说没坡才没有）。
     */
    fun inclineFits(ex: JsonObject?): Boolean {
        if (ex == null) return false
        val name = ex.str("n") ?: ""
        if (FLAT.containsMatchIn(name)) return false
        if (HILLY.containsMatchIn(name) || HILLY_EQ.contains((ex.str("eq") ?: "").lowercase())) return true
        return isCustomEx(ex)
    }

    /** exercises.js:100 isCustomEx 的 Android 最小面：custom 标记一腿（customIds 面随批次接入） */
    private fun isCustomEx(ex: JsonObject?): Boolean = ex?.bool("custom") == true

    /** incline.js:48 hasIncline——set 带了值得显示的坡度：高于平地即有 */
    fun hasIncline(s: JsonObject?): Boolean = numOfJs(s?.get("incline")) > 0.0

    /** incline.js:51 inclineFrom——新行跟着上一组走：有坡就带坡（无 → 空对象，键不落） */
    fun inclineFrom(s: JsonObject?): JsonObject {
        val n = numOfJs(s?.get("incline"))
        return if (n > 0.0) Js.obj("incline" to jn(n)) else Js.emptyObj()
    }
}
