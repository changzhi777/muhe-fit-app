package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * exercises.js 的目录依赖最小面（progression / backoff / finish-workout 的分类规则）。
 *
 * JS 的 EXIDX 是整本目录（6,554 条）的 id 索引；Android 侧不搬数据——只搬分类「规则」，
 * 目录「数据」（eq/bp/n）由各 fixture 的 catalog 段提供（导出脚本从 JS EXDB 取用例实际
 * 引用的动作），测试启动时注册进本对象。查不到的 id 一律按「未知动作」处理——与 JS 对
 * EXIDX 未命中的 falsy 路径一字不差（isCardio/isBodyweightEq/isLoadedEq/isAssisted 均
 * false，defaultIncrement 取轻步长）。
 */
object Ex {

    // exercises.js:213 / 222 / 241 / 249 —— 分类常量集，逐字对齐 JS
    private val BODYWEIGHT_EQ = setOf("body weight", "band", "resistance band")
    private val LOADED_EQ = setOf(
        "barbell", "ez barbell", "olympic barbell", "trap bar", "dumbbell", "kettlebell",
        "clubbell", "macebell", "cable", "leverage machine", "smith machine", "sled machine", "weighted",
    )
    private const val ASSISTED_EQ = "leverage machine"
    private val HEAVY_BP = setOf("upper legs", "lower legs", "back", "hips", "glutes")
    private val ASSISTED_NAME = Regex("\\bassist(ed)?\\b", RegexOption.IGNORE_CASE)

    // fixture catalog 段注册进来的最小目录：id → {eq, bp, n}（幂等：各 fixture 的同 id 元数据一致）
    private val catalog = LinkedHashMap<String, JsonObject>()

    fun register(catalogSection: JsonObject) {
        for ((id, meta) in catalogSection) (meta as? JsonObject)?.let { catalog[id] = it }
    }

    // exercises.js（JS: typeof idOrEx === 'string' ? EXIDX[idOrEx] : idOrEx）
    private fun exOf(idOrEx: JsonElement?): JsonObject? = when (idOrEx) {
        null, is JsonNull -> null
        is JsonObject -> idOrEx
        else -> catalog[idOrEx.jsonPrimitive.contentOrNull]
    }

    // 目录字段读取（dumbbells.js / exercises.js 调用面）
    fun eqOf(idOrEx: JsonElement?): String? = exOf(idOrEx)?.str("eq")
    fun exName(idOrEx: JsonElement?): String? = exOf(idOrEx)?.str("n")

    // exercises.js:204 isCardio
    fun isCardio(idOrEx: JsonElement?): Boolean = exOf(idOrEx)?.str("bp") == "cardio"

    // exercises.js:214 isBodyweightEq
    fun isBodyweightEq(idOrEx: JsonElement?): Boolean = BODYWEIGHT_EQ.contains(exOf(idOrEx)?.str("eq"))

    // exercises.js:223 isLoadedEq
    fun isLoadedEq(idOrEx: JsonElement?): Boolean = LOADED_EQ.contains(exOf(idOrEx)?.str("eq"))

    // exercises.js:246 assistedShape
    private fun assistedShape(ex: JsonObject?): Boolean {
        if (ex == null) return false
        ex.bool("assisted")?.let { return it }
        return ex.str("eq") == ASSISTED_EQ && ASSISTED_NAME.containsMatchIn(ex.str("n") ?: "")
    }

    // exercises.js:261 isAssisted
    fun isAssisted(idOrEx: JsonElement?): Boolean {
        if (idOrEx == null || idOrEx is JsonNull) return false
        if (idOrEx is JsonObject) {
            idOrEx.bool("assisted")?.let { return it }
            idOrEx.sub("target")?.bool("assisted")?.let { return it }
            val known = idOrEx.str("id")?.let { catalog[it] }
            return assistedShape(known ?: idOrEx)
        }
        return assistedShape(catalog[idOrEx.jsonPrimitive.contentOrNull])
    }

    // exercises.js:254 defaultIncrement
    fun defaultIncrement(exId: JsonElement?, unit: String?): Double {
        val heavy = exOf(exId)?.str("bp")?.let { HEAVY_BP.contains(it) } == true
        return if (unit == "lb") (if (heavy) 10.0 else 5.0) else (if (heavy) 5.0 else 2.5)
    }

    // exercises.js:271 betterWeight
    fun betterWeight(idOrEx: JsonElement?, a: Double, b: Double): Double =
        if (isAssisted(idOrEx)) kotlin.math.min(a, b) else kotlin.math.max(a, b)
}
