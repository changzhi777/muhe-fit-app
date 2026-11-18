package com.muhe.fit.app.lib

import com.muhe.fit.app.golden.GoldenTestBase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * onerm.js ↔ Kotlin 移植的 golden 对齐测试（首个移植示范）。
 *
 * fixture: app/src/test/resources/golden/onerm.json
 * 真源:    ck/frontend/src/lib/onerm.js（语义由 ck/frontend/src/lib/onerm.test.js 锁定）
 * 再生:    node ck/scripts/export-golden-fixtures.mjs
 */
class OnermGoldenTest : GoldenTestBase() {

    private val fixture = loadFixture("onerm.json")

    @Test
    fun constantsAlignWithJsSource() {
        assertConstants(fixture, Onerm.REP_CAP, Onerm.WEIGHTED_REP_CAP, Onerm.DEFAULT_FORMULA)
    }

    @Test
    fun estimate1RMMatchesJsGolden() {
        assertGoldenCases(fixture.functionBlock("estimate1RM").goldenCases()) { args ->
            Onerm.estimate1RM(
                w = args[0].jsonPrimitive.double,
                r = args[1].jsonPrimitive.double,
                formula = args.getOrNull(2)?.stringOrNull() ?: Onerm.DEFAULT_FORMULA,
                rir = args.getOrNull(3)?.doubleOrNull(),
            )
        }
    }

    @Test
    fun weightedEstimateMatchesJsGolden() {
        assertGoldenCases(fixture.functionBlock("weightedEstimate").goldenCases()) { args ->
            Onerm.weightedEstimate(args[0].jsonPrimitive.double, args[1].jsonPrimitive.double)
        }
    }

    @Test
    fun calculate1RMAccuracyMatchesJsGolden() {
        assertGoldenCases(fixture.functionBlock("calculate1RMAccuracy").goldenCases()) { args ->
            Onerm.calculate1RMAccuracy(
                reps = args[0].jsonPrimitive.double,
                rir = args.getOrNull(1)?.doubleOrNull(),
                formula = args.getOrNull(2)?.stringOrNull() ?: "weighted",
            )
        }
    }

    @Test
    fun formulaOfMatchesJsGolden() {
        assertGoldenCases(fixture.functionBlock("formulaOf").goldenCases()) { args ->
            // JS 入参是状态对象 S（读 S?.oneRmFormula）；fixture 以 {"oneRmFormula": "..."} 表达
            val state = args[0].takeIf { it !is JsonNull }?.jsonObject
            Onerm.formulaOf(state?.get("oneRmFormula")?.jsonPrimitive?.contentOrNull)
        }
    }

    // ── JsonElement 小工具已上收 GoldenTestBase（stringOrNull/doubleOrNull，JsonNull 与缺参统一折叠 null）──
}
