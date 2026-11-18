package com.muhe.fit.app.lib

import com.muhe.fit.app.golden.GoldenTestBase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * backoff.js ↔ Kotlin 移植的 golden 对齐测试（5 函数块 28 例）。
 *
 * fixture: app/src/test/resources/golden/backoff.json
 * 真源:    ck/frontend/src/lib/backoff.js（语义由 backoff.test.js 锁定）
 * 再生:    node ck/scripts/export-golden-fixtures.mjs
 */
class BackoffGoldenTest : GoldenTestBase() {

    private val fixture = loadFixture("backoff.json")

    init {
        Ex.register(fixture.goldenCatalog())
    }

    private fun block(fn: String) = fixture.functionBlock(fn).goldenCases()

    @Test
    fun isBackoffMatchesJsGolden() = assertGoldenCases(block("isBackoff")) { args ->
        Backoff.isBackoff(args[0].asObjectOrNull())
    }

    @Test
    fun backoffAtMatchesJsGolden() = assertGoldenCases(block("backoffAt")) { args ->
        Backoff.backoffAt(args[0].jsonPrimitive.double, args[1].jsonPrimitive.double, args[2].jsonPrimitive.double)
    }

    @Test
    fun backoffWeightsMatchesJsGolden() = assertGoldenObjects(block("backoffWeights")) { args ->
        JsonArray(Backoff.backoffWeights(
            args[0].jsonPrimitive.double,
            args[1].jsonPrimitive.double.toInt(),
            args[2].jsonPrimitive.double,
        ).map { JsonPrimitive(it) })
    }

    @Test
    fun backoffStepOfMatchesJsGolden() = assertGoldenCases(block("backoffStepOf")) { args ->
        Backoff.backoffStepOf(args[0].asObjectOrNull(), args[1].stringOrNull() ?: "kg")
    }

    @Test
    fun applyBackoffMatchesJsGolden() = assertGoldenObjects(block("applyBackoff")) { args ->
        val rows = (args[0] as JsonArray).mapNotNull { it.asObjectOrNull() }
        JsonArray(Backoff.applyBackoff(rows, args[1].jsonPrimitive.double))
    }
}
