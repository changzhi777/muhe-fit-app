package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * pyramid.js 的移植（金字塔组：每组一个次数目标，按序——数字或 "max" 尽力组）。
 * 来源函数逐一注明 JS 行号（pyramid.js，2026-10-20 版）。
 *
 * 宿主模型：cfg/workouts/rows = kotlinx JsonObject；目标列表 = List<JsonElement>，
 * 元素是数字原语或字符串原语 "max"（JS `v === PYRAMID_MAX` 严格等值，字符串 "5" 也按
 * Number('5') 读数——normalizePyramid 测试 [7.6, 0, -3, '5'] → [8,1,1,5] 锚定该口径）。
 * 本模块不 import history.js（history.js 反向 import 本模块——JS 注释纪律，照搬）。
 * pyramidLabel 的 t('Max') 是 i18n：maxLabel 由调用侧（Compose stringResource）传入。
 */
object Pyramid {

    /** pyramid.js:8-9 */
    const val PYRAMID_MAX = "max"
    const val MAX_PYRAMID_SETS = 10

    /** "max" 目标的 JSON 形（JS: 字符串 'max'） */
    private fun maxEl(): JsonElement = JsonPrimitive(PYRAMID_MAX)

    /** JS `v === PYRAMID_MAX`——严格字符串等值 */
    private fun isMaxEl(v: JsonElement?): Boolean =
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content == PYRAMID_MAX

    /** JS `Number(v)`——数字面直读；字符串面按内容读；null/对象 → NaN（不可数） */
    private fun numOfJs(v: JsonElement?): Double = when (v) {
        null, is JsonNull -> Double.NaN                 // JS Number(undefined) === NaN
        is JsonPrimitive ->
            if (v.isString) v.content.toDoubleOrNull() ?: Double.NaN
            else v.doubleOrNull ?: Double.NaN
        else -> Double.NaN
    }

    /** JS String(数字)：12 → "12"、12.5 → "12.5"（Kotlin Double.toString 会出 "12.0"） */
    private fun jsNumStr(v: Double): String = if (v == floor(v)) v.toLong().toString() else v.toString()

    /** pyramid.js:12-17 PYRAMID_PRESETS——编辑器一键替换的目标组 */
    val PYRAMID_PRESETS: List<List<JsonElement>> = listOf(
        listOf(jn(12.0), jn(10.0), jn(8.0), jn(6.0)),
        listOf(jn(6.0), jn(8.0), jn(10.0), jn(12.0)),
        listOf(jn(12.0), jn(10.0), jn(8.0), jn(10.0), jn(12.0)),
        listOf(jn(12.0), jn(8.0), jn(6.0), maxEl(), jn(12.0)),
    )

    /** pyramid.js:19-21 isPyramid——reps 模式且 pyramid 非空（mode 空/缺按 'reps'，JS truthiness） */
    fun isPyramid(cfg: JsonObject?): Boolean {
        if (cfg == null) return false
        val py = cfg.arr("pyramid") ?: return false
        if (py.isEmpty()) return false
        val mode = cfg.str("mode")?.takeIf { it.isNotEmpty() } ?: "reps"
        return mode == "reps"
    }

    /**
     * pyramid.js:26-37 normalizePyramid——编辑器与手改计划文件能放什么，收敛到会话能练的：
     * ≥1 的整目标、"max"、至多 MAX_PYRAMID_SETS 组。stride 2 是单侧动作：目标向上取偶，
     * L/R 才分得匀（11→12、1→2；"max" 不动）。
     */
    fun normalizePyramid(list: List<JsonElement>?, stride: Int = 1): List<JsonElement> {
        if (list == null) return emptyList()             // JS: !Array.isArray
        val out = ArrayList<JsonElement>()
        for (v in list) {
            if (isMaxEl(v)) out.add(maxEl())
            else {
                // JS: v != null && v !== '' && Number.isFinite(Number(v))——JSON null/''/非数
                // 都在 numOfJs 落 NaN 后剔除（bool 目标 JS 会按 Number(true)=1 计，宿主不产，注记在案）
                val n = numOfJs(v)
                if (n.isFinite()) {
                    val reps = max(1.0, jsRound(n))
                    out.add(jn(ceil(reps / stride) * stride))
                }
            }
        }
        return out.take(MAX_PYRAMID_SETS)
    }

