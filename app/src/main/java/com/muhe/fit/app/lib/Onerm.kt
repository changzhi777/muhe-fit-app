package com.muhe.fit.app.lib

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 1RM 估算（Epley 系七公式 + RIR %1RM 加权集成）。
 *
 * 移植自 ck/frontend/src/lib/onerm.js（golden 对齐见 OnermGoldenTest，机制见 android-app/docs/golden-testing.md）。
 * 设计哲学原样继承（onerm.js 头注释）：估算器只知道重量与次数，不知道动作库——
 * 心肺组 {min, speed} 与计时组 {sec, w} 天然没有次数，在任何扫描里自动出局；
 * 超过次数上限「宁可不猜，不印幻想」。
 *
 * ⚠️ 移植纪律：JS 侧语义改动必须先走 ck/scripts/export-golden-fixtures.mjs 再生成 fixture，
 * 再动本文件——双向一致的唯一通道是 fixture。
 */
object Onerm {

    // onerm.js:20/24 超过上限公式反映的是工作能力而非最大力量，拒绝猜测
    const val REP_CAP = 12
    const val WEIGHTED_REP_CAP = 15

    // onerm.js:37
    const val DEFAULT_FORMULA = "epley"

    // onerm.js:28-36 公式套件。LinkedHashMap 插入序 = JS 对象键序，weightedEstimate/ensembleCV
    // 的浮点求和顺序依赖此序（1-ulp 级），勿重排。
    private val FORMULAS: LinkedHashMap<String, (Double, Double) -> Double> = linkedMapOf(
        "epley" to { w, r -> w * (1 + r / 30) },
        "brzycki" to { w, r -> w * 36 / (37 - r) },
        "lombardi" to { w, r -> w * r.pow(0.1) },
        "oconner" to { w, r -> w * (1 + r / 40) },
        "mayhew" to { w, r -> w * 100 / (52.2 + 41.9 * exp(-0.055 * r)) },
        "wathan" to { w, r -> w * 100 / (48.8 + 53.8 * exp(-0.075 * r)) },
        "lander" to { w, r -> w * 100 / (101.3 - 2.67123 * r) },
    )

    // onerm.js:42-45 展示名（Settings 选择器用），纯数据，作者名不翻译
    val FORMULA_NAMES = mapOf(
        "epley" to "Epley", "brzycki" to "Brzycki", "lombardi" to "Lombardi", "oconner" to "O’Conner",
        "mayhew" to "Mayhew", "wathan" to "Wathan", "lander" to "Lander",
    )

    /**
     * onerm.js:46-49 公式选择：profile 在 Settings 里挑的公式；未知值一律回落默认，
     * 使新旧版本互通不炸。JS 入参是整个状态 S（读 S?.oneRmFormula），Kotlin 侧收窄为该字段。
     */
    fun formulaOf(oneRmFormula: String?): String {
        val f = oneRmFormula
        return if (f != null && (f == "weighted" || FORMULAS.containsKey(f))) f else DEFAULT_FORMULA
    }

    // ── RIR %1RM 映射（Mike Tuchscherer / RTS 量表，onerm.js:53-56）──
    // 下标 0 = 1RM 组，下标 14 = 15 次到力竭
    private val RIR_PCT = doubleArrayOf(
        100.0, 95.5, 92.2, 89.2, 86.3, 83.7, 81.1, 78.6, 76.2, 73.9,
        71.7, 69.5, 67.5, 65.5, 63.6,
    )

    // onerm.js:58-61
    private fun rirEstimate(w: Double, effectiveReps: Double): Double {
        // JS Math.round 语义 = floor(x + 0.5)（半值向 +∞），非 kotlin.math.round 的 ties-to-even
        val idx = (floor(effectiveReps + 0.5).toInt() - 1).coerceIn(0, RIR_PCT.size - 1)
        return w * 100 / RIR_PCT[idx]
    }

    // ── 权重矩阵（onerm.js:66-75）──
    // a_i：绝对可靠度；v_i(r)：有效次数上升时的可变衰减。weight_i(r) = a_i * v_i(r)
    private class FormulaWeight(val a: Double, val v: (Double) -> Double)

    private val WEIGHTS: LinkedHashMap<String, FormulaWeight> = linkedMapOf(
        "epley" to FormulaWeight(0.95) { r -> if (r <= 6) 1.0 else maxOf(0.4, 1 - (r - 6) * 0.12) },
        "brzycki" to FormulaWeight(1.10) { r -> if (r <= 8) 1.0 else maxOf(0.3, 1 - (r - 8) * 0.18) },
        "lombardi" to FormulaWeight(0.85) { r -> if (r <= 5) 1.0 else maxOf(0.4, 1 - (r - 5) * 0.10) },
        "oconner" to FormulaWeight(0.90) { r -> if (r <= 6) 1.0 else maxOf(0.4, 1 - (r - 6) * 0.12) },
        "mayhew" to FormulaWeight(0.95) { r -> if (r <= 8) 1.0 else maxOf(0.5, 1 - (r - 8) * 0.10) },
        "wathan" to FormulaWeight(0.90) { r -> if (r <= 7) 1.0 else maxOf(0.4, 1 - (r - 7) * 0.12) },
        "lander" to FormulaWeight(0.85) { r -> if (r <= 7) 1.0 else maxOf(0.35, 1 - (r - 7) * 0.15) },
        "rir" to FormulaWeight(1.00) { r -> if (r <= 10) 1.0 else maxOf(0.4, 1 - (r - 10) * 0.15) },
    )

