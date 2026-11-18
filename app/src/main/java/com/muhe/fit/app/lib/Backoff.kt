package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * backoff.js 全量移植（回退阶梯：顶组之后每组降一步）。
 *
 * 移植自 ck/frontend/src/lib/backoff.js（golden 对齐见 BackoffGoldenTest，机制见
 * android-app/docs/golden-testing.md）。依赖闭包：workout-model.js 的 isWarmupRow /
 * isSideSet / syncSideAggregate（WorkoutModel）、exercises.js 的 defaultIncrement /
 * isAssisted（Ex）。
 */
object Backoff {

    // backoff.js:15 round1
    private fun round1(v: Double): Double = jsRound(v * 10) / 10

    /**
     * backoff.js:20-24 isBackoff——默认关，老计划原样构建；金字塔与 rest-pause 无可降。
     */
    fun isBackoff(cfg: JsonObject?): Boolean =
        cfg != null && cfg.bool("backoff") == true &&
            (cfg.str("mode") ?: "reps") == "reps" &&
            (cfg.arr("pyramid") == null || cfg.arr("pyramid")!!.isEmpty()) &&
            cfg.sub("intensifier")?.str("type") != "restpause"

    /**
     * backoff.js:32-40 backoffAt——工作组 k（0 = 顶组）的重量。与步进点按同一网格规则：
     * 顶组在格上则逐组落格，离格按原值下步；不低于一个步长，不高于顶组。
     * ⚠️ JS Math.round = floor(x + 0.5)（半值向 +∞），统一走 jsRound。
     */
    fun backoffAt(top: Double, k: Double, step: Double): Double {
        val t = if (top.isFinite()) top else 0.0   // JS Number(top) || 0
        val s = if (step.isFinite()) step else 0.0
        if (!(t > 0) || !(s > 0) || !(k > 0)) return t
        val raw = t - k * s
        val onGrid = abs(t - jsRound(t / s) * s) <= 0.1
        val v = if (onGrid) round1(jsRound(raw / s) * s) else round1(raw)
        return max(min(t, s), v)
    }

    /**
     * backoff.js:45-48 backoffStepOf——回退步长：器械自带 inc，否则目录默认；辅助器械不适用（更轻 = 更难）。
     */
    fun backoffStepOf(cfg: JsonObject?, unit: String): Double {
        if (!isBackoff(cfg) || Ex.isAssisted(cfg ?: Js.emptyObj())) return 0.0
        return cfg.num("inc")?.takeIf { it > 0 } ?: Ex.defaultIncrement(cfg?.get("id"), unit)
    }

    /** backoff.js:51-53 backoffWeights——n 个工作组的计划重量："26 → 24 → 22"。 */
    fun backoffWeights(top: Double, n: Int, step: Double): List<Double> =
        List(maxOf(0, n)) { k -> backoffAt(top, k.toDouble(), step) }

    /**
     * backoff.js:60-77 applyBackoff——把新构建条目的开放工作组自首个工作行下步。
     * 热身行不动（朝顶组 ramp），已勾行不改写，per-side 行两肢同降。
     */
    fun applyBackoff(rows: List<JsonObject>, step: Double): List<JsonObject> {
        if (!(step > 0)) return rows
        val first = rows.firstOrNull { !WorkoutModel.isWarmupRow(it) }
        val top = first?.numOr("w") ?: 0.0   // JS Number(first.w) || 0
        if (top <= 0) return rows
        var k = -1
        return rows.map { row ->
            if (WorkoutModel.isWarmupRow(row)) return@map row
            k++
            if (k == 0) return@map row
            val w = backoffAt(top, k.toDouble(), step)
            if (WorkoutModel.isSideSet(row)) {
                val sides = row.sub("sides")!!
                fun side(s: JsonObject?): JsonObject =
                    if (s?.bool("done") == true) s else Js.with(s ?: Js.emptyObj(), "w", jn(w))
                return@map WorkoutModel.syncSideAggregate(
                    Js.with(row, "sides", Js.obj("L" to side(sides.sub("L")), "R" to side(sides.sub("R"))))
                )
            }
            if (row.bool("done") == true) row else Js.with(row, "w", jn(w))
        }
    }
}
