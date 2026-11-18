package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonObject

/**
 * supersetFlow.js 全量移植（active-workout 超级组流程的纯决策面——导航推进 / 休息计时 / 进度高水位）。
 *
 * 移植自 ck/frontend/src/lib/supersetFlow.js（断言语义校准：supersetFlow.test.js）。
 * 依赖闭包：
 *  - workout-model.js 的 isWarmupRow（WarmupRestSecFor 判 ramp 行）→ WorkoutModel.isWarmupRow（已移植）；
 *  - superset-meta.js 的 supersetMeta 读函数 + SG_NAME_MAX（restSecFor 判组间休息 #292）→ 本文件移植
 *    （History.kt 已移植同文件的写侧 setSupersetMeta/syncSupersetMeta，读侧此前无消费方故未落）。
 * JS 侧返回对象 {unitDone, roundDone, nextIdx} / {isNew, highWater} 为纯数值闭包，按惯例落 data class。
 */
object SupersetFlow {

    // superset-meta.js:5 SG_NAME_MAX
    const val SG_NAME_MAX = 40

    /** superset-meta.js:9-18 supersetMeta 返回形状 { name, rest } */
    data class SupersetMetaInfo(val name: String, val rest: Double)

    // superset-meta.js:7 fieldsOf——onTarget=true 时组元数据挂在条目的 target 上（session 条目）
    private fun fieldsOf(item: JsonObject?, onTarget: Boolean): JsonObject =
        (if (onTarget) item?.sub("target") else item) ?: Js.emptyObj()

    /**
     * superset-meta.js:10-18 supersetMeta——unit（条目下标组）的组名与组间休息；
     * 第一位有值的成员代表全组（名单随组元携带，无第二结构）。
     * 注：JS `Number(f.sgRest)` 可接受数字串，此处按写侧（Math.round 落数字）只读 number。
     */
    fun supersetMeta(list: List<JsonObject>?, unit: List<Int>?, onTarget: Boolean = false): SupersetMetaInfo {
        var name = ""
        var rest = 0.0
        for (idx in unit ?: emptyList()) {
            val f = fieldsOf(list?.getOrNull(idx), onTarget)
            val nm = f.str("sgName")
            if (name.isEmpty() && nm != null && nm.trim().isNotEmpty()) name = nm.trim().take(SG_NAME_MAX)
            val r = f.num("sgRest")
            if (rest == 0.0 && r != null && r > 0.0) rest = jsRound(r)
        }
        return SupersetMetaInfo(name, rest)
    }

    // supersetFlow.js:6 hasWork——该条目还有未完成组（set.done falsy 即未完成；越界条目 = 无活）
    private fun hasWork(entries: List<JsonObject>?, idx: Int): Boolean {
        val sets = entries?.getOrNull(idx)?.arr("sets") ?: return false
        return sets.any { s -> !jsTruthy((s as? JsonObject)?.get("done")) }
    }

    /**
     * supersetFlow.js:10-17 nextUnfinishedUnit——当前导航单元之后第一个有活的单元，绕回一次。
     * 用户乱序完成时，只要前面还有活就不给出「训练完成」；返回 null = 全部完成。
     */
    fun nextUnfinishedUnit(entries: List<JsonObject>?, units: List<List<Int>>?, fromIdx: Int): List<Int>? {
        if (entries == null || units.isNullOrEmpty()) return null
        val current = units.indexOfFirst { it.contains(fromIdx) }
        val ordered = if (current < 0) units else units.drop(current + 1) + units.take(current)
        return ordered.firstOrNull { unit -> unit.any { hasWork(entries, it) } }
    }

    /**
     * supersetFlow.js:21-27 insertionIndexAfterCurrentUnit——新条目插在当前完整导航单元之后；
     * units 无效/空或当前不在任何单元里时安全落到条目列表末尾。
     */
    fun insertionIndexAfterCurrentUnit(units: List<List<Int>>?, currentIndex: Int, entryCount: Int): Int {
        val length = maxOf(0, entryCount)
        if (units.isNullOrEmpty()) return length
        val unit = units.firstOrNull { it.contains(currentIndex) }
        if (unit.isNullOrEmpty()) return length
        return minOf(length, unit.max() + 1)
    }

    /** supersetFlow.js:34 setProgressHighWater 返回形状 { isNew, highWater } */
    data class SetProgressHighWater(val isNew: Boolean, val highWater: Int)

    /**
     * supersetFlow.js:32-35 setProgressHighWater——只有把本条目推过「本会话见过的最大同时完成组数」
     * 才算新进度：取消/重勾已完成组不重放导航与休息副作用；补加的新组完成仍算。
     */
    fun setProgressHighWater(entry: JsonObject?, previous: Int = 0): SetProgressHighWater {
        val done = entry?.arr("sets")?.count { s -> jsTruthy((s as? JsonObject)?.get("done")) } ?: 0
        return SetProgressHighWater(isNew = done > previous, highWater = maxOf(previous, done))
    }