    /** pyramid.js:40-43 pyramidFromFlat——开开关从现有「组×次」起步 */
    fun pyramidFromFlat(cfg: JsonObject?): List<JsonElement> {
        val n = max(1.0, min(MAX_PYRAMID_SETS.toDouble(), jsRound(cfg.numOr("sets"))))
        val reps = cfg.num("reps")?.takeIf { it != 0.0 } ?: 10.0   // JS: cfg.reps || 10
        return List(n.toInt()) { jn(reps) }
    }

    /** pyramid.js:46-49 flatFromPyramid 返回 */
    data class FlatCounts(val sets: Int, val reps: Double)

    /** pyramid.js:46-49 flatFromPyramid——关开关回「组×次」：组数 + 首个数字目标（只认数字，"5" 不算） */
    fun flatFromPyramid(list: List<JsonElement>?): FlatCounts {
        val first = (list ?: emptyList())
            .firstOrNull { (it as? JsonPrimitive)?.let { p -> !p.isString && p.doubleOrNull != null } == true }
            ?.let { (it as JsonPrimitive).doubleOrNull }
        return FlatCounts(list?.size ?: 0, first ?: 10.0)
    }

    /** pyramid.js:52-54 pyramidLabel——"12 · 8 · 6 · Max · 12"；Max 文案由调用侧传（JS: t('Max')） */
    fun pyramidLabel(list: List<JsonElement>?, maxLabel: String = "Max"): String =
        (list ?: emptyList()).joinToString(" · ") { v ->
            if (isMaxEl(v)) maxLabel else jsNumStr(numOfJs(v))
        }

    /** pyramid.js:57-59 pyramidTargetAt——列表内取自身，会话加组过界抄最后一档（空列表 → null = JS undefined） */
    fun pyramidTargetAt(list: List<JsonElement>?, i: Int): JsonElement? {
        val l = list ?: return null
        return if (i < l.size) l[i] else l.lastOrNull()
    }

    /**
     * pyramid.js:64-68 normalizePyramidRest——每组各自的休息秒数（pyramidRest[i] 对 pyramid[i]），
     * 0 = 「用动作的休息」。定长到列表；全零 → 空表（从没设过休息的金字塔保持原形状）。
     */
    fun normalizePyramidRest(rest: List<JsonElement>?, length: Int): List<Double> {
        val src = rest ?: emptyList()
        // JS: Math.max(0, Math.round(Number(src[i])) || 0)——NaN（缺格/非数）落 0
        val out = (0 until length).map { i ->
            val r = jsRound(numOfJs(src.getOrNull(i)))
            max(0.0, if (r.isFinite()) r else 0.0)
        }
        return if (out.any { it > 0 }) out else emptyList()
    }

    /**
     * pyramid.js:73-80 normalizePyramidWeight——每组各自的计划重量（pyramidWeight[i]），
     * 0 = 「无计划」：该组从上次同组举起的开。定长；全零 → 空表（同 pyramidRest）。
     */
    fun normalizePyramidWeight(weights: List<JsonElement>?, length: Int): List<Double> {
        val src = weights ?: emptyList()
        val out = (0 until length).map { i ->
            val w = numOfJs(src.getOrNull(i))
            if (w.isFinite() && w > 0) jsRound(w * 100) / 100 else 0.0
        }
        return if (out.any { it > 0 }) out else emptyList()
    }

    /** pyramid.js:84-87 pyramidWeightAt——金字塔工作组 i 的计划重量，0 = 该组没有 */
    fun pyramidWeightAt(cfg: JsonObject?, i: Int): Double {
        if (!isPyramid(cfg)) return 0.0
        val pw = cfg!!.arr("pyramidWeight")
        if (pw.isNullOrEmpty()) return 0.0
        val v = pyramidTargetAt(pw, i)
        val n = numOfJs(v)
        return max(0.0, if (n.isFinite()) n else 0.0)    // JS: Math.max(0, Number(...) || 0)
    }