    // onerm.js:78-83 七公式估算值的变异系数（尺度不变）
    private fun ensembleCV(w: Double, effectiveReps: Double): Double {
        val vals = FORMULAS.values.map { it(w, effectiveReps) }
        val mean = vals.fold(0.0) { acc, v -> acc + v } / vals.size
        val variance = vals.fold(0.0) { acc, v -> acc + (v - mean).pow(2.0) } / vals.size
        return sqrt(variance) / mean
    }

    /**
     * onerm.js:88-100 全公式 + RIR 映射的加权平均。导出未取整值——
     * 内部消费者（疲劳模型的强度锚点）要精确混合值；对外取整由 [estimate1RM] 负责。
     */
    fun weightedEstimate(w: Double, effectiveReps: Double): Double {
        var total = 0.0
        var wsum = 0.0
        for ((name, fn) in FORMULAS) {
            val spec = WEIGHTS.getValue(name)
            val wt = spec.a * spec.v(effectiveReps)
            total += fn(w, effectiveReps) * wt
            wsum += wt
        }
        val rirSpec = WEIGHTS.getValue("rir")
        val rirWt = rirSpec.a * rirSpec.v(effectiveReps)
        total += rirEstimate(w, effectiveReps) * rirWt
        wsum += rirWt
        return total / wsum
    }

    /** JS Math.round = floor(x + 0.5)；不能换 kotlin.math.round（ties-to-even，.5 边界与 JS 不同） */
    private fun jsRound(x: Double): Double = floor(x + 0.5)

    private fun round1(x: Double): Double = jsRound(x * 10) / 10   // JS Math.round(v*10)/10
    private fun round2(x: Double): Double = jsRound(x * 100) / 100 // JS Math.round(v*100)/100

    /**
     * onerm.js:108-134 从一组估算 1RM。无法诚实回答就返回 null：
     * 重量缺失/非正、次数 < 1、非有限值、超过次数上限。单次组不是估算而是测量，原样返回。
     */
    fun estimate1RM(
        w: Double,
        r: Double,
        formula: String = DEFAULT_FORMULA,
        rir: Double? = null,
    ): Double? {
        val weight = w
        val reps = r
        if (!weight.isFinite() || !reps.isFinite()) return null
        if (weight <= 0 || reps < 1) return null

        val validRir = rir != null && rir >= 0

        // r === 1 且无 RIR 或 RIR 0 → 测量，不是估算（onerm.js:116-119）
        if (reps == 1.0 && (rir == null || rir < 0 || rir == 0.0)) return round1(weight)

        if (formula == "weighted") {
            val effectiveReps = if (validRir) reps + (rir ?: 0.0) else reps
            if (effectiveReps > WEIGHTED_REP_CAP) return null
            val est = weightedEstimate(weight, effectiveReps)
            if (!est.isFinite() || est <= 0) return null
            return round1(est)
        }

        // 单式路径：RIR 不参与（有效次数换算只属于加权集成）
        if (reps > REP_CAP) return null
        val fn = FORMULAS[formula] ?: FORMULAS.getValue(DEFAULT_FORMULA)
        val est = if (reps == 1.0) weight else fn(weight, jsRound(reps))
        if (!est.isFinite() || est <= 0) return null
        return round1(est)
    }

    /**
     * onerm.js:200-222 估算置信指数（0–1）：次数衰减 × RIR 主观性 ×（weighted 时）公式间离散。
     */
    fun calculate1RMAccuracy(reps: Double, rir: Double? = null, formula: String = "weighted"): Double {
        val r = reps
        if (!r.isFinite() || r < 1) return 0.0
        // JS: r === 1 && rir === 0（严格相等；rir 为 null 不命中）
        if (r == 1.0 && rir != null && rir == 0.0) return 1.0
        if (r > WEIGHTED_REP_CAP) return 0.0

        // 次数衰减：1 次 → 1.0，WEIGHTED_REP_CAP 次 → 0.5
        val repFactor = if (r == 1.0) 1.0 else 1 - 0.5 * (r - 1) / (WEIGHTED_REP_CAP - 1)

        // 储备次数引入主观不确定性（~8% 惩罚）；RIR 0（力竭）是客观端，不惩罚
        val rirFactor = if (rir != null && rir.isFinite() && rir > 0) 0.92 else 1.0

        // 公式间离散（仅 weighted）：CV 越高置信越低
        var spreadFactor = 1.0
        if (formula == "weighted" && r >= 2) {
            val cv = ensembleCV(100.0, r)
            spreadFactor = maxOf(0.5, 1 - cv / 0.15 * 0.5)
        }

        return round2(repFactor * rirFactor * spreadFactor)
    }

    // ── 未移植（依赖 exercises/history/dumbbells 模块，随对应移植批次落地，JS 行号备查）──
    // bestSetOf(entry, formula)          onerm.js:139-155  组级最优估算（辅助器械排除，issue #232）
    // bestSetOfEntries(entries, formula) onerm.js:157-164  条目级归并
    // e1rmSeries(S, exId, ...)           onerm.js:170-178  趋势曲线点位
    // best1RM(S, exId, ...)              onerm.js:182-186  历史最优（含出处 set/date）
    // is1RMRecord(S, exId, entry, ...)   onerm.js:190-196  会话是否破纪录
}
