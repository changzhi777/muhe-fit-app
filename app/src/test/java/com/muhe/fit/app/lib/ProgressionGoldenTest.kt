package com.muhe.fit.app.lib

import com.muhe.fit.app.golden.GoldenTestBase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * progression.js ↔ Kotlin 移植的 golden 对齐测试（训练域核心，18 函数块 139 例）。
 *
 * fixture: app/src/test/resources/golden/progression.json
 * 真源:    ck/frontend/src/lib/progression.js（语义由 progression.test.js 锁定）
 * 再生:    node ck/scripts/export-golden-fixtures.mjs
 *
 * 目录依赖：fixture 的 catalog 段提供用例引用动作的 eq/bp/n（注册进 Ex），Kotlin 侧只搬分类规则。
 */
class ProgressionGoldenTest : GoldenTestBase() {

    private val fixture = loadFixture("progression.json")

    init {
        Ex.register(fixture.goldenCatalog())
    }

    private fun block(fn: String) = fixture.functionBlock(fn).goldenCases()

    @Test
    fun constantsAlignWithJsSource() {
        val c = fixture.goldenConstants()
        check(c.getValue("DELOAD_FACTOR").jsonPrimitive.double == Progression.DELOAD_FACTOR)
        check(c.getValue("DELOAD_FACTOR_MIN").jsonPrimitive.double == Progression.DELOAD_FACTOR_MIN)
        check(c.getValue("DELOAD_FACTOR_MAX").jsonPrimitive.double == Progression.DELOAD_FACTOR_MAX)
        check(c.getValue("DEFAULT_SEC_INCREMENT").jsonPrimitive.double == Progression.DEFAULT_SEC_INCREMENT)
        check(c.getValue("MAX_BW_SETS").jsonPrimitive.int == Progression.MAX_BW_SETS)
        check(c.getValue("MAX_TRIPLE_SETS").jsonPrimitive.int == Progression.MAX_TRIPLE_SETS)
        val after = c.getValue("DELOAD_AFTER").jsonObject
        check(after.keys == Progression.DELOAD_AFTER.keys) { "DELOAD_AFTER 键序漂移（浮点求和序无关，但语义面须一致）" }
        after.forEach { (k, v) ->
            check(Progression.DELOAD_AFTER[k] == v.jsonPrimitive.int) { "DELOAD_AFTER[$k] 漂移" }
        }
        val pf = c.getValue("POLICIES_FOR").jsonObject
        check(pf.keys == Progression.POLICIES_FOR.keys)
        pf.forEach { (k, v) ->
            val js = v.jsonArray.map { it.jsonPrimitive.content }
            check(Progression.POLICIES_FOR[k] == js) { "POLICIES_FOR[$k] 漂移: ${Progression.POLICIES_FOR[k]} vs $js" }
        }
    }

    @Test
    fun policyForMatchesJsGolden() = assertGoldenCases(block("policyFor")) { args ->
        Progression.policyFor(args[0].asObjectOrNull(), args.getOrNull(1)?.asObjectOrNull(), args.getOrNull(2)?.stringOrNull())
    }

    @Test
    fun weightIncrementMatchesJsGolden() = assertGoldenCases(block("weightIncrement")) { args ->
        Progression.weightIncrement(args[0].asObjectOrNull(), args[1].stringOrNull())
    }

    @Test
    fun epley1RMMatchesJsGolden() = assertGoldenCases(block("epley1RM")) { args ->
        Progression.epley1RM(args[0].jsonPrimitive.double, args[1].jsonPrimitive.double)
    }

    @Test
    fun deloadTarget1RMMatchesJsGolden() = assertGoldenCases(block("deloadTarget1RM")) { args ->
        Progression.deloadTarget1RM(
            args[0].jsonPrimitive.double,
            args[1].jsonPrimitive.double,
            factor = args.getOrNull(2)?.doubleOrNull() ?: Progression.DELOAD_FACTOR,
            perSide = args.getOrNull(3)?.booleanOrNull() ?: false,
        )
    }