    /**
     * pyramid.js:91-97 pyramidRestFor——勾掉的工作行挣的休息，0 = 回动作休息。
     * `i` 索引 rows（含热身）；金字塔只数工作组。
     */
    fun pyramidRestFor(target: JsonObject?, rows: List<JsonElement>?, i: Int): Double {
        if (!isPyramid(target)) return 0.0
        val pr = target!!.arr("pyramidRest")
        if (pr.isNullOrEmpty()) return 0.0
        val row = rows?.getOrNull(i) as? JsonObject
        if (row == null || WorkoutModel.isWarmupRow(row)) return 0.0
        // JS: rows.slice(0, i).filter(r => !isWarmupRow(r)).length（非对象行按非热身计，同 JS falsy 路径）
        val k = rows!!.subList(0, i).count { r -> !WorkoutModel.isWarmupRow(r as? JsonObject) }
        val v = numOfJs(pyramidTargetAt(pr, k))
        return max(0.0, if (v.isFinite()) v else 0.0)
    }

    // ---- Max 组自史册读回（pyramid.js:99+） ----

    /** pyramid.js:102 Max 组记录点 { w, r, d, t } */
    data class MaxSet(val w: Double, val r: Double, val d: String?, val t: Double?)

    /** pyramid.js:102-113 maxSetsOf——某动作每个完成的 Max 组，旧→新（私有） */
    private fun maxSetsOf(workouts: List<JsonElement>?, exId: String): List<MaxSet> {
        val out = ArrayList<MaxSet>()
        for (w in workouts ?: emptyList()) {
            val wo = w as? JsonObject ?: continue
            for (en in wo.arr("entries") ?: emptyList()) {
                val e = en as? JsonObject ?: continue
                if (e.str("id") != exId) continue
                for (s in e.arr("sets") ?: emptyList()) {
                    val so = s as? JsonObject ?: continue
                    if (jsTruthy(so.get("max")) && jsTruthy(so.get("done"))
                        && so.numOr("r") > 0 && !WorkoutModel.isWarmupRow(so)
                    ) out.add(MaxSet(so.numOr("w"), so.numOr("r"), wo.str("d"), wo.num("start")))
                }
            }
        }
        return out
    }

    /**
     * pyramid.js:117-124 maxRecordAt——重量 `w` 的 Max 组要挑战的纪录：该动作在 `w` 或更重
     * 时的最多次数（轻重量更多次不算纪录）。平手：更重者，再先到者。
     */
    fun maxRecordAt(workouts: List<JsonElement>?, exId: String, w: Double?): MaxSet? {
        val wn = w?.takeIf { it.isFinite() && it != 0.0 } ?: 0.0   // JS: Number(w) || 0
        var best: MaxSet? = null
        for (s in maxSetsOf(workouts, exId)) {
            if (s.w < wn) continue
            if (best == null || s.r > best.r || (s.r == best.r && s.w > best.w)) best = s
        }
        return best
    }

    /** pyramid.js:127 maxRepsSeries 返回：[{ t, y, d, w }]（Stats 图） */
    data class MaxRepsPoint(val t: Double?, val y: Double, val d: String?, val w: Double)

    /** pyramid.js:127-135 maxRepsSeries——每训练一次的 Max 组最多次数（键 = t|d，JS undefined 拼串仅作身份键） */
    fun maxRepsSeries(workouts: List<JsonElement>?, exId: String): List<MaxRepsPoint> {
        val byWorkout = LinkedHashMap<String, MaxSet>()
        for (s in maxSetsOf(workouts, exId)) {
            val key = "${s.t}|${s.d}"
            val cur = byWorkout[key]
            if (cur == null || s.r > cur.r) byWorkout[key] = s
        }
        return byWorkout.values.map { MaxRepsPoint(it.t, it.r, it.d, it.w) }
    }
}
