package com.muhe.fit.app.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Golden fixture 读取与断言基类。
 *
 * 机制（全文见 android-app/docs/golden-testing.md）：
 * ① fixture 真源 = ck/frontend/src/lib/ 下各 .test.js 的代表用例（JS 侧不改语义）
 * ② ck/scripts/export-golden-fixtures.mjs 生成 JSON 到 app/src/test/resources/golden/ 目录
 * （注意：Kotlin 块注释可嵌套，注释文案里避免写「斜杠星号」字面——.test.js/.json 的通配写法曾被当嵌套注释开启）
 * ③ 本基类读 fixture 逐条断言「同输入 → 同输出」（数值容差 1e-6，吸收 JS/JVM pow/exp 的 ulp 级差异）
 * ④ 纪律：JS 侧语义改动 → 先再生 fixture → 再移植对齐
 *
 * JSON 解析用 kotlinx-serialization 的 JsonElement 手工导航（该库已在主依赖白名单内，
 * 测试期不新增任何依赖）。
 */
abstract class GoldenTestBase {

    protected fun loadFixture(name: String): JsonObject =
        javaClass.classLoader?.getResourceAsStream("golden/$name")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?.let { Json.parseToJsonElement(it).jsonObject }
            ?: error("golden fixture 缺失或不可读: resources/golden/$name")

    /** 取指定函数块；fixture 结构不符时 fail-visible（error 带上下文） */
    protected fun JsonObject.functionBlock(fn: String): JsonObject {
        val blocks = getValue("functions").jsonArray.map { it.jsonObject }
        return blocks.firstOrNull { it.getValue("fn").jsonPrimitive.content == fn }
            ?: error("fixture 无函数块: $fn（已有块: ${blocks.map { it.getValue("fn").jsonPrimitive.content }}）")
    }

    protected fun JsonObject.goldenCases(): List<JsonObject> =
        getValue("cases").jsonArray.map { it.jsonObject }

    /** 取 fixture 的 catalog 段（训练域 fixture：Kotlin 侧 ExIdx 的最小目录数据） */
    protected fun JsonObject.goldenCatalog(): JsonObject = getValue("catalog").jsonObject

    /** 取 fixture 的 constants 段 */
    protected fun JsonObject.goldenConstants(): JsonObject = getValue("constants").jsonObject

    /** 逐 case 断言：invoke(args) 的返回值对照 expected */
    protected fun assertGoldenCases(
        cases: List<JsonObject>,
        invoke: (args: List<JsonElement>) -> Any?,
    ) {
        check(cases.isNotEmpty()) { "fixture 函数块无用例——导出脚本坏了？" }
        cases.forEachIndexed { i, case ->
            val args = case.getValue("args").jsonArray
            val expected = case.getValue("expected")
            val actual = invoke(args)
            when {
                expected is JsonNull ->
                    check(actual == null) { "case#$i args=$args: 期望 null，实际 $actual" }

                expected.jsonPrimitive.booleanOrNull != null ->
                    check(actual == expected.jsonPrimitive.booleanOrNull) {
                        "case#$i args=$args: 期望 ${expected.jsonPrimitive.booleanOrNull}，实际 $actual"
                    }

                // isString 精确判字符串（contentOrNull 对数字也返回文本，不能当字符串判定！）
                expected.jsonPrimitive.isString ->
                    check(actual == expected.jsonPrimitive.content) {
                        "case#$i args=$args: 期望 \"${expected.jsonPrimitive.content}\"，实际 $actual"
                    }

                else -> {
                    val exp = expected.jsonPrimitive.double
                    check(actual != null) { "case#$i args=$args: 期望 $exp，实际 null" }
                    val act = (actual as Number).toDouble()
                    check(kotlin.math.abs(act - exp) <= TOLERANCE) {
                        "case#$i args=$args: 期望 $exp，实际 $act（超出容差 $TOLERANCE）"
                    }
                }
            }
        }
    }