    @Test
    fun deloadFactorOfMatchesJsGolden() = assertGoldenCases(block("deloadFactorOf")) { args ->
        Progression.deloadFactorOf(args[0].asObjectOrNull())
    }

    @Test
    fun snapWeightMatchesJsGolden() = assertGoldenCases(block("snapWeight")) { args ->
        Progression.snapWeight(args[0].jsonPrimitive.double, args[1].jsonPrimitive.double)
    }

    @Test
    fun addStepMatchesJsGolden() = assertGoldenCases(block("addStep")) { args ->
        Progression.addStep(args[0].jsonPrimitive.double, args[1].jsonPrimitive.double, args[2].jsonPrimitive.double)
    }

    @Test
    fun stepWeightMatchesJsGolden() = assertGoldenCases(block("stepWeight")) { args ->
        Progression.stepWeight(args[0].jsonPrimitive.double, args[1].jsonPrimitive.double, args[2].jsonPrimitive.double)
    }

    @Test
    fun deloadToMatchesJsGolden() = assertGoldenCases(block("deloadTo")) { args ->
        Progression.deloadTo(args[0].jsonPrimitive.double, args[1].jsonPrimitive.double, args.getOrNull(2)?.doubleOrNull() ?: Progression.DELOAD_FACTOR)
    }

    @Test
    fun plannedOfMatchesJsGolden() = assertGoldenObjects(block("plannedOf")) { args ->
        Progression.plannedOf(args[0].asObjectOrNull())
    }

    @Test
    fun planChangedMatchesJsGolden() = assertGoldenCases(block("planChanged")) { args ->
        Progression.planChanged(args[0].asObjectOrNull(), args[1].asObjectOrNull())
    }

    @Test
    fun tripleSetsOfMatchesJsGolden() = assertGoldenObjects(block("tripleSetsOf")) { args ->
        val t = Progression.tripleSetsOf(args[0].asObjectOrNull())
        com.muhe.fit.app.lib.Js.obj("setsMin" to jn(t.setsMin), "setsMax" to jn(t.setsMax))
    }

    @Test
    fun selectDeloadCandidateMatchesJsGolden() = assertGoldenObjects(block("selectDeloadCandidate")) { args ->
        Progression.selectDeloadCandidate(args[0].asObjectOrNull() ?: Js.emptyObj())
    }

    @Test
    fun readSessionMatchesJsGolden() = assertGoldenObjects(block("readSession")) { args ->
        Progression.readSession(args[0].asObjectOrNull())
    }

    @Test
    fun stallCountMatchesJsGolden() = assertGoldenCases(block("stallCount")) { args ->
        Progression.stallCount(
            (args[0] as JsonArray).mapNotNull { it.asObjectOrNull() },
            args.getOrNull(1)?.stringOrNull(),
        )
    }

    @Test
    fun sessionsForMatchesJsGolden() = assertGoldenObjects(block("sessionsFor")) { args ->
        val sessions = Progression.sessionsFor(
            args[0].asObjectOrNull(),
            args[1].stringOrNull(),
            args.getOrNull(2)?.asObjectOrNull(),
            args.getOrNull(3)?.stringOrNull(),
        )
        JsonArray(sessions)
    }

    @Test
    fun nextPrescriptionMatchesJsGolden() = assertGoldenObjects(block("nextPrescription")) { args ->
        Progression.nextPrescription(
            args[0].asObjectOrNull(),
            args[1].asObjectOrNull() ?: Js.emptyObj(),
            args.getOrNull(2)?.asObjectOrNull(),
        )
    }

    @Test
    fun applyPrescriptionMatchesJsGolden() = assertGoldenObjects(block("applyPrescription")) { args ->
        val sets = (args[0] as JsonArray).mapNotNull { it.asObjectOrNull() }
        val p = args.getOrNull(1)?.asObjectOrNull()
        val step = args.getOrNull(2)?.doubleOrNull() ?: 2.5
        val floor = args.getOrNull(3)?.doubleOrNull() ?: 0.0
        JsonArray(Progression.applyPrescription(sets, p, step, floor))
    }
}
