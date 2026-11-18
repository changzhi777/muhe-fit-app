package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.floor

/**
 * JS 动态对象语义的最小 Kotlin 面（progression / backoff / finish-workout 移植共用）。
 *
 * 移植纪律：这三个 JS 模块在对象上大量使用 spread、delete、逐字段 `||`/`??` 读值——任何
 * 「按白名单重建 data class」的形态都会在透传链上丢未知字段（drops、at、note、自定义键…），
 * 而 finish-workout 的契约恰恰是「无标记的行按引用原样透传」。故行/条目/状态一律以
 * kotlinx JsonObject 为载体（Map 语义 = JS 对象），函数体逐行对位 JS 源；golden expected
 * 是 JS JSON.stringify 的产物，JsonObject 结构等值比对即全对齐（见 GoldenTestBase.diff）。
 */
object Js {

    /** {...pairs}，值为 null 的键不写（对应 JS undefined 键不参与） */
    fun obj(vararg pairs: Pair<String, JsonElement?>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in pairs) if (v != null) out[k] = v
        return JsonObject(out)
    }

    fun emptyObj(): JsonObject = JsonObject(LinkedHashMap())

    /** JS spread：后者覆盖前者；null 元素跳过（对应 ...undefined） */
    fun spread(vararg parts: JsonObject?): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (p in parts) p?.forEach { (k, v) -> out[k] = v }
        return JsonObject(out)
    }

    /** {...base, key: value}；value 为 null 时删键（调用侧自己决定 JS undefined/null 语义） */
    fun with(base: JsonObject, key: String, value: JsonElement?): JsonObject {
        val out = LinkedHashMap(base)
        if (value == null) out.remove(key) else out[key] = value
        return JsonObject(out)
    }

    /** delete base[key]（不存在则原样返回） */
    fun without(base: JsonObject, key: String): JsonObject {
        if (!base.containsKey(key)) return base
        val out = LinkedHashMap(base)
        out.remove(key)
        return JsonObject(out)
    }
}

/** 数值 → JsonPrimitive（容差比对见 GoldenTestBase，整数/小数表示差异不影响断言） */
fun jn(x: Double): JsonPrimitive = JsonPrimitive(x)
fun jn(x: Int): JsonPrimitive = JsonPrimitive(x)

fun jarrD(values: List<Double>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

/**
 * JS truthiness（作用在任意 JsonElement 上）：false/null/缺失 → false；
 * 数字 0/NaN → false；空串 → false；对象/数组 → true。
 */
fun jsTruthy(el: JsonElement?): Boolean = when (el) {
    null, is JsonNull -> false
    is JsonObject, is JsonArray -> true
    is JsonPrimitive -> el.booleanOrNull ?: el.doubleOrNull?.let { it != 0.0 && it.isFinite() } ?: el.content.isNotEmpty()
}

/** 字段存在且非 null（JS `x != null` 的 loose 判定：undefined 与 null 都算没有） */
fun JsonObject?.hasNotNull(key: String): Boolean {
    val el = this?.get(key) ?: return false
    return el !is JsonNull
}

/** JS 元素读数：Number(el)（缺失/非数 → null，由调用侧落 `||`/`??`） */
fun JsonElement?.numValue(): Double? =
    (this as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

// ── JS 读值语义（只在 JsonPrimitive 上取标量；对象/数组/JsonNull → null）──

/** JS 读一个可能是 number 的字段 */
fun JsonObject?.num(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

/** JS `x || 0`：0/NaN/缺失都归 fallback（默认 0） */
fun JsonObject?.numOr(key: String, fallback: Double = 0.0): Double =
    num(key)?.takeIf { it != 0.0 } ?: fallback

/** JS `x ?? null`：只在缺失/null 时落 */
fun JsonObject?.numN(key: String): Double? = num(key)

fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject?.bool(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.booleanOrNull

/** JS 数组字段：缺失/非数组 → null */
fun JsonObject?.arr(key: String): List<JsonElement>? = this?.get(key) as? JsonArray

/** JS 对象字段：缺失/非对象 → null */
fun JsonObject?.sub(key: String): JsonObject? = this?.get(key) as? JsonObject

/** JS `a || b || c` 的数值链：第一个非 0 非 NaN 的值，全空 → null */
fun chainNum(vararg xs: Double?): Double? = xs.firstOrNull { it != null && it != 0.0 && it.isFinite() }

/** JS 元素级 `reps[k] || 0` */
fun JsonElement?.doubleOr0(): Double = numValue() ?: 0.0

/** JS Math.round = floor(x + 0.5)（半值向 +∞），非 kotlin.math.round 的 ties-to-even */
fun jsRound(x: Double): Double = floor(x + 0.5)

/** 数值比较容差（与 golden TOLERANCE 同源：JS/JVM 浮点 ulp 级差异） */
const val JS_NUM_EPS = 1e-9

fun jsNumEq(a: Double, b: Double): Boolean = abs(a - b) <= JS_NUM_EPS
