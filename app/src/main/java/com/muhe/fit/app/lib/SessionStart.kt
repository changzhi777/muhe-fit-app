package com.muhe.fit.app.lib

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * session-start.js + session-merge.js 的全量移植（沐禾健身 Android · golden 纪律：JS 纯函数 →
 * Kotlin 纯函数，宿主模型 kotlinx JsonObject 开放形状，每函数注 JS 来源与行号）。
 *
 * 开场流程三入口：
 *   buildSessionEntries  单 routine 建会话条目（session-start.js:118）
 *   buildCombinedEntries 多 routine 合并日建条目 + rid 戳（session-merge.js:22）
 *   plannedConfigOf      设置面板按「计划」开（session-start.js:88）
 *
 * 依赖闭包随行移植（main/ 已有面直接复用）：
 *   history.js   buildSets / buildWorkSets / seedSideFromLast / insertWarmupRow / rungDown /
 *                barFloor / lastEntryFor(+lastEntryIn+entryIn) / applyIntensifierPlan /
 *                applyFailurePlan / shapeRows / MAX_PLANNED_WARMUPS
 *   dumbbells.js historyAs 闭包（workoutAs / entryAs / scaleRow / meaningFactor / dbLoadFor /
 *                dbLoadOf / isOneArm / bellsIn / entryDbLoad / tidy / isMap）——Dumbbells.kt 对应
 *                面是 private 不可跨文件，本文件复刻并逐行注 JS 行号
 *   plates.js    dropGrid / loadKindFor(+loadKindOf) / baseWeightFor / rowLoad / plateStack /
 *                inventoryFor / ownsPlates / sizesOf / PLATE_SIZES / UNCOMMON / DEFAULT_PAIRS
 *                / SINGLE_EQ / Q
 *   bar.js       barWeightFor / defaultBarWeight / usesBar / BAR_EQ / DEFAULT_BAR_KG / DEFAULT_BAR_LB
 *   pyramid.js   PYRAMID_MAX / pyramidTargetAt / pyramidWeightAt（isPyramid 已在 Progression.kt）
 *   incline.js   hasIncline / inclineFrom
 *   workout-model.js nextDropWeight / nextBurstReps / splitBurstReps（isWarmupRow / isSideSet /
 *                makeSideSet / syncSideAggregate 复用 WorkoutModel.kt）
 * 既有复用：History（modeOf/isBw/isPerSide/entryExcluded/entryRoutineId）· Progression
 * （nextPrescription/applyPrescription/weightIncrement/defaultIncrement→Ex/plannedOf/isPyramid）
 * · Backoff（backoffStepOf/applyBackoff）· Ex（eqOf）· Dumbbells（ownedWeightsFor/ownedFloor）。
 *
 * ⚠️ 双轨说明：progression.js:752 applyPrescription 的 step 参数 JS 无类型收窄——可为数字，也可为
 * 哑铃库存数组（#376，热身 ramp 落自有铃）。main/Progression.kt 的 applyPrescription 为 golden
 * 151 例口径已把 step 收窄成 Double，本文件经 RampStep 载体承载两态：applyPrescriptionRamped
 * 逐行复刻同路径、末端 reramp 走 rerampWarmupsRamped（数组分支 ownedFloor）。owned-bells 路径
 * 无 JS 测试（V0.7 勘误口径），以静态对拍为准。
 * ⚠️ 目录数据前提：Ex.catalog 的 eq/n 查询与 JS EXIDX 同构——数据由测试 fixture catalog 段或
 * 运行时 ExercisesRepo 接线注册；未注册 id 与 JS EXIDX 未命中同路（falsy）。
 */
object SessionStart {

    // ═══ 依赖常量 ═══

    // history.js:543（export const，本文件内消费）
    private const val MAX_PLANNED_WARMUPS = 5

    // pyramid.js:7（export const PYRAMID_MAX = 'max'；buildWorkSets 判每组「尽力组」）
    private const val PYRAMID_MAX = "max"

    // bar.js:7 BAR_EQ（手持杠的器材值）
    private val BAR_EQ = setOf("barbell", "olympic barbell", "ez barbell", "smith machine", "trap bar")

    // bar.js:9-10 默认杆重（按器材类型，分单位——真刻度，非 kg 换算）
    private val DEFAULT_BAR_KG = mapOf(
        "barbell" to 20.0, "olympic barbell" to 20.0, "ez barbell" to 10.0,
        "smith machine" to 9.0, "trap bar" to 25.0,
    )
    private val DEFAULT_BAR_LB = mapOf(
        "barbell" to 45.0, "olympic barbell" to 45.0, "ez barbell" to 25.0,
        "smith machine" to 20.0, "trap bar" to 55.0,
    )

