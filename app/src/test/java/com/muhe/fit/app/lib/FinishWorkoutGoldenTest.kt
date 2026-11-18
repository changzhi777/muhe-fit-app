package com.muhe.fit.app.lib

import com.muhe.fit.app.golden.GoldenTestBase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * finish-workout.js ↔ Kotlin 移植的 golden 对齐测试（2 函数块 32 例）。
 *
 * fixture: app/src/test/resources/golden/finish-workout.json
 * 真源:    ck/frontend/src/lib/finish-workout.js（语义由 finish-workout.test.js 锁定）
 * 再生:    node ck/scripts/export-golden-fixtures.mjs
 *
 * 注：buildCompletedWorkout 的 snapshotFor 回调参数不可 JSON 化，fixture 不覆盖（JS 侧原测试覆盖）。
 */
class FinishWorkoutGoldenTest : GoldenTestBase() {

    private val fixture = loadFixture("finish-workout.json")

    init {
        Ex.register(fixture.goldenCatalog())
    }

    private fun block(fn: String) = fixture.functionBlock(fn).goldenCases()

    @Test
    fun sessionEndMatchesJsGolden() = assertGoldenCases(block("sessionEnd")) { args ->
        FinishWorkout.sessionEnd(
            args[0].asObjectOrNull(),
            args[1].jsonPrimitive.double,
            gap = args.getOrNull(2)?.doubleOrNull() ?: FinishWorkout.FORGOTTEN_GAP_MS,
        )
    }

    @Test
    fun buildCompletedWorkoutMatchesJsGolden() = assertGoldenObjects(block("buildCompletedWorkout")) { args ->
        val active = args[0].asObjectOrNull() ?: Js.emptyObj()
        val opts = args.getOrNull(1)?.asObjectOrNull() ?: Js.emptyObj()
        val end = opts.getValue("end").jsonPrimitive.double   // fixture 一律显式钉死（默认参 Date.now() 不可复现）
        val prs = opts.get("prs") ?: JsonArray(emptyList())
        FinishWorkout.buildCompletedWorkout(active, end, prs)
    }

    @Test
    fun forgottenGapAlignsWithJsSource() {
        val c = fixture.goldenConstants()
        check(c.getValue("FORGOTTEN_GAP_MS").jsonPrimitive.double == FinishWorkout.FORGOTTEN_GAP_MS)
    }
}