    /**
     * 逐 case 结构比对：invoke(args) 的对象/数组返回值对照 expected。
     * 数值沿 1e-6 容差（吸收 JS JSON.stringify 与 Kotlin 序列化的整数/小数表示差异）；
     * 键集合必须两侧一致（JS undefined 键不落 JSON ↔ Kotlin 侧不写该键）。
     */
    protected fun assertGoldenObjects(
        cases: List<JsonObject>,
        invoke: (args: List<JsonElement>) -> JsonElement?,
    ) {
        check(cases.isNotEmpty()) { "fixture 函数块无用例——导出脚本坏了？" }
        cases.forEachIndexed { i, case ->
            val args = case.getValue("args").jsonArray
            val expected = case.getValue("expected")
            val actual = invoke(args)
            val mismatch = diff(expected, actual, "case#$i")
            check(mismatch == null) { mismatch!! }
        }
    }

    private fun diff(exp: JsonElement?, act: JsonElement?, path: String): String? {
        if (exp is JsonNull || exp == null) return if (act is JsonNull || act == null) null else "$path: 期望 null，实际 $act"
        if (act == null) return "$path: 期望 $exp，实际无返回"
        if (exp is JsonPrimitive && act is JsonPrimitive) {
            val en = exp.doubleOrNull()
            val an = act.doubleOrNull()
            // 数值容差通道要求两侧同型（isString 一致）：字符串 "80" 与数字 80.0 判等会
            // 漏掉 JS/Kotlin 序列化形状漂移——golden 对拍正是要抓它（2026-11-18 修复）
            if (en != null && an != null && exp.isString == act.isString) {
                return if (kotlin.math.abs(en - an) <= TOLERANCE) null
                else "$path: 期望 $en，实际 $an（超出容差 $TOLERANCE）"
            }
            return if (exp == act) null else "$path: 期望 $exp，实际 $act"
        }
        if (exp is JsonObject && act is JsonObject) {
            exp.keys.firstOrNull { !act.containsKey(it) }?.let { return "$path.$it: 期望有该键，实际缺" }
            act.keys.firstOrNull { !exp.containsKey(it) }?.let { return "$path.$it: 期望无该键，实际多出" }
            return exp.keys.firstNotNullOfOrNull { k -> diff(exp[k]!!, act[k]!!, "$path.$k") }
        }
        if (exp is JsonArray && act is JsonArray) {
            if (exp.size != act.size) return "$path: 数组长度 ${exp.size} ≠ ${act.size}"
            return exp.indices.firstNotNullOfOrNull { i -> diff(exp[i], act[i], "$path[$i]") }
        }
        return "$path: 类型不匹配（期望 ${exp::class.simpleName}，实际 ${act::class.simpleName}）"
    }

    // ── JsonElement 小工具（JsonNull 与缺参统一折叠为 null）──

    protected fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    protected fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    protected fun JsonElement.doubleOrNull(): Double? =
        (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    protected fun JsonElement.booleanOrNull(): Boolean? =
        (this as? JsonPrimitive)?.booleanOrNull

    /** 常量段对齐：fixture 里记录的 JS 侧常量必须与本仓移植值一致 */
    protected fun assertConstants(fixture: JsonObject, repCap: Int, weightedRepCap: Int, defaultFormula: String) {
        val c = fixture.getValue("constants").jsonObject
        check(c.getValue("REP_CAP").jsonPrimitive.int == repCap) {
            "REP_CAP 漂移: fixture=${c.getValue("REP_CAP").jsonPrimitive.int} port=$repCap"
        }
        check(c.getValue("WEIGHTED_REP_CAP").jsonPrimitive.int == weightedRepCap) {
            "WEIGHTED_REP_CAP 漂移: fixture=${c.getValue("WEIGHTED_REP_CAP").jsonPrimitive.int} port=$weightedRepCap"
        }
        check(c.getValue("DEFAULT_FORMULA").jsonPrimitive.content == defaultFormula) {
            "DEFAULT_FORMULA 漂移: fixture=${c.getValue("DEFAULT_FORMULA").jsonPrimitive.content} port=$defaultFormula"
        }
    }

    companion object {
        // 见 golden-testing.md §容差：远小于 rounded 输出的 0.1 步进，大于 ulp 级库差异
        const val TOLERANCE = 1e-6
    }
}