    // plates.js:19-21 杆片尺寸清单（最重在前）与「默认假设没有」的非常见尺寸
    private val PLATE_SIZES_KG = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5)
    private val PLATE_SIZES_LB = listOf(45.0, 35.0, 25.0, 15.0, 10.0, 5.0, 2.5, 1.25)
    private val UNCOMMON_KG = setOf(0.5)
    private val UNCOMMON_LB = setOf(15.0, 1.25)

    // plates.js:26 DEFAULT_PAIRS（默认库存每尺寸的对数——「一副架」）
    private const val DEFAULT_PAIRS = 6

    // plates.js:30 SINGLE_EQ（挂单	stack 的器材：配重带/地雷/片装机/雪橇）
    private val SINGLE_EQ = setOf("weighted", "sled machine")

    // plates.js:80 Q（一切以四分之一单位做，1.25/2.5 保持精确整数）
    private const val PLATE_Q = 4

    // dumbbells.js:68 ONE_ARM（单臂词：交替弯举仍两手，只有词面说单臂/单手才算）
    private val ONE_ARM = Regex("""\b(one|single)[- ]?(arm|hand|handed)\b""", RegexOption.IGNORE_CASE)

    /** rungDown / insertWarmupRow / applyPrescription 末段 reramp 的 step 载体：
     *  JS 侧 step 形态两态——数字（增量步长）或哑铃库存数组（#376，rung 落自有铃）。 */
    sealed interface RampStep {
        data class Bells(val ws: List<Double>) : RampStep
        data class Inc(val step: Double) : RampStep
    }

    /** dropGrid 三分支两态返回（JS 函数或数字），nextDropWeight 分流处理。 */
    sealed interface DropGrid {
        data class Fn(val f: (Double) -> Double) : DropGrid
        data class Step(val step: Double) : DropGrid
    }

    /** history.js:531 buildSets 的 options 载体。 */
    data class BuildOptions(
        val step: RampStep? = null,      // 插热身用的步长（该动作自己的 inc 或哑铃库存）
        val rid: String? = null,         // 条目归属 routine（#216：自己的历史优先）
        val useTarget: Boolean = false,  // 排除进行（noProg）：用 routine 自己的数
        val preferLast: Boolean = false, // 自由练：完全复刻上一次
        val planReps: Boolean = false,   // 计划own次数（Settings → 计划会话从哪开）
    )

    /** session-merge.js:31 buildCombinedEntries 的返回 { entries, routineIds, routines }。 */
    data class CombinedResult(
        val entries: List<JsonObject>,
        val routineIds: List<String>,
        val routines: List<JsonObject>,
    )

    // ═══ session-start.js 本体 ═══

    // session-start.js:20 startsFromLast——会话从哪开（Settings → During a workout）。
    // 'plan'（默认）：routine 自己的组×次，历史与渐进策略定重量；'last'：照抄上一次记的次数。
    // 未选过或旧 profile 读作 'plan'（startFrom 缺失 → false）。
    fun startsFromLast(st: JsonObject?): Boolean = st?.str("startFrom") == "last"

    /**
     * session-start.js:30-75 buildPlannedEntry——按会话同款方式建一个计划动作：处方 + 开场行 +
     * 被评判的 target。会话开场、会话中改动作、往计划会话里加/换动作全部经这里，同一动作从
     * 哪里建都不会建出两样。`routine` 是它被计划进的那一个：它自己的历史优先（#216）、它的
     * 策略生效。`noProg` 按排除进行（excluded routine）的方式建——不带处方。
     */
    fun buildPlannedEntry(stored: JsonObject?, cfg: JsonObject, routine: JsonObject?, noProg: Boolean = false): JsonObject {
        // dumbbells.js：今天这个动作的哑铃重量语义（'as'|'each'|'total'）。它 progresses 的
        // 历史按该语义读——从"40 总重"切"每只"开在 20，不在 40。只有两种显式语义间转换。
        val meaning = dbLoadFor(stored, cfg)
        val st = historyAs(stored, cfg.get("id"), meaning)
        // 'plan' 留在条目上，纯为让 workout 解释它选的数。
        val plan = if (noProg) Js.obj("policy" to JsonPrimitive("off"), "kind" to JsonPrimitive("off"))
        else Progression.nextPrescription(st, cfg, routine)
        // 热身 ramp 与处方都对齐该动作自己的增量（1.25kg 片存在），不用单位默认；计时动作
        // 的 inc 是秒——可选负重保持默认增量。
        val mode = History.modeOf(cfg)
        val stUnit = st?.str("unit")
        val step = if (mode == "reps") Progression.weightIncrement(cfg, stUnit)
        else Ex.defaultIncrement(cfg.get("id"), stUnit)
        val planReps = !startsFromLast(st)
        // 热身 ramp：profile 列了自有哑铃时落自有铃（issue #376）——梯级落最重的下属铃，
        // 不落在架子上没有的中间重量。JS: (modeOf==='reps' && owned) || step。
        val ramp: RampStep = if (mode == "reps") {
            Dumbbells.ownedWeightsFor(st, cfg)?.let { RampStep.Bells(it) } ?: RampStep.Inc(step)
        } else RampStep.Inc(step)
        // 退阶组从顶组按动作自己的步长下退（backoff.js）；计时动作（mode!=reps）不退。
        val backoffStep = if (mode == "reps" && Backoff.backoffStepOf(cfg, stUnit ?: "kg") != 0.0) step else 0.0
        val built = applyPrescriptionRamped(
            buildSets(st, cfg, BuildOptions(
                step = ramp, rid = routine?.str("id"),
                useTarget = plan.str("kind") == "off", planReps = planReps,
            )),
            plan, ramp, barFloor(st, cfg.get("id")),
        )
        val rows = if (backoffStep != 0.0) Backoff.applyBackoff(built, backoffStep) else built
        val sets = applyIntensifierPlan(rows, cfg, dropGrid(st, cfg))
        var target = Js.spread(cfg)
        // 会话开场时盖章——它被记下的语义永远跟着它（dumbbells.js）。
        if (meaning != "as") target = Js.with(target, "dbLoad", JsonPrimitive(meaning))
        plan.numN("weight")?.let { target = Js.with(target, "weight", jn(it)) }
        plan.numN("reps")?.let { target = Js.with(target, "reps", jn(it)) }
        plan.numN("sec")?.let { target = Js.with(target, "sec", jn(it)) }
        plan.numN("sets")?.let { target = Js.with(target, "sets", jn(it)) }
        // Triple progression 的每组目标（progression.js readSession 按它评分）。只有处方定过
        // 它的会话带一个：从复制 target 捡来一个的计划不能被旧一天的目标评分。
        val rowReps = plan.arr("rowReps")
        target = if (rowReps != null) Js.with(target, "rowReps", JsonArray(rowReps)) else Js.without(target, "rowReps")
        // 退阶组建组用的步长，留在会话上——读回时（progression.js readSession）即使计划变了
        // 也让每组对齐它自己的重量。
        target = if (backoffStep != 0.0) Js.with(target, "backoffStep", jn(backoffStep)) else Js.without(target, "backoffStep")
        // 从上一次会话的次数开场的行（"Your last session"，且没有定次数的策略）——卡片好说
        // 数从哪来。只在 true 时写，完成 workout 里永不保存。
        // JS `s.r !== cfg.reps` 严格不等：按数值比较（JS 15 === 15.0；undefined !== 数字）。
        val carried = !planReps && plan.str("kind") != "off" && plan.numN("reps") == null && mode == "reps" &&
            rows.any { s -> !WorkoutModel.isWarmupRow(s) && s.numN("r") != cfg.num("reps") }
        // 'planned' 是 routine 要的（与处方挪过的 target 分开），下个会话好分辨编辑过的计划
        // 与进步了的（nextPrescription）。
        return Js.spread(
            Js.obj(
                "target" to target,
                "plan" to plan,
                "sets" to JsonArray(sets),
                "planned" to Progression.plannedOf(cfg),
            ),
            if (carried) Js.obj("carried" to JsonPrimitive(true)) else Js.emptyObj(),
        )
    }

    /**
     * session-start.js:88-98 plannedConfigOf——运行中会话的动作设置面板按什么开：条目的
     * target，换回 routine 计划的组数与次数（range、秒）。今天的 target 是处方——双进阶目标、
     * 自重爬升、次数上限加的组都可能把它挪离计划；按今天的数开，一个「什么都没改」的保存会
     * 把它们盖成计划（#275）。重量留今天的（杠上的那一个）；早于计划盖章的条目只有 target。
     */
    fun plannedConfigOf(entry: JsonObject?): JsonObject {
        var out = Js.spread(entry?.sub("target"))
        val planned = entry?.sub("planned") ?: return out
        for (key in listOf("sets", "reps", "repsMin", "sec")) {
            val v = planned.get(key)
            out = if (v != null && v !is JsonNull) Js.with(out, key, v)
            // 没有下限的计划不保留下限。
            else if (key == "repsMin") Js.without(out, "repsMin")
            else out
        }
        return out
    }

    /**
     * session-start.js:111 builtOutOfProgression——条目是否按排除进行的方式重建（buildPlannedEntry
     * 的 noProg）。entry.noProg 两个来源：deload/康复 routine 开场冻在每条上（无处方建）；动作
     * ⋯ 菜单手动设（只停计数，行仍按处方）。只有 routine 本身被排除的条目才按这个方式重建。
     */
    fun builtOutOfProgression(entry: JsonObject?, routine: JsonObject?): Boolean =
        entry?.bool("noProg") == true && routine?.bool("excludeFromProgression") == true

    /**
     * session-start.js:118-126 buildSessionEntries——裸数组返回会话条目。"排除进行"已按条目
     * （entry.noProg，只在 true 时写）而非包装旗——混进真训练的康复 routine 只排除它自己的
     * 动作。合并助手（session-merge.js）戳 entry.rid，单 routine 与合并路径共用本构造不改。
     */
    fun buildSessionEntries(st: JsonObject?, r: JsonObject?): List<JsonObject> {
        // 处方在开场时应用——走到杠前屏幕上就是对的重量，而不是事后被告知。
        val noProg = r?.bool("excludeFromProgression") == true
        val exs = r?.arr("ex") ?: emptyList()
        return exs.mapNotNull { el ->
            val cfg = el as? JsonObject ?: return@mapNotNull null   // JS ex 元素恒对象；非对象按防御滤
            val built = buildPlannedEntry(st, cfg, r, noProg)
            Js.spread(
                Js.obj("id" to cfg.get("id"), "sg" to cfg.get("sg")),
                built,
                if (noProg) Js.obj("noProg" to JsonPrimitive(true)) else Js.emptyObj(),
            )
        }
    }

    // ═══ session-merge.js 本体 ═══

    /**
     * session-merge.js:22-32 buildCombinedEntries——按 routine id 有序列表建一个会话的条目：
     * 解析 + 滤掉不存在的 routine，按 id 去重（先者胜）；按列表序拼接各 routine 的条目；
     * 每条戳 entry.rid。返回的 routineIds / routines 是解析去重后的那份——调用方存的就是建
     * 的。单 routine 开场（一个元素的列表）与自由练（空列表 → 无条目无 rid）同样经这里。
     */
    fun buildCombinedEntries(st: JsonObject?, routineIds: List<String>?): CombinedResult {
        // JS: [].concat(routineIds ?? []).filter(id => id && !seen.has(id) && seen.add(id))
        //   —— falsy id（含 null 元素）过滤 + 首见保留；distinct 保首序等价。
        val routines = (routineIds ?: emptyList())
            .filter { it.isNotEmpty() }
            .distinct()
            .mapNotNull { id ->
                st?.arr("routines")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("id") == id }
            }
        val entries = routines.flatMap { r ->
            buildSessionEntries(st, r).map { e -> Js.with(e, "rid", r.get("id")) }
        }
        return CombinedResult(entries, routines.mapNotNull { it.str("id") }, routines)
    }

    /**
     * session-merge.js:40-44 deriveSessionName——合并 workout 的名字，按合并序的 routine 名
     * （ENG-12 规则）：1–3 个 → " + " 连接；4+ 个 → 前两个 + "+ N more"。空列表 → null
     * （已存/活动会话不可达态）。
     */
    fun deriveSessionName(names: List<String>): String? {
        if (names.isEmpty()) return null
        if (names.size <= 3) return names.joinToString(" + ")
        return "${names[0]} + ${names[1]} + ${names.size - 2} more"
    }

    // ═══ history.js：开场闭包（buildSets 系 + lastEntryFor 系） ═══

    /**
     * history.js:531-541 buildSets——计划动作开场行：工作组 + routine 要的热身（cfg.warmupSets，
     * 默认 0，旧计划行为不变）。热身用 insertWarmupRow 逐个叠——与「加热身组」按钮同一条
     * ramp：每行把到工作重量的差距减半（50%/75%/87.5%），杠铃动作永不低于杆（barFloor）。
     * options.step 是调用方传的动作步长。
     */
    fun buildSets(S: JsonObject?, cfg: JsonObject, options: BuildOptions = BuildOptions()): List<JsonObject> {
        val rows = buildWorkSets(S, cfg, options)
        // JS: Math.max(0, Math.min(5, Math.round(cfg.warmupSets) || 0))
        val warm = max(0.0, min(MAX_PLANNED_WARMUPS.toDouble(), cfg.num("warmupSets")?.let { jsRound(it) } ?: 0.0)).toInt()
        if (warm == 0) return rows
        val mode = History.modeOf(cfg)
        var out = rows
        val floor = barFloor(S, cfg.get("id"))
        for (i in 0 until warm) out = insertWarmupRow(out, mode, cfg, options.step ?: RampStep.Inc(2.5), floor)
        return out
    }

    /**
     * history.js:550 barFloor——一个动作最轻的热身：它用的杆（bar.js barWeightFor，含自设杆
     * 重），没有杆的动作 0。55lb 推举的一半是 25lb——装不上 45lb 的杆，所以热身梯级永不
     * 低于它。JS: barWeightFor(S, exId) || 0。
     */
    fun barFloor(S: JsonObject?, exId: JsonElement?): Double = barWeightFor(S, exId) ?: 0.0

    /**
     * history.js:358-361 lastEntryFor——该动作上一次（计入的）条目。rid 有值先找该 routine
     * 自己的（#216），没有再找任一 routine 的。entryIn / lastEntryIn 为 :348 / :363-393。
     */
    fun lastEntryFor(S: JsonObject?, exId: String?, rid: String? = null): JsonObject? {
        if (!rid.isNullOrEmpty()) {
            val own = lastEntryIn(S, exId, rid)
            if (own != null) return own
        }
        return lastEntryIn(S, exId, null)
    }

    // history.js:348 entryIn——workout 里该动作的条目（rid 有值时只认该 routine 的）。
    private fun entryIn(w: JsonObject, exId: String?, rid: String?): JsonObject? =
        (w.arr("entries") ?: emptyList()).mapNotNull { it as? JsonObject }
            .firstOrNull { e -> exId != null && e.str("id") == exId && (rid == null || History.entryRoutineId(w, e) == rid) }

    // history.js:363-393 lastEntryIn——逆序扫 workouts，跳过不计入的会话（计划 deload、混进
    // 真训练的康复块 entryExcluded）与热身行——它们既不该播种行数也不该把 3x5 读成 5 组。
    // 返回 { d, sets(已完成工作行), target, rid?, planned? }。
    private fun lastEntryIn(S: JsonObject?, exId: String?, rid: String?): JsonObject? {
        val workouts = S?.arr("workouts")?.mapNotNull { it as? JsonObject } ?: emptyList()
        for (i in workouts.indices.reversed()) {
            val w = workouts[i]
            val en = entryIn(w, exId, rid) ?: continue
            if (History.entryExcluded(w, en)) continue
            val done = (en.arr("sets") ?: emptyList()).mapNotNull { it as? JsonObject }
                .filter { jsTruthy(it.get("done")) && !WorkoutModel.isWarmupRow(it) }
            if (done.isNotEmpty()) {
                val slot = History.entryRoutineId(w, en)
                return Js.spread(
                    Js.obj(
                        "d" to w.get("d"),
                        "sets" to JsonArray(done),
                        "target" to (en.get("target") ?: JsonNull),
                    ),
                    if (slot != null) Js.obj("rid" to JsonPrimitive(slot)) else Js.emptyObj(),
                    if (en.get("planned") != null) Js.obj("planned" to en.get("planned")!!) else Js.emptyObj(),
                )
            }
        }
        return null
    }

    /**
     * history.js:552-656 buildWorkSets——buildSets 的行体。逐分支（cardio / time / reps 含
     * pyramid 与单侧）逐行对照 JS；`|| n` 的 0-fallback 用 takeIf{it != 0.0} 保真；JS
     * undefined 传播（prev.min 缺失时键省略、不跳 fallback）由 Js.obj null 滤键承载。
     */
    private fun buildWorkSets(S: JsonObject?, cfg: JsonObject, options: BuildOptions): List<JsonObject> {
        val preferLast = options.preferLast
        val useTarget = options.useTarget
        // lastEntryFor 已跳过不计入的条目（deload / 康复块）——行从上一次*计入*的会话播种，
        // 本函数不再自己滤历史。options.rid 是行所属 routine：它的上次会话优先（#216）。
        val last = lastEntryFor(S, cfg.str("id") ?: "", options.rid)
        // JS: Math.max(1, cfg.sets || 1)
        val n = max(1.0, cfg.num("sets")?.takeIf { it != 0.0 } ?: 1.0).toInt()
        val mode = History.modeOf(cfg)
        val sets = mutableListOf<JsonObject>()
        // deload routine 用自己的处方，不把常规会话的值带进 workout；其他计划会话从历史读
        // 重量（次数也读，除非计划own它——见 planReps）。
        fun prevAt(i: Int): JsonObject? =
            if (!useTarget && last != null) {
                (last.arr("sets")?.getOrNull(i) as? JsonObject) ?: (last.arr("sets")?.lastOrNull() as? JsonObject)
            } else null
        // options.planReps：计划会话按 routine 自己的次数开，历史只定重量（Settings →
        // "计划会话从哪开"）。没有它每行都照抄上一次的次数（#275）。自由练没有计划可own、
        // 只复刻你练过的（preferLast）。
        val planReps = options.planReps && !preferLast && (cfg.numN("reps") ?: 0.0) > 0

        if (mode == "cardio") {
            for (i in 0 until n) {
                val prev = prevAt(i)
                // JS: prev ? prev.min : (cfg.min || 20)——prev 存在即取其键（undefined 传播）。
                val minEl: JsonElement? = if (prev != null) prev.get("min")
                else cfg.num("min")?.takeIf { it != 0.0 }?.let { jn(it) } ?: jn(20.0)
                val speedEl: JsonElement? = if (prev != null) prev.get("speed")
                else cfg.num("speed")?.takeIf { it != 0.0 }?.let { jn(it) } ?: jn(8.0)
                sets.add(Js.spread(
                    Js.obj("min" to minEl, "speed" to speedEl),
                    inclineFrom(prev),
                    Js.obj("done" to JsonPrimitive(false)),
                ))
            }
            return sets
        }
        if (mode == "time") {
            // 计时没有次数可对半——"单侧"是整个 hold 每边做一次：计划组数翻倍（2×30s 变
            // 2 左 + 2 右，仍各 30s），不是把时长对半。平行行带 `side` 标签，不是次数用的
            // L/R 子行对（isSideSet）——每行只有一个时长可记，不是两个并排值。
            val count = if (History.isPerSide(cfg)) n * 2 else n
            for (i in 0 until count) {
                // 只带来自计时组的旧值——reps 切 time 不得拿次数播种时长。
                val prev = prevAt(i)
                val carried = prev?.takeIf { (it.numN("sec") ?: 0.0) > 0 }
                var row = Js.obj(
                    "sec" to jn(carried?.numN("sec") ?: (cfg.num("sec")?.takeIf { it != 0.0 } ?: 45.0)),
                    "w" to jn(carried?.numOr("w") ?: cfg.numOr("weight")),
                    "done" to JsonPrimitive(false),
                )
                if (History.isPerSide(cfg)) row = Js.with(row, "side", JsonPrimitive(if (i % 2 == 0) "L" else "R"))
                sets.add(row)
            }
            return sets
        }
        val conf = S?.sub("exWeights")?.sub(cfg.str("id") ?: "")
        for (i in 0 until n) {
            val prev = prevAt(i)
            val usable = prev?.takeIf { (it.numN("r") ?: 0.0) > 0 }
            // 重量读该 routine 上次练的（没有的 routine 读任一次）。确认过的工作重量按动作
            // 键（exWeights），只在没会话可读时兜底：先读它会把轻日的数给重日（#216）。
            // 渐进策略反正会覆盖（applyPrescription）。deload 用 routine 的目标重量；没设过
            // （weight 0）回读上一次常规负重，不处方一根空杆。
            val lastRegular = last?.arr("sets")?.getOrNull(i) as? JsonObject
                ?: last?.arr("sets")?.lastOrNull() as? JsonObject
            val w: Double? = if (useTarget) {
                val cw = cfg.numN("weight")
                if (cw != null && cw > 0) cw
                else lastRegular?.takeIf { (it.numN("r") ?: 0.0) > 0 }?.numN("w") ?: cw
            } else if (usable != null) {
                usable.numN("w")
            } else {
                conf?.numN("w")?.takeIf { it > 0 } ?: cfg.numN("weight")
            }
            // JS: { w, r: (planReps || !usable) ? cfg.reps : usable.r, done: false }
            //     —— cfg.reps 可能 undefined → 键省略（Js.obj 滤 null）。
            var row = Js.spread(
                if (w != null) Js.obj("w" to jn(w)) else Js.emptyObj(),
                Js.obj(
                    "r" to (if (planReps || usable == null) cfg.get("reps") else usable.get("r")),
                    "done" to JsonPrimitive(false),
                ),
            )
            // Pyramid 组：计划own每组的目标；尽力组开在上次同组做到的数——要超的数已在。
            if (Progression.isPyramid(cfg)) {
                val pyr = cfg.arr("pyramid") ?: emptyList()
                val pyrTarget = pyramidTargetAt(pyr, i)
                // pyramid 永不渐进，计划会话按 useTarget 建它、usable 为 null：尽力组直接读
                // 上次同组。
                val seed = usable ?: lastRegular?.takeIf { (it.numN("r") ?: 0.0) > 0 }
                if (pyrTarget == JsonPrimitive(PYRAMID_MAX)) {
                    row = when {
                        seed == null -> Js.with(row, "r", jn(0.0))
                        seed.get("r") != null -> Js.with(row, "r", seed.get("r")!!)
                        else -> Js.without(row, "r")   // JS: row.r = seed.r（undefined）→ 键省略
                    }
                    row = Js.with(row, "max", JsonPrimitive(true))
                } else {
                    row = Js.with(row, "r", pyrTarget)
                }
                // 每组重量是计划给该组的，否则上次同组举的。平铺 weight 不是 pyramid 字段、
                // 编辑器不显示——只在第一次会话兜底（#445）。自由练有可抄的照抄（preferLast）。
                val planned = pyramidWeightAt(cfg, i)
                row = if (planned > 0 && !(preferLast && usable != null)) {
                    Js.with(row, "w", jn(planned))
                } else if (useTarget && seed != null) {
                    if (seed.get("w") != null) Js.with(row, "w", seed.get("w")!!) else Js.without(row, "w")
                } else row
            }
            // 单侧动作每边各记（issue #60）：行拆 L/R，各播一半次数、同重量。上一次本身就是
            // 单侧组时带它的两边——不对称得以延续：重量总是两边都带，次数只在计划不own时带。
            if (History.isPerSide(cfg)) {
                sets.add(
                    if (usable != null && WorkoutModel.isSideSet(usable)) seedSideFromLast(row, usable, planReps)
                    else WorkoutModel.makeSideSet(row)
                )
            } else sets.add(row)
        }
        return sets
    }

    /**
     * history.js:645-653 seedSideFromLast——从上一次的单侧组播新单侧行：每边同次数/重量，
     * 全不 done，effort 不带（每次会话新记）。上一行不是单侧时回落均分。planReps 时次数是行
     * 自己的——计划的总数、按 makeSideSet 同款拆——只带重量。
     */
    private fun seedSideFromLast(row: JsonObject, prev: JsonObject, planReps: Boolean): JsonObject {
        val base = WorkoutModel.makeSideSet(row)
        if (!WorkoutModel.isSideSet(prev)) return base
        val baseSides = base.sub("sides")
        fun carry(s: JsonObject?, key: String): JsonObject = Js.obj(
            "w" to jn(s?.numOr("w") ?: 0.0),
            "r" to (if (planReps) baseSides?.sub(key)?.get("r") else jn(s?.numOr("r") ?: 0.0)),
            "done" to JsonPrimitive(false),
        )
        return WorkoutModel.syncSideAggregate(Js.with(base, "sides", Js.obj(
            "L" to carry(prev.sub("sides")?.sub("L"), "L"),
            "R" to carry(prev.sub("sides")?.sub("R"), "R"),
        )))
    }

    /**
     * history.js:1005-1057 insertWarmupRow——在第一工作行前插一行热身：从上一行 ramp（没有
     * 则从 0）。ramp 向下取整到步长：落轻一格没代价，落重一格得先卸片；永不低于 floor
     * （barFloor）、杆更重时也永不超工作重量。上一行已重过工作重量（手编热身在其上）时取原
     * 值——不把过重的数沿块传播。cardio/time/reps 三形态 + 单侧拆分（issue #60，仅 reps 拆）。
     */
    fun insertWarmupRow(rows: List<JsonObject>, mode: String, target: JsonObject, step: RampStep, floor: Double): List<JsonObject> {
        val firstWork = rows.indexOfFirst { !WorkoutModel.isWarmupRow(it) }
        val at = if (firstWork == -1) rows.size else firstWork
        val prev = if (at > 0) rows[at - 1] else null       // 本行 ramp 的来源热身
        val work = if (firstWork != -1) rows[firstWork] else null
        fun rampTo(to: Double): Double {
            val from = prev?.numOr("w") ?: 0.0
            // 没有 ramp 目标：自重、cardio、无负重的计时。
            if (!(to > 0)) return 0.0
            // 已在或超工作重量（热身被手编到上面）：取原值，不让过重的数往下传——热身永不
            // 重于它服务的那组。
            if (to <= from) return to
            // 向下取整到步长；永不低于 floor（barFloor）、永不超工作重量。
            return min(to, max(max(0.0, floor), rungDown(from + (to - from) / 2, step)))
        }
        val warm: JsonObject = when (mode) {
            "cardio" -> {
                // JS: prev ? prev.min : (work ? work.min : (target.min || 20))——undefined 传播保真。
                val minEl: JsonElement? = if (prev != null) prev.get("min")
                else if (work != null) work.get("min")
                else target.num("min")?.takeIf { it != 0.0 }?.let { jn(it) } ?: jn(20.0)
                val speedEl: JsonElement? = if (prev != null) prev.get("speed")
                else if (work != null) work.get("speed")
                else target.num("speed")?.takeIf { it != 0.0 }?.let { jn(it) } ?: jn(8.0)
                Js.spread(
                    Js.obj("min" to minEl, "speed" to speedEl),
                    inclineFrom(prev ?: work),
                    Js.obj("done" to JsonPrimitive(false), "phase" to JsonPrimitive("warmup"), "warmup" to JsonPrimitive(true)),
                )
            }
            "time" -> {
                val secEl: JsonElement? = if (prev != null) prev.get("sec")
                else if (work != null) work.get("sec")
                else target.num("sec")?.takeIf { it != 0.0 }?.let { jn(it) } ?: jn(45.0)
                Js.obj(
                    "sec" to secEl,
                    "w" to jn(rampTo(if (work != null) work.numOr("w") else target.numOr("weight"))),
                    "done" to JsonPrimitive(false), "phase" to JsonPrimitive("warmup"), "warmup" to JsonPrimitive(true),
                )
            }
            else -> {
                // JS: r: work ? work.r : (prev ? prev.r : target.reps)——undefined 传播保真。
                val rEl: JsonElement? = if (work != null) work.get("r")
                else if (prev != null) prev.get("r")
                else target.get("reps")
                Js.obj(
                    "w" to jn(rampTo(if (work != null) work.numOr("w") else target.numOr("weight"))),
                    "r" to rEl,
                    "done" to JsonPrimitive(false), "phase" to JsonPrimitive("warmup"), "warmup" to JsonPrimitive(true),
                )
            }
        }
        // 单侧动作的热身也单侧（issue #60）：按服务的工作组同款拆——makeSideSet 对半合次数；
        // ramped 重量两边共享。仅 reps 拆——计时 hold 与 cardio 热身无单侧。makeSideSet spread
        // 该行，phase/warmup 带到 side set 上。
        val warmRow = if (mode == "reps" && History.isPerSide(target)) WorkoutModel.makeSideSet(warm) else warm
        val next = rows.toMutableList()
        next.add(at, warmRow)
        return next
    }

    // history.js:981 rungDown——x 向下取整到 step 的梯级；step 是哑铃库存数组时落 ownedFloor。
    // 本闭包两处消费（rampTo / rerampWarmupsRamped）的 step 恒为 RampStep 两态；rerampWarmups
    // 的 JS 数组字面量调用方（会话内按钮）不属本文件。
    private fun rungDown(x: Double, step: RampStep): Double = when (step) {
        is RampStep.Bells -> Dumbbells.ownedFloor(step.ws, x)
        is RampStep.Inc -> floor(x / step.step) * step.step
    }

    /**
     * history.js:661-663 applyIntensifierPlan——先 shapeRows（按计划盖 intensifier、预填
     * drops/clusters）再 applyFailurePlan（lastToFailure 标记）。公开：buildPlannedEntry 与
     * 运行中建动作同走此步。
     */
    fun applyIntensifierPlan(sets: List<JsonObject>, cfg: JsonObject, grid: DropGrid): List<JsonObject> =
        applyFailurePlan(shapeRows(sets, cfg, grid), cfg)

    /**
     * history.js:672-681 applyFailurePlan——"末组到力竭"（cfg.lastToFailure，只在开时写）：
     * 新建动作的最后一工作行标为力竭（Greyskull 的 AMRAP 末组）。只有那一行、只有未记的行；
     * 其余行原样——没旗的计划建的行与从前一致。计时单侧 hold 是一 L 一 R 读作一组：两边
     * 都算那组。
     */
    private fun applyFailurePlan(sets: List<JsonObject>, cfg: JsonObject?): List<JsonObject> {
        if (cfg?.bool("lastToFailure") != true || History.modeOf(cfg) == "cardio") return sets
        var last = -1
        sets.forEachIndexed { i, s -> if (!WorkoutModel.isWarmupRow(s)) last = i }
        val from = if (last > 0 && sets[last].str("side") == "R" && sets[last - 1].str("side") == "L") last - 1 else last
        if (last < 0) return sets
        // JS: !s.done —— done 缺失视为未记，照样盖。
        return sets.mapIndexed { i, s ->
            if (i >= from && i <= last && jsTruthy(s.get("done")) != true) Js.with(s, "failure", JsonPrimitive(true)) else s
        }
    }

    /**
     * history.js:682-726 shapeRows——按 cfg.intensifier 盖计划设计的组形。dropset：逐行（或
     * 单侧行的每边）盖一条按 pct% 递降的 drops 链，各按该行自己的次数；单侧先盖两边再 resync
     * 聚合（issue #60）。restpause：整段替换为「热身 + 单个 rest-pause 工作组」两个行——
     * 协议不是做满配置组数；工作行次数是总数，clusters 是它的完整拆解（只读显示，不额外计
     * 量）。
     */
    private fun shapeRows(sets: List<JsonObject>, cfg: JsonObject?, grid: DropGrid): List<JsonObject> {
        val intens = cfg?.sub("intensifier")
        val kind = intens?.str("type")
        if (kind != "dropset" && kind != "restpause") return sets
        if (kind == "dropset") {
            // JS: Math.max(1, Math.round(cfg.intensifier.count) || 1)
            val count = max(1.0, intens.num("count")?.let { jsRound(it) }?.takeIf { it != 0.0 } ?: 1.0).toInt()
            val pct = intens.numN("pct")
            fun withDrops(row: JsonObject): JsonObject {
                val drops = mutableListOf<JsonObject>()
                var w = row.numOr("w")
                repeat(count) {
                    w = nextDropWeight(w, pct, grid)
                    drops.add(Js.obj("w" to jn(w), "r" to row.get("r")))
                }
                return Js.with(Js.with(row, "type", JsonPrimitive("dropset")), "drops", JsonArray(drops))
            }
            return sets.map { s ->
                if (WorkoutModel.isWarmupRow(s)) s
                // 单侧组逐边降（issue #60）：先盖两边，再 resync 聚合。
                else if (WorkoutModel.isSideSet(s)) {
                    val sides = s.sub("sides")
                    WorkoutModel.syncSideAggregate(Js.with(s, "sides", Js.obj(
                        "L" to withDrops(sides?.sub("L") ?: Js.emptyObj()),
                        "R" to withDrops(sides?.sub("R") ?: Js.emptyObj()),
                    )))
                } else withDrops(s)
            }
        }
        // rest-pause 就是两组，不是配置组数个：热身按该动作配置次数，之后一个 rest-pause
        // 工作组。计划它即替换 buildSets 建的行，而非逐行盖戳。
        val restSec = max(5.0, intens.num("restSec")?.takeIf { it != 0.0 } ?: 15.0)
        // JS: Math.max(1, Math.round(cfg.intensifier.totalReps) || 1)
        val totalReps = max(1.0, intens.num("totalReps")?.let { jsRound(it) }?.takeIf { it != 0.0 } ?: 1.0)
        // JS: (sets.find(s => !isWarmupRow(s)) || sets[0] || {}).w || 0
        val w = (sets.firstOrNull { !WorkoutModel.isWarmupRow(it) } ?: sets.firstOrNull())?.numOr("w") ?: 0.0
        val warmup = Js.obj(
            "w" to jn(w),
            "r" to jn(max(1.0, cfg.num("reps")?.let { jsRound(it) }?.takeIf { it != 0.0 } ?: 1.0)),
            "done" to JsonPrimitive(false), "phase" to JsonPrimitive("warmup"),
        )
        val work = Js.obj(
            "w" to jn(w), "r" to jn(totalReps),
            "done" to JsonPrimitive(false), "type" to JsonPrimitive("restpause"),
            "clusters" to JsonArray(splitBurstReps(totalReps).map { r -> Js.obj("r" to jn(r), "restSec" to jn(restSec)) }),
        )
        if (History.isPerSide(cfg)) {
            val source = sets.firstOrNull { !WorkoutModel.isWarmupRow(it) }
            // 配置总数盖两边；奇数总数也保。
            fun side(key: String, reps: Double): JsonObject {
                // JS: source?.sides?.[key]?.w ?? w —— ?? 只认 null/undefined。
                val sw = source?.sub("sides")?.sub(key)?.numN("w")
                return Js.obj(
                    "w" to jn(sw ?: w), "r" to jn(reps),
                    "done" to JsonPrimitive(false), "type" to JsonPrimitive("restpause"),
                    "clusters" to JsonArray(splitBurstReps(reps).map { r -> Js.obj("r" to jn(r), "restSec" to jn(restSec)) }),
                )
            }
            // 热身也单侧（issue #60）：makeSideSet 按工作组同款拆合计数。
            return listOf(
                WorkoutModel.makeSideSet(warmup),
                WorkoutModel.syncSideAggregate(Js.with(work, "sides", Js.obj(
                    "L" to side("L", ceil(totalReps / 2)),
                    "R" to side("R", floor(totalReps / 2)),
                ))),
            )
        }
        return listOf(warmup, work)
    }

    // ═══ progression.js:752-819 applyPrescription 的 ramp 感知双轨 ═══
    //
    // 主体与 main/Progression.kt applyPrescription 逐行同（唯一差异：step 载体 RampStep，两处
    // 末端 rerampWarmups 走本文件 rerampWarmupsRamped 承载哑铃库存数组 #376）。细节注 JS 行号。

    private fun applyPrescriptionRamped(sets: List<JsonObject>, p: JsonObject?, step: RampStep, floor: Double): List<JsonObject> {
        // 首次会话没东西可处方，除 triple progression 的开环起点。
        if (p == null || p.str("kind") == "off" || (p.str("kind") == "first" && p.arr("rowReps")?.isNotEmpty() != true)) return sets
        var out = sets.map { s ->
            // 永不改已记的组（被 tick 的热身漏进来会是数据损失）。处方对着工作行；开放热身只
            // 跟次数或它落定的 hold（insertWarmupRow 在处方开口前已抄过）；重量最后重 ramp。
            if (jsTruthy(s.get("done"))) return@map s
            if (WorkoutModel.isWarmupRow(s)) {
                if (p.numN("reps") != null && s.numN("r") != null) return@map Js.with(s, "r", jn(p.numN("reps")!!))
                if (p.numN("sec") != null && s.numN("sec") != null) return@map Js.with(s, "sec", jn(p.numN("sec")!!))
                return@map s
            }
            if (WorkoutModel.isSideSet(s)) {
                val sides = s.sub("sides")
                fun sideRow(side: JsonObject?): JsonObject {
                    if (side?.bool("done") == true) return side
                    var o = side ?: Js.emptyObj()
                    p.numN("weight")?.let { o = Js.with(o, "w", jn(it)) }
                    p.numN("reps")?.let { o = Js.with(o, "r", jn(it / 2)) }
                    return o
                }
                return@map WorkoutModel.syncSideAggregate(
                    Js.with(s, "sides", Js.obj("L" to sideRow(sides?.sub("L")), "R" to sideRow(sides?.sub("R"))))
                )
            }
            var o = s
            p.numN("weight")?.let { o = Js.with(o, "w", jn(it)) }
            p.numN("reps")?.let { o = Js.with(o, "r", jn(it)) }
            p.numN("sec")?.let { o = Js.with(o, "sec", jn(it)) }
            o
        }
        // 定了组数的策略可以让列表长——自重渐进加一组，杠铃加一片。只向上长，只抄已有行：
        // 进行中的会话不得丢已记的组（progression.js:782-802）。
        val pSets = p.numN("sets")
        if (pSets != null && pSets > 0) {
            val workRows = out.filter { !WorkoutModel.isWarmupRow(it) }
            if (pSets > workRows.size) {
                // 全热身条目没有工作行可播种——长热身抄既发明工作又死循环。条目原样。
                if (workRows.isEmpty()) return rerampWarmups(out, step, floor)
                val seed = workRows.last()
                // 新追加的行没被练过，不继承种子的已记 drops/clusters——那会发明它没练过的
                // 额外工作。type 保留（那是计划，不是某组记下的）。
                val plainSeed = Js.without(Js.without(seed, "drops"), "clusters")
                while (out.filter { !WorkoutModel.isWarmupRow(it) }.size < pSets.toInt()) {
                    val grown: JsonObject = if (WorkoutModel.isSideSet(seed)) {
                        val arg = LinkedHashMap<String, JsonElement>()
                        (p.numN("weight") ?: seed.numN("w"))?.let { arg["w"] = jn(it) }
                        (p.numN("reps") ?: seed.numN("r"))?.let { arg["r"] = jn(it) }
                        WorkoutModel.makeSideSet(JsonObject(arg))
                    } else Js.with(plainSeed, "done", JsonPrimitive(false))
                    out = out + grown
                }
            }
        }
        // triple 的每组目标：按顺序落到每个开放工作行；已记的行保留所记（progression.js:804-815）。
        val rowReps = p.arr("rowReps")
        if (rowReps != null) {
            var k = -1
            val listOut = out.toMutableList()
            for (i in listOut.indices) {
                if (WorkoutModel.isWarmupRow(listOut[i])) continue
                k++
                val r = rowReps.getOrNull(k)?.numValue()
                if (jsTruthy(listOut[i].get("done")) || r == null || !(r > 0)) continue
                listOut[i] = if (WorkoutModel.isSideSet(listOut[i])) {
                    val sides = listOut[i].sub("sides")
                    fun sideRow(side: JsonObject?): JsonObject {
                        if (side?.bool("done") == true) return side
                        return Js.with(side ?: Js.emptyObj(), "r", jn(r / 2))
                    }
                    WorkoutModel.syncSideAggregate(
                        Js.with(listOut[i], "sides", Js.obj("L" to sideRow(sides?.sub("L")), "R" to sideRow(sides?.sub("R"))))
                    )
                } else {
                    Js.with(listOut[i], "r", jn(r))
                }
            }
            out = listOut
        }
        // 最后：工作行已带最终重量，热身块朝真正要举的重量 ramp（progression.js:818）。
        return rerampWarmups(out, step, floor)
    }

    /**
     * history.js:983-1003 rerampWarmups 的 ramp 感知版（复刻 main/History.rerampWarmups，step
     * 载体 RampStep；JS 数组分支经 rungDown → ownedFloor）。工作行之前的热身块朝工作重量重
     * ramp；已记的热身行是 ramp 的锚点（from）不被改写；单侧热身两边同杆、聚合 resync。
     */
    private fun rerampWarmups(rows: List<JsonObject>, step: RampStep, floor: Double): List<JsonObject> {
        val firstWork = rows.indexOfFirst { !WorkoutModel.isWarmupRow(it) }
        if (firstWork <= 0) return rows
        val target = rows[firstWork].numOr("w")
        if (target <= 0) return rows
        val out = rows.toMutableList()
        var from = 0.0
        for (i in 0 until firstWork) {
            if (jsTruthy(out[i].get("done"))) {
                from = out[i].numOr("w")
                continue
            }
            val w = if (target > from) min(target, max(max(0.0, floor), rungDown(from + (target - from) / 2, step))) else target
            // 单侧热身两边 ramp 同一根杆：设每边重量再 resync 聚合——L/R 行与行自身 w 一致。
            // 平行行只设 w。
            out[i] = if (WorkoutModel.isSideSet(out[i])) {
                val sides = out[i].sub("sides")
                WorkoutModel.syncSideAggregate(Js.with(out[i], "sides", Js.obj(
                    "L" to Js.with(sides?.sub("L") ?: Js.emptyObj(), "w", jn(w)),
                    "R" to Js.with(sides?.sub("R") ?: Js.emptyObj(), "w", jn(w)),
                )))
            } else Js.with(out[i], "w", jn(w))
            from = w
        }
        return out
    }

    // ═══ bar.js ═══

    // bar.js:16-17 defaultBarWeight——器材类型的默认杆重，按单位；不在表上 null。
    private fun defaultBarWeight(eq: String?, unit: String?): Double? =
        (if (unit == "lb") DEFAULT_BAR_LB else DEFAULT_BAR_KG)[eq]

    // bar.js:13 usesBar——该动作（对象或 id）是否用杠。
    private fun usesBar(exOrId: JsonElement?): Boolean = BAR_EQ.contains(Ex.eqOf(exOrId))

    /**
     * bar.js:54-61 barWeightFor——一个动作在 profile 单位下的有效杆重：显式 S.barWeights[exId]
     * 优先——显式 0 是"无杆"（史密斯配重滑车，issue #138）保持 0——否则按杠型的默认。非杠
     * 动作 null。exId 为 id 字符串（EXIDX[id].id === id，键即传入 id）。
     */
    private fun barWeightFor(S: JsonObject?, exOrId: JsonElement?): Double? {
        val eq = Ex.eqOf(exOrId)
        if (!BAR_EQ.contains(eq)) return null
        val idStr = (exOrId as? JsonPrimitive)?.contentOrNull ?: (exOrId as? JsonObject)?.str("id")
        val own = idStr?.let { (S?.sub("barWeights")?.get(it) as? JsonPrimitive)?.doubleOrNull }
        if (own == 0.0) return 0.0
        if (own != null && own > 0) return own
        return defaultBarWeight(eq, S?.str("unit"))
    }

    // ═══ plates.js ═══

    // plates.js:33 unitOf——库存按 profile 单位存放（单位切换不换算）。
    private fun platesUnitOf(S: JsonObject?): String = if (S?.str("unit") == "lb") "lb" else "kg"

    // plates.js:43-44 sizesOf——存着的片清单里尺寸>0 的键值对（_ts 与杂键剔除）。JS 返回
    // Object.entries 保插入序，此处同序映射为 (尺寸, 数量)。
    private fun sizesOf(entry: JsonObject?): List<Pair<Double, Double>> =
        entry?.mapNotNull { (k, v) ->
            val w = k.toDoubleOrNull()
            if (w != null && w.isFinite() && w > 0) Pair(w, platesNum(v)) else null
        } ?: emptyList()

    // plates.js:34 num——数字原样；其余 Number(v) || 0。
    private fun platesNum(v: JsonElement?): Double = (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: 0.0

    // plates.js:48 ownsPlates——该 profile 对它的单位算自己的片，还是上标准组。
    private fun ownsPlates(S: JsonObject?): Boolean = sizesOf(S?.sub("plates")?.sub(platesUnitOf(S))).isNotEmpty()

    /**
     * plates.js:55-72 inventoryFor——该 profile 能装的片，最重在前：[{w, n}]，n 为每尺寸的对
     * 数（single 栈一片对装两片，见 rowLoad）。S.plates[unit] 缺失或没有任何尺寸 → 默认组
     * （PLATE_SIZES 剔除 UNCOMMON）。
     */
    private fun inventoryFor(S: JsonObject?): List<Pair<Double, Double>> {
        val unit = platesUnitOf(S)
        val own = sizesOf(S?.sub("plates")?.sub(unit))
        val out = mutableListOf<Pair<Double, Double>>()
        if (own.isNotEmpty()) {
            for ((w, nRaw) in own) {
                val n = floor(nRaw)
                if (w > 0 && n > 0) out.add(Pair(w, n))
            }
        } else {
            val sizes = if (unit == "lb") PLATE_SIZES_LB else PLATE_SIZES_KG
            val uncommon = if (unit == "lb") UNCOMMON_LB else UNCOMMON_KG
            for (w in sizes) if (!uncommon.contains(w)) out.add(Pair(w, DEFAULT_PAIRS.toDouble()))
        }
        return out.sortedByDescending { it.first }
    }

    // plates.js:91-95 loadKindOf——存着的 loading：'pairs'|'single'|'none'，器材定则 null。
    // 裸字符串是最早构建的存法，仍照读。
    private fun loadKindOf(value: JsonElement?): String? {
        val k = when (value) {
            is JsonObject -> value.get("kind")
            else -> value
        }
        val s = (k as? JsonPrimitive)?.contentOrNull
        return if (s == "pairs" || s == "single" || s == "none") s else null
    }

    /**
     * plates.js:190-204 loadKindFor——该动作的重量怎么装：用户自己的选择（S.loadKind）赢；
     * 否则杠器材 → pairs；自重（isBw 含弹力带）与 "weighted"/雪橇 → single；其余 none。
     * 弹力带的"重量"是张力不是片——isBw 虽算它，器材面仍 none。
     */
    private fun loadKindFor(S: JsonObject?, cfg: JsonObject?): String {
        val exId = cfg?.get("id")
        val own = (exId as? JsonPrimitive)?.contentOrNull?.let { loadKindOf(S?.sub("loadKind")?.get(it)) }
        if (own != null) return own
        if (usesBar(exId)) return "pairs"
        val eq = Ex.eqOf(exId)
        if (eq == "band" || eq == "resistance band") return "none"
        if (SINGLE_EQ.contains(eq) || History.isBw(cfg)) return "single"
        return "none"
    }

    /**
     * plates.js:206-213 baseWeightFor——任何片装上前已有的重量（profile 单位）：杠动作是杆
     * （S.barWeights 覆盖，0 = "无杆"，否则杆型默认），其余是显式覆盖（雪橇自重），再否则 0。
     */
    private fun baseWeightFor(S: JsonObject?, exOrId: JsonElement?): Double {
        val isExObj = exOrId is JsonObject
        if (Ex.eqOf(exOrId) == null && !isExObj) return 0.0
        val idStr = (exOrId as? JsonPrimitive)?.contentOrNull ?: (exOrId as? JsonObject)?.str("id")
        val own = idStr?.let { (S?.sub("barWeights")?.get(it) as? JsonPrimitive)?.doubleOrNull }
        if (own != null && own >= 0) return own
        return defaultBarWeight(Ex.eqOf(exOrId), S?.str("unit")) ?: 0.0
    }

    // plates.js:80-81 Q / q——四分之一单位整数化，1.25 与 2.5 精确。
    private fun q4(w: Double): Int = jsRound(w * PLATE_Q).toInt()

    private data class StackResult(val plates: List<Double>, val missing: Double)

    /**
     * plates.js:114-169 plateStack——从 inv（[{w,n}] 最重在前）给 weight 配片：贪心最重优先
     * 恰好命中（实际装杆的方式）；贪心未中（各一片的自房、奇库存）时最少片数精确命中、平局
     * 偏重片；都无法命中时给最接近的**下方**载荷与缺量。
     *   → { plates: [w…] 最重在前, missing: number }（missing 0 = 精确）
     */
    private fun plateStack(weight: Double, inv: List<Pair<Double, Double>>): StackResult {
        val requested = max(0.0, weight)
        val target = floor(requested * PLATE_Q).toInt()
        if (target == 0) return StackResult(emptyList(), requested)
        val items = inv.filter { it.first > 0 && it.second > 0 }.sortedByDescending { it.first }
        // 1. 贪心
        val greedy = mutableListOf<Double>()
        var left = target
        for (p in items) {
            val unit = q4(p.first)
            if (unit <= 0) continue   // 防护：JS floor(left/0)=Inf 会原样 push 无效小板，属实现噪声
            var k = min(p.second.toInt(), left / unit)
            while (k-- > 0) { greedy.add(p.first); left -= unit }
        }
        // 已到或超出全部持有——贪心已把每片装上、剩下是缺量。下面的搜索只会再找到同样答案、
        // 却要按 target 摆一张表：重量框里一个 9999 的手误会在每次键入时都建它。
        val all = items.sumOf { q4(it.first).toDouble() * it.second.toInt() }
        if (left == 0 || target.toDouble() >= all) return StackResult(greedy, requested - (target - left).toDouble() / PLATE_Q)
        // 2. 最少片数精确命中：f[i][s] = 用尺寸 i.. 拼出 s 的最少片数
        val n = items.size
        val INF = Int.MAX_VALUE / 2
        val f = Array(n + 1) { IntArray(target + 1) { INF } }
        f[n][0] = 0
        for (i in n - 1 downTo 0) {
            val unit = q4(items[i].first)
            val cap = items[i].second.toInt()
            if (unit <= 0) continue
            for (s in 0..target) {
                var best = f[i + 1][s]
                var k = 1
                while (k <= cap && k * unit <= s) {
                    val v = f[i + 1][s - k * unit]
                    if (v + k < best) best = v + k
                    k++
                }
                f[i][s] = best
            }
        }
        // target 处或以下的可达最重和（能命中时即 target 本身）。
        var sum = target
        while (sum > 0 && f[0][sum] >= INF) sum--
        val plates = mutableListOf<Double>()
        var s = sum
        for (i in 0 until n) {
            val unit = q4(items[i].first)
            val cap = items[i].second.toInt()
            if (unit <= 0) continue
            // 仍留最优余量的最大 k → 平局偏重片。
            var k = min(cap, s / unit)
            while (k >= 0) {
                if (f[i + 1][s - k * unit] + k == f[i][s]) {
                    repeat(k) { plates.add(items[i].first) }
                    s -= k * unit
                    break
                }
                k--
            }
        }
        return StackResult(plates, requested - sum.toDouble() / PLATE_Q)
    }

    /**
     * plates.js:220-233 rowLoad——一行组的载荷线：无可装（kind none、无重量）null；重量不超
     * 基座 {kind, barOnly: true}；否则 pairs 的每侧（或 single 的全部）载荷 + 库存缺量。
     *   → { kind, perSide, plates, missing, barOnly }
     */
    private fun rowLoad(kind: String, weight: Double, base: Double, inv: List<Pair<Double, Double>>): JsonObject? {
        if (kind != "pairs" && kind != "single") return null
        if (!(weight > 0)) return null
        val b = max(0.0, base)
        if (weight <= b) return Js.obj(
            "kind" to JsonPrimitive(kind), "barOnly" to JsonPrimitive(true),
            "perSide" to jn(0.0), "plates" to JsonArray(emptyList()), "missing" to jn(0.0),
        )
        val perSide = jsRound((weight - b) / (if (kind == "pairs") 2.0 else 1.0) * 100) / 100
        // 库存按对计。杠每侧用一对里的一片；single 栈一对能装两片——一副 20 在雪橇上是两个 20。
        val stock = if (kind == "single") inv.map { Pair(it.first, it.second * 2) } else inv
        val st = plateStack(perSide, stock)
        return Js.obj(
            "kind" to JsonPrimitive(kind), "barOnly" to JsonPrimitive(false),
            "perSide" to jn(perSide), "plates" to jarrD(st.plates), "missing" to jn(st.missing),
        )
    }

    /**
     * plates.js:246-261 dropGrid——drop 组的下一个重量落点：哑铃动作且列了自有铃 → 最重的
     * 下属铃（21kg 降 20% 是 16.8，架上有 15）；点了片的片装动作 → 那些片在降量之下能拼出的
     * 最重载荷；否则该动作自己的重量步长。
     */
    fun dropGrid(S: JsonObject?, cfg: JsonObject?): DropGrid {
        val owned = Dumbbells.ownedWeightsFor(S, cfg)
        if (owned != null) return DropGrid.Fn { w -> Dumbbells.ownedFloor(owned, w) }
        val kind = loadKindFor(S, cfg)
        if (ownsPlates(S) && (kind == "pairs" || kind == "single")) {
            val base = baseWeightFor(S, cfg?.get("id"))
            val inv = inventoryFor(S)
            val per = if (kind == "pairs") 2.0 else 1.0
            return DropGrid.Fn { w ->
                if (w <= base) (if (base > 0) base else w)
                else {
                    val rl = rowLoad(kind, w, base, inv)
                    val perSide = rl?.numN("perSide") ?: 0.0
                    val missing = rl?.numN("missing") ?: 0.0
                    base + per * max(0.0, perSide - missing)
                }
            }
        }
        return DropGrid.Step(Progression.weightIncrement(cfg, if (S?.str("unit") == "lb") "lb" else "kg"))
    }

    // ═══ pyramid.js ═══

    // pyramid.js:46-48 pyramidTargetAt——第 i 组的目标；加组抄最后一个。
    private fun pyramidTargetAt(list: List<JsonElement>, i: Int): JsonElement =
        if (i < list.size) list[i] else list.last()

    // pyramid.js:76-79 pyramidWeightAt——pyramid 工作组 i 计划的重量，没有 0。会话中加的组
    // 抄最后一组的，与目标同款。
    private fun pyramidWeightAt(cfg: JsonObject, i: Int): Double {
        val pw = cfg.arr("pyramidWeight")
        if (!Progression.isPyramid(cfg) || pw.isNullOrEmpty()) return 0.0
        return max(0.0, pyramidTargetAt(pw, i).numValue() ?: 0.0)
    }

    // ═══ incline.js ═══

    // incline.js:50 hasIncline——值得显示的坡度：高于平的任何数。
    private fun hasIncline(s: JsonObject?): Boolean = (s?.numN("incline") ?: 0.0) > 0

    // incline.js:51 inclineFrom——新行从跟随的组接管什么：有坡度时的 incline。
    private fun inclineFrom(s: JsonObject?): JsonObject =
        if (hasIncline(s)) Js.obj("incline" to jn(s!!.numN("incline")!!)) else Js.emptyObj()

    // ═══ workout-model.js 补件（nextDropWeight / nextBurstReps / splitBurstReps） ═══

    /**
     * workout-model.js:135-152 nextDropWeight——drop 的下一个重量：函数 grid → 直接问格
     * （有限且 ≥0 才取）；数字 grid → 对齐到步长、不回头（≥ prev 时退一步）、不低于 0；无
     * grid → 0.5 对齐。结果全部按 3 位小数 tidy。
     */
    private fun nextDropWeight(prevWeight: Double, pct: Double?, grid: DropGrid?): Double {
        // JS: Math.min(90, Math.max(1, Number(pct) || 20))
        val p = min(90.0, max(1.0, pct?.takeIf { it != 0.0 } ?: 20.0))
        val prev = max(0.0, prevWeight)
        val raw = prev * (1 - p / 100)
        when (grid) {
            is DropGrid.Fn -> {
                val w = grid.f(raw)
                if (w.isFinite() && w >= 0) return jsRound(w * 1000) / 1000
            }
            is DropGrid.Step -> {
                val step = grid.step
                if (step > 0) {
                    var w = jsRound(raw / step) * step
                    if (prev > 0 && w >= prev) w = prev - step
                    return jsRound(max(0.0, w) * 1000) / 1000
                }
            }
            null -> {}
        }
        return jsRound(raw * 2) / 2
    }

    // workout-model.js:153-156 nextBurstReps——下一个 rest-pause burst 的建议次数：约前一次的一半。
    private fun nextBurstReps(prevReps: Double?): Double = max(1.0, jsRound((prevReps ?: 0.0) / 2))

    // workout-model.js:163-172 splitBurstReps——把 rest-pause 总数拆成递降近半的 burst 序列、
    // 加回来恰是总数：12 → [6, 3, 2, 1]。计划 rest-pause 直接配置的就是它。
    private fun splitBurstReps(total: Double): List<Double> {
        val bursts = mutableListOf<Double>()
        var remaining = jsRound(max(0.0, total))
        while (remaining > 0) {
            val burst = min(remaining, nextBurstReps(remaining))
            bursts.add(burst)
            remaining -= burst
        }
        return bursts
    }

    // ═══ dumbbells.js：historyAs 闭包（Dumbbells.kt 对应面 private，本文件复刻） ═══

    // dumbbells.js:36 isMap——非数组对象。
    private fun dbIsMap(v: JsonElement?): Boolean = v is JsonObject

    // dumbbells.js:38 tidy
    private fun dbTidy(w: Double): Double = jsRound(w * 1000) / 1000

    // dumbbells.js:45-48 dbLoadOf——存着的语义（'as'|'each'|'total'），没有 null。S.dbLoad 存
    // { mode, _ts }，裸字符串同读。
    private fun dbLoadOf(value: JsonElement?): String? {
        val m = when (value) {
            is JsonObject -> value.get("mode")
            else -> value
        }
        val s = (m as? JsonPrimitive)?.contentOrNull
        return if (s == "each" || s == "total" || s == "as") s else null
    }

    // dumbbells.js:54-57 dbLoadFor——这个动作现在的重量语义：routine 自己的选择（cfg.dbLoad），
    // 否则动作默认（S.dbLoad[exId]），否则 'as'（照录入，今天的默认行为）。
    private fun dbLoadFor(S: JsonObject?, cfg: JsonObject?): String {
        dbLoadOf(cfg?.get("dbLoad"))?.let { return it }
        val id = (cfg?.get("id") as? JsonPrimitive)?.contentOrNull
        return id?.let { dbLoadOf(S?.sub("dbLoad")?.get(it)) } ?: "as"
    }

    // dumbbells.js:71 isOneArm——单铃：按侧记（cfg.side），或名字说单臂/单手（目录英文名）。
    private fun dbIsOneArm(cfg: JsonObject?): Boolean {
        if (jsTruthy(cfg?.get("side"))) return true
        val n = cfg?.get("id")?.let { Ex.exName(it) } ?: return false
        return ONE_ARM.containsMatchIn(n)
    }

    // dumbbells.js:73 bellsIn——一次举几只：单臂 1，否则 2。
    private fun dbBellsIn(cfg: JsonObject?): Int = if (dbIsOneArm(cfg)) 1 else 2

    // dumbbells.js:85 entryDbLoad——条目保存时的语义：target 上的戳，没有 'as'。
    private fun dbEntryDbLoad(entry: JsonObject?): String =
        dbLoadOf(entry?.sub("target")?.get("dbLoad")) ?: "as"

    // dumbbells.js:86 cfgOfEntry——条目的 cfg 形：{...target, id}。
    private fun dbCfgOfEntry(entry: JsonObject?): JsonObject =
        Js.spread(entry?.sub("target"), Js.obj("id" to entry?.get("id")))

    /**
     * dumbbells.js:97-105 meaningFactor——把按 `from` 记的重量换算成按 `to` 记的同一载荷的
     * 因子。只有两种显式语义转换；"as" 两个方向都按面值；单臂动作一只铃、each 与 total 是
     * 同一个数。
     */
    private fun meaningFactor(from: String?, to: String?, cfg: JsonObject?): Double {
        if (from == to || from == "as" || to == "as" || from.isNullOrEmpty() || to.isNullOrEmpty()) return 1.0
        val n = dbBellsIn(cfg)
        if (n == 1) return 1.0
        return if (from == "each") n.toDouble() else 1.0 / n
    }

    // dumbbells.js:106-115 scaleRow——行按因子缩放：w 与 drops（及每侧）的 w；其余键不动。
    private fun scaleRow(row: JsonElement, f: Double): JsonElement {
        val r = row as? JsonObject ?: return row
        var o = r
        r.numN("w")?.let { o = Js.with(o, "w", jn(dbTidy(it * f))) }
        val drops = r.arr("drops")
        if (drops != null) o = Js.with(o, "drops", JsonArray(drops.map { d ->
            (d as? JsonObject)?.let { dm -> Js.with(dm, "w", jn(dbTidy(dm.numOr("w") * f))) } ?: d
        }))
        val sides = r.sub("sides")
        if (sides != null) {
            val arg = LinkedHashMap<String, JsonElement>()
            for ((k, s) in sides) arg[k] = scaleRow(s, f)
            o = Js.with(o, "sides", JsonObject(arg))
        }
        return o
    }

    /**
     * dumbbells.js:117-125 entryAs——条目按 `to` 语义读的版本：语义不同时是缩放副本（见
     * meaningFactor），否则条目本身。只用于比较，永不保存。
     */
    private fun dbEntryAs(entry: JsonObject, to: String): JsonObject {
        val from = dbEntryDbLoad(entry)
        val f = meaningFactor(from, to, dbCfgOfEntry(entry))
        if (f == 1.0) return entry
        val scaled = (entry.arr("sets") ?: emptyList()).map { s -> scaleRow(s, f) }
        var out = Js.spread(
            entry,
            Js.obj(
                "target" to Js.with(entry.sub("target") ?: Js.emptyObj(), "dbLoad", JsonPrimitive(to)),
                "sets" to JsonArray(scaled),
            ),
        )
        val topW = entry.numN("topW")
        if (topW != null && topW > 0) out = Js.with(out, "topW", jn(dbTidy(topW * f)))
        return out
    }

    // dumbbells.js:127-142 workoutAs——exId 的每条条目按 `to` 读（dbEntryAs）；无变化返回
    // workout 本身。
    private fun workoutAs(w: JsonObject?, exId: JsonElement?, to: String): JsonObject? {
        if (w?.arr("entries") == null) return w
        var changed = false
        val entries = w.arr("entries")!!.map { e ->
            val em = e as? JsonObject
            // 元素非对象（JS 不可达态）原样保留；id 不符的条目原样返回。
            if (em == null || em.get("id") != exId) return@map e
            val next = dbEntryAs(em, to)
            if (next !== em) changed = true
            next
        }
        return if (changed) Js.with(w, "entries", JsonArray(entries)) else w
    }

    /**
     * dumbbells.js:144-155 historyAs——一个动作的历史按 `to` 语义读的状态，供比较方使用：
     * 记录、1RM、建今天行的渐进。什么都不转换（动作从没选过语义）时返回状态本身。
     */
    private fun historyAs(S: JsonObject?, exId: JsonElement?, to: String?): JsonObject? {
        if (S == null || to.isNullOrEmpty() || to == "as" || S.arr("workouts") == null) return S
        var changed = false
        val workouts = S.arr("workouts")!!.map { w ->
            val wm = w as? JsonObject
            // 元素非对象（JS 不可达态）原样保留；workoutAs 未转换时返回原对象（=== 判不变）。
            val next = if (wm != null) workoutAs(wm, exId, to) else wm
            if (next !== w) changed = true
            next ?: w
        }
        return if (changed) Js.with(S, "workouts", JsonArray(workouts)) else S
    }
}