    /**
     * supersetFlow.js:48-50 restAfterSet——每个完成组之后都休息（issue #3：普通动作收尾组
     * 曾「安静完成」，两行动作只计时一次休息）；唯一不休息 = 最后动作的最后一组，会话已结束。
     */
    fun restAfterSet(unitDone: Boolean, lastUnit: Boolean): Boolean = !unitDone || !lastUnit

    /**
     * supersetFlow.js:65-67 restOnRecheck——重勾已完成组：高水位规则吞掉的重放，由这里补回
     * （issue #3 另一半）；计时已在走的不打扰——走着的计时属于最近完成的组，比重启更好。
     */
    fun restOnRecheck(timerRunning: Boolean, unitDone: Boolean, lastUnit: Boolean): Boolean =
        !timerRunning && restAfterSet(unitDone, lastUnit)

    /**
     * supersetFlow.js:85-100 restSecFor——完成组之后的休息秒数。
     * 单人组读 target.restSec，无则继承 defaultRestSec（0 = 全局休息计时关闭，显式自带者仍生效）；
     * 超级组（#292）自带 sgRest 则整轮只休 sgRest，否则取组内最长者（setRest 为金字塔组自带
     * 休息 {idx, sec}，顶替该成员的动作休息，组仍取最长）。defaultRestSec 传 null 对应 JS undefined。
     */
    fun restSecFor(
        entries: List<JsonObject>?,
        unit: List<Int>?,
        defaultRestSec: Double?,
        setRest: JsonObject? = null,
    ): Double {
        val fallback = if (defaultRestSec != null && defaultRestSec > 0.0) defaultRestSec else 0.0
        val idxs = unit ?: emptyList()
        if (idxs.isEmpty()) return fallback
        // superset-meta.js #292：组自带休息 → 每轮正好休那么多
        if (idxs.size > 1) {
            val rest = supersetMeta(entries, idxs, onTarget = true).rest
            if (rest > 0.0) return rest
        }
        var longest = 0.0
        for (idx in idxs) {
            val setRestIdx = setRest?.num("idx")
            val setRestSec = setRest?.num("sec")
            val own = if (setRestIdx != null && setRestSec != null && setRestIdx == idx.toDouble() && setRestSec > 0.0) setRestSec
            else entries?.getOrNull(idx)?.sub("target")?.num("restSec")
            longest = maxOf(longest, if (own != null && own > 0.0) own else fallback)
        }
        return longest
    }

    /**
     * supersetFlow.js:111-119 warmupRestSecFor——ramp 组之间的休息读 target.warmupRestSec；
     * 最后一个 ramp 组进第一个正式组的那次休息是 workRestSec（看的是下一个「未完成」组），
     * 无该字段或正式组一律 workRestSec（isWarmupRow 兼容 legacy warmup 布尔，见 WorkoutModel）。
     */
    fun warmupRestSecFor(entry: JsonObject?, setIdx: Int, workRestSec: Double): Double {
        val sets = entry?.arr("sets") ?: emptyList()
        val set = sets.getOrNull(setIdx) as? JsonObject
        if (set == null || !WorkoutModel.isWarmupRow(set)) return workRestSec
        val next = sets.drop(setIdx + 1)
            .firstOrNull { s -> !jsTruthy((s as? JsonObject)?.get("done")) } as? JsonObject
        if (next == null || !WorkoutModel.isWarmupRow(next)) return workRestSec
        val own = entry.sub("target").num("warmupRestSec")
        return if (own != null && own > 0.0) own else workRestSec
    }

    /** supersetFlow.js:135 supersetFlowStep 返回形状 { unitDone, roundDone, nextIdx } */
    data class SupersetFlowStepResult(val unitDone: Boolean, val roundDone: Boolean, val nextIdx: Int?)

    /**
     * supersetFlow.js:124-136 supersetFlowStep——超级组内一个刚完成组接下来去哪。跳过已耗尽
     * 成员（含绕回）；轮的边界 = 显示序里最后一位还有活的成员，而不是盲目取组内最大下标
     * （单人多组、短成员先耗尽时不提前收轮）。unit.size <= 1 或 fromIdx 不在组内 → null。
     */
    fun supersetFlowStep(entries: List<JsonObject>?, unit: List<Int>?, fromIdx: Int): SupersetFlowStepResult? {
        if (entries == null || unit == null || unit.size <= 1) return null
        val pos = unit.indexOf(fromIdx)
        if (pos < 0) return null

        if (unit.none { hasWork(entries, it) }) {
            return SupersetFlowStepResult(unitDone = true, roundDone = false, nextIdx = null)
        }

        val wrapped = unit.drop(pos + 1) + unit.take(pos + 1)
        val nextIdx = wrapped.firstOrNull { hasWork(entries, it) }
        val roundDone = unit.drop(pos + 1).none { hasWork(entries, it) }
        return SupersetFlowStepResult(unitDone = false, roundDone = roundDone, nextIdx = nextIdx)
    }
}
