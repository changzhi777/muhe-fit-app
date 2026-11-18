package com.muhe.fit.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.muhe.fit.app.R
import com.muhe.fit.app.lib.WorkoutModel
import com.muhe.fit.app.lib.arr
import com.muhe.fit.app.lib.bool
import com.muhe.fit.app.lib.jsTruthy
import com.muhe.fit.app.lib.num
import com.muhe.fit.app.lib.sub
import com.muhe.fit.app.screens.workoutfmt
import com.muhe.fit.app.viewmodel.WorkoutViewModel
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Workout 训练会话屏（批 3 · 训练屏完备性 UI；Workout.jsx 结构移植精简版）。
 *
 * 三态（对位 PWA 路由分叉 Workout.jsx L2086-2089）：
 *   Idle    = StartChooser（今日 routine 列表 + 自由练）
 *   Active  = ActiveWorkout（list 视图：动作块 + 组行序号菜单 + stepper/done +
 *             Drop/Burst 缩进行 + 分侧 L/R 行；RestTimerDock 浮底）
 *   Done    = FinishSummary 简版（时长/组数/训练量瓦片）
 *
 * 批 3 新增 UI 完备性：
 *   ① 组行序号钮可点 → DropdownMenu：转热身 / 力竭开关（已开勾选态）/ +Drop / +Burst /
 *      复制组 / 删除组（SetNumberBadge）
 *   ② drops / clusters 缩进 subrow（↳ w kg × r / ↳ r 次 · restSec 秒 + 行尾删除）
 *   ③ failure 标记：set.failure == true 时序号钮显 "F"（红色调，优先于 w/序号）
 *   ④ per-side 行（WorkoutModel.isSideSet 口径）：主行隐藏 stepper/done，渲染 L/R 两行
 *      （side 徽标 + w/r 只读 + done Checkbox → toggleSideDone）；stepper 归批 4
 *   ⑤ 「加热身」TextButton（加一组旁）
 *
 * ⚠️ VM 方法契约（并行批次，编译验收主线统一）：
 *   批 3 签名 = toggleFailure / addDrop / addBurst / removeDrop / toggleSideDone /
 *   makeWarmup / copySet（+ 既有 toggleSet / setField / addSet / removeSet）；
 *   调用面新增签名 = addWarmupSet(entryIdx)（加热身按钮）与
 *   removeCluster(entryIdx, setIdx, i)（cluster 行删除；若 VM 以 removeDrop 兼载
 *   clusters 删除，收口时把 SetOps.removeCluster 换绑即可，UI 零改动）。
 *
 * strings 收口键名清单（V1.4 LibraryScreen 先例：本文件暂用中文常量，收口统一换
 * stringResource，不动 strings.xml——并行属地）：
 *   workout_start_title workout_start_empty workout_start_ex_count workout_start_free
 *   workout_header_name workout_header_finish workout_header_discard
 *   workout_entry_name_fallback
 *   workout_set_add workout_set_add_warmup
 *   workout_menu_warmup workout_menu_failure_on workout_menu_failure_off
 *   workout_menu_drop workout_menu_burst workout_menu_copy workout_menu_delete
 *   workout_unit_kg workout_unit_reps workout_unit_sec
 *   workout_summary_title workout_summary_duration workout_summary_sets
 *   workout_summary_vol workout_summary_ack
 *
 * 批 3b 增强批（superset 配对 UI + 编辑已存会话入口 + backfill 补记）：
 *   ⑥ ExerciseBlock 名称行尾 ⋯ 菜单（MoreVert）：与上一动作组成超级组（entryIdx>0 且无 sg）/ 解除
 *      超级组（sg 非空）→ VM 契约 pairWithPrev(entryIdx) / unpair(entryIdx)，回调经
 *      WorkoutScreen → ActiveWorkout → ExerciseBlock 层层透传
 *   ⑦ entry["sg"] 非空 → 名称行「↔ 超级组」徽标（labelSmall · primary）
 *   ⑧ StartChooser 顶部两操作：「继续编辑上次会话」→ loadLastForEdit()；「补记过去训练」→
 *      内嵌 DatePickerDialog（rememberSelectableDates）选日期 → LocalDate.ofEpochDay(ms/86400000)
 *      → ISO 存 backfillDate → 日期下点 routine 卡 = startBackfill(r.id, date)；未选日期点卡仍走正常 start
 *   ⑨ Active header「编辑中」徽标（active["editingWorkoutId"] 非空 = 编辑中，labelSmall · primary）
 *
 * 🆕 批 4 增强（称重录入 UI + per-side stepper + 分侧 intensifier 渲染）：
 *   ⑩ 称重提示条（🩹 批 5 已删，替换为强制阻塞 scrim + BwDialog，见批 5 ⑭）：ActiveWorkout header 下 active["bw"] 空（缺键 / JsonNull）→ 提示卡
 *      「记录今日体重 →」→ AlertDialog（OutlinedTextField 数字键盘 kg + 确定/取消）
 *      → VM 契约 setBw(w)（空/非法输入点确定 = 无操作不触发）；录入后 bw 有值提示条消失
 *   ⑪ SideRow stepper：L/R 行只读文本改紧凑 ±（MiniStepper = SetRow Stepper 同风格缩小版，
 *      钮 20dp / icon 14dp / bodySmall；w ±2.5 kg / r ±1 次）→ VM 契约 setSideField
 *      (entryIdx, setIdx, side, "w"/"r", delta)；徽标 / done Checkbox 结构保留，stepper 插中间
 *   ⑫ 分侧 intensifier 渲染：sides.L/R 各自 drops（↳ w kg × r + 删 → removeDrop 传侧内
 *      index，引擎 removeSideDropAt 两侧对称删）与 clusters（↳ r 次 + 删 → 复用既有
 *      removeCluster(Int,Int,Int) 签名，引擎对称删——若 VM 另有专用侧版换绑即可，UI 零改动）；
 *      侧组尾部一处「+Drop」小按钮 → addDrop（引擎 addSideDrop 两侧对称加，按钮一处免双加）
 *
 * 🆕 VM 方法契约（批 4 并行批次，编译验收主线统一）：
 *   setBw(w: Double)
 *   setSideField(entryIdx: Int, setIdx: Int, side: String, key: String, delta: Double)
 *
 * 🆕 批 4 追加键名清单：
 *   workout_bw_hint workout_bw_hint_action workout_bw_dialog_title workout_bw_label
 *   workout_bw_confirm workout_bw_cancel workout_side_add_drop
 *
 * 🆕 批 5 增强（cards 布局 + SwipeRow 滑动手势 + 称重强制阻塞 + 布局菜单）：
 *   ⑬ 布局切换菜单：header「完成」旁「布局」TextButton → DropdownMenu（列表视图/卡片视图，
 *      当前态 trailingIcon Check 勾选）→ VM 契约 setLayout("list"/"cards")；s.workoutView
 *      变化即重组，布局切换实时生效
 *   ⑭ 称重强制阻塞（PWA bwSheet 必填语义）：active["bw"] 空（(bw as? JsonPrimitive)
 *      ?.doubleOrNull == null，批 4 contentOrNull 判空升级为数值判空）→ 全屏 scrim（黑 55%
 *      遮罩吞点击挡内容交互）+ 居中 BwDialog（onDismissRequest 空操作 + 无取消钮 =
 *      不可点外部关闭）；setBw 确认录入后 bw 有值 → bwEmpty 翻 false 整块消失。
 *      批 4 称重提示条逻辑删除
 *   ⑮ CardsLayout：HorizontalPager（count = entries.size，rememberPagerState）每页一张
 *      ExerciseBlock（同款 SetRow/菜单/subrow/per-side 全套）+ 页间圆点 indicator（当前页
 *      primary 加大）+ 底部 Prev/Next OutlinedButton（animateScrollToPage，首/尾页禁用）
 *   ⑯ SwipeRow 滑动手势（list 与 cards 共用，ExerciseBlock 内包 SetRow）：pointerInput +
 *      detectHorizontalDragGestures（自带垂直让路），右滑累计 >80dp 触发复制（primary 蓝
 *      渐变提示）→ ops.copySet（批 3 既有）、左滑累计 >80dp 触发删除（error 红渐变提示）
 *      → ops.deleteActiveSet（批 5 新契约）；alpha 随滑动距离线性增长
 *   ⑰ 撤销条：滑动删除/复制后 header 下 UndoBar（「已删除/已复制 · 撤销」TextButton →
 *      ops.undoDeleteSet()），MVP 口径 remember + LaunchedEffect delay(5000) 超时消失
 *      （undoTick 每次触发 +1 重置计时，连击不提前消失；不消费 VM undo 可用态）
 *
 * ⚠️ VM 方法契约（批 5 并行批次，编译验收主线统一）：
 *   UiState.Active.workoutView: String（"list"/"cards"，默认 list）
 *   deleteActiveSet(entryIdx: Int, setIdx: Int) / undoDeleteSet() / setLayout(view: String)
 *
 * ⚠️ MVP 注记：cards 布局内组行水平滑动优先触发复制/删除（子级 pointerInput 抢占 pager
 *   手势），翻页请滑卡片间空白/卡头区域——pager 嵌套滚动协调归后续迭代。
 *
 * 🆕 批 5 追加键名清单：
 *   workout_layout workout_layout_list workout_layout_cards
 *   workout_card_prev workout_card_next
 *   workout_undo_deleted workout_undo_copied workout_undo
 *   workout_bw_required
 *
 * 批 5 之后迭代项：focus 布局、progressline/refLine、折叠 completed、pager 嵌套滚动协调。
 *
 * 🆕 批 3b 追加键名清单：
 *   workout_edit_last workout_backfill workout_backfill_chosen workout_backfill_clear
 *   workout_dp_confirm workout_dp_cancel
 *   workout_menu_pair_prev workout_menu_unpair_sg workout_sg_badge workout_editing_badge
 *   workout_menu_more
 */
@Composable
fun WorkoutScreen(
    onFinished: () -> Unit,
    vm: WorkoutViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val rest by vm.rest.collectAsState()

    // 前台恢复重载（V1.3 CheckInScreen 同款口径）：ON_RESUME 重查 active（进程被杀重开恢复）
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.load() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // 🆕 P7a 返回拦截：Active 态系统返回先弹确认（route-scoped VM 随 pop 销毁，误触即丢会话——
    // 真机验证 2026-11-19 发现）。「放弃」→ vm.discardWorkout() 回 Idle 态（屏自然换 StartChooser，
    // 再按返回即退出路由，无需 nav 参数）；取消留在会话。Idle/Done 态不拦截。
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = state is WorkoutViewModel.UiState.Active) { confirmDiscard = true }
    if (state is WorkoutViewModel.UiState.Active && confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.workout_discard_title)) },
            text = { Text(stringResource(R.string.workout_discard_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; vm.discardWorkout() }) {
                    Text(stringResource(R.string.workout_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.workout_discard_cancel)) }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            is WorkoutViewModel.UiState.Idle -> StartChooser(
                s = s,
                onStart = vm::start,
                onLoadLastForEdit = vm::loadLastForEdit,
                onStartBackfill = vm::startBackfill,
            )
            is WorkoutViewModel.UiState.Active -> ActiveWorkout(
                s = s,
                ops = SetOps(
                    toggle = vm::toggleSet,
                    field = vm::setField,
                    makeWarmup = vm::makeWarmup,
                    toggleFailure = vm::toggleFailure,
                    addDrop = vm::addDrop,
                    addBurst = vm::addBurst,
                    copySet = vm::copySet,
                    removeSet = vm::removeSet,
                    removeDrop = vm::removeDrop,
                    removeCluster = vm::removeCluster,   // 🆕 调用面新增签名（见文件头契约注）
                    toggleSideDone = vm::toggleSideDone,
                    addSet = vm::addSet,
                    addWarmupSet = vm::addWarmupSet,     // 🆕 调用面新增签名（见文件头契约注）
                    setBw = vm::setBw,                    // 🆕 批 4
                    setSideField = vm::setSideField,      // 🆕 批 4
                    deleteActiveSet = vm::deleteActiveSet, // 🆕 批 5：滑动删除组（可撤销）
                    undoDeleteSet = vm::undoDeleteSet,     // 🆕 批 5：撤销删除/复制
                    setLayout = vm::setLayout,             // 🆕 批 5：布局切换 "list"/"cards"
                    setCur = vm::setCur,                   // 🆕 批 6：聚焦视图 prev/next 动作
                ),
                onPairWithPrev = vm::pairWithPrev,
                onUnpair = vm::unpair,
                onFinish = vm::finishWorkout,
                onDiscard = vm::discardWorkout,
            )
            is WorkoutViewModel.UiState.Done -> FinishSummary(s, onDone = {
                vm.acknowledgeSummary()
                onFinished()
            })
        }
        rest?.let { t ->
            RestTimerDock(
                timer = t,
                onSkip = vm::skipRest,
                onAdjust = vm::adjustRest,
                onPause = vm::togglePauseRest,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

// ── StartChooser（无会话态） ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartChooser(
    s: WorkoutViewModel.UiState.Idle,
    onStart: (String?) -> Unit,
    onLoadLastForEdit: () -> Unit,
    onStartBackfill: (String, String) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var backfillDate by remember { mutableStateOf<String?>(null) } // ISO yyyy-MM-dd；非空 = 补记模式
    // 补记日期选择器（函数顶无条件 remember，关掉 dialog 后选择保留）
    val dpState = rememberDatePickerState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "开始训练",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        // 批 3b：顶部两行操作 —— 编辑上次会话 / 补记过去训练
        OutlinedButton(
            onClick = onLoadLastForEdit,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("继续编辑上次会话") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showDatePicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (backfillDate == null) "补记过去训练"
                else "补记日期：$backfillDate（点计划卡开始）",
            )
        }
        if (backfillDate != null) {
            TextButton(onClick = { backfillDate = null }) {
                Text("取消补记（恢复今日/自由练）")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (s.routines.isEmpty()) {
            Text(
                "还没有训练计划。去「计划」页建一个，或直接自由练。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            s.routines.forEach { r ->
                val bd = backfillDate
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            if (bd != null) onStartBackfill(r.id, bd) else onStart(r.id)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${r.exCount} 个动作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { onStart(null) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("自由练（无计划）") }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { ms ->
                        backfillDate = LocalDate.ofEpochDay(ms / 86400000L).toString()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dpState)
        }
    }
}

// ── ActiveWorkout（会话进行态） ─────────────────────────────────────

/** 批 3 组行/动作块操作面：VM 方法引用集中一处（WorkoutScreen 构造，屏内层层透传）。 */
private data class SetOps(
    val toggle: (Int, Int) -> Unit,
    val field: (Int, Int, String, Double) -> Unit,
    val makeWarmup: (Int, Int) -> Unit,
    val toggleFailure: (Int, Int) -> Unit,
    val addDrop: (Int, Int) -> Unit,
    val addBurst: (Int, Int) -> Unit,
    val copySet: (Int, Int) -> Unit,
    val removeSet: (Int, Int) -> Unit,
    val removeDrop: (Int, Int, Int) -> Unit,
    val removeCluster: (Int, Int, Int) -> Unit,   // 调用面新增签名（VM 批次补实现）
    val toggleSideDone: (Int, Int, String) -> Unit,
    val addSet: (Int) -> Unit,
    val addWarmupSet: (Int) -> Unit,              // 调用面新增签名（VM 批次补实现）
    val setBw: (Double) -> Unit,                  // 🆕 批 4：今日体重录入（批 5 升级为强制阻塞弹层）
    val setSideField: (Int, Int, String, String, Double) -> Unit, // 🆕 批 4：分侧 stepper（entryIdx, setIdx, side, key, delta）
    val deleteActiveSet: (Int, Int) -> Unit,      // 🆕 批 5：滑动删除组（VM 记撤销栈）
    val undoDeleteSet: () -> Unit,                // 🆕 批 5：撤销最近一次删除/复制
    val setLayout: (String) -> Unit,              // 🆕 批 5：布局切换（"list"/"cards"）
    val setCur: (Int) -> Unit,                    // 🆕 批 6：聚焦视图动作切换（FocusBranch prev/next）
)

@Composable
private fun ActiveWorkout(
    s: WorkoutViewModel.UiState.Active,
    ops: SetOps,
    onPairWithPrev: (Int) -> Unit,
    onUnpair: (Int) -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
) {
    // 编辑模式标识（批 3b）：editingWorkoutId 非空 = 正在编辑已存会话
    val editing = (s.active["editingWorkoutId"] as? JsonPrimitive)?.contentOrNull != null
    // 🆕 批 5 称重强制阻塞判空（契约口径：doubleOrNull 数值判空，批 4 contentOrNull 升级）
    val bwEmpty = (s.active["bw"] as? JsonPrimitive)?.doubleOrNull == null
    // 🆕 批 5 撤销条状态（MVP：remember + LaunchedEffect 5 秒超时；undoTick 触发 +1 重置计时）
    var undoAction by remember { mutableStateOf<String?>(null) } // "deleted" / "copied"
    var undoTick by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        // header（whdr L1905 简版）：名称 + done/total + 布局菜单 + Finish pill
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.active["name"]?.toString()?.trim('"') ?: "训练",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (editing) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    "编辑中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Text(
                        workoutfmt.elapsed(s.active) + " · " + "${s.doneCount}/${s.totalCount} 组",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 🆕 批 5 布局切换菜单（「完成」旁；当前态勾选）
                LayoutMenu(workoutView = s.workoutView, onPick = ops.setLayout)
                TextButton(onClick = onFinish) {
                    Text(
                        "完成",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        // 🆕 批 5 撤销条（滑动删除/复制后顶部 5 秒；替换批 4 称重提示条位置）
        undoAction?.let { action ->
            UndoBar(
                text = if (action == "deleted") "已删除" else "已复制",
                onUndo = {
                    ops.undoDeleteSet()
                    undoAction = null
                },
            )
        }
        // 5 秒超时消失（undoTick 每次滑动触发 +1 → 计时重启，连击不提前消失）
        LaunchedEffect(undoTick) {
            if (undoTick > 0) {
                delay(5000)
                undoAction = null
            }
        }
        // 🆕 批 5 布局分叉：workoutView 变化即重组（切换实时生效）
        when (s.workoutView) {
            "cards" -> CardsLayout(
                s = s,
                ops = ops,
                onPairWithPrev = onPairWithPrev,
                onUnpair = onUnpair,
                onSwipeAction = { undoAction = it; undoTick++ },
            )
            "focus" -> FocusBranch(
                s = s,
                ops = ops,
                onSwipeAction = { undoAction = it; undoTick++ },
            )
            else -> {
                // 动作块列表（list 视图全展开）
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    itemsIndexed(s.entries) { ei, entry ->
                        ExerciseBlock(
                            entry = entry as? JsonObject ?: JsonObject(emptyMap()),
                            entryIdx = ei,
                            highlighted = ei == s.cur,
                            ops = ops,
                            onPairWithPrev = onPairWithPrev,
                            onUnpair = onUnpair,
                            onSwipeAction = { undoAction = it; undoTick++ },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    item { Spacer(Modifier.height(120.dp)) } // dock 避让
                }
            }
        }
        // Discard 次级动作（确认批 3，MVP 直删）
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDiscard) {
                Text("丢弃会话", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    // 🆕 批 5 称重强制阻塞（PWA bwSheet 必填语义）：bw 空 → 全屏 scrim 挡内容交互 + 居中
    // BwDialog（不可点外部关闭）；setBw 确认录入后 bw 有值 → bwEmpty 翻 false 整块消失
    if (bwEmpty) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* scrim 吞点击防穿透 */ },
            contentAlignment = Alignment.Center,
        ) {
            BwDialog(onConfirm = ops.setBw)
        }
    }
}

// ── ExerciseBlock（动作块 + 组行） ──────────────────────────────────

@Composable
private fun ExerciseBlock(
    entry: JsonObject,
    entryIdx: Int,
    highlighted: Boolean,
    ops: SetOps,
    onPairWithPrev: (Int) -> Unit,
    onUnpair: (Int) -> Unit,
    onSwipeAction: (String) -> Unit,   // 🆕 批 5：SetRow 滑动结果上报（"copied"/"deleted"）
    modifier: Modifier = Modifier,     // 🆕 批 5：cards 布局页内尺寸
) {
    val name = entry["name"]?.toString()?.trim('"') ?: "动作"
    val sets = entry.arr("sets") ?: emptyList()
    // 超级组标记（批 3b）：entry["sg"] 非空 = 已与前一动作配对
    val sg = (entry["sg"] as? JsonPrimitive)?.contentOrNull
    val inSg = !sg.isNullOrEmpty()
    var blockMenuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.surfaceContainerHighest
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (inSg) {
                    Text(
                        "↔ 超级组",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Box {
                    IconButton(
                        onClick = { blockMenuOpen = true },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = blockMenuOpen, onDismissRequest = { blockMenuOpen = false }) {
                        if (entryIdx > 0 && !inSg) {
                            DropdownMenuItem(
                                text = { Text("与上一动作组成超级组") },
                                onClick = { blockMenuOpen = false; onPairWithPrev(entryIdx) },
                            )
                        }
                        if (inSg) {
                            DropdownMenuItem(
                                text = { Text("解除超级组") },
                                onClick = { blockMenuOpen = false; onUnpair(entryIdx) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            sets.forEachIndexed { si, setEl ->
                val set = setEl as? JsonObject ?: return@forEachIndexed
                // 🆕 批 5 SwipeRow：组行滑动手势（右滑复制/左滑删除，list 与 cards 共用）
                SwipeRow(
                    entryIdx = entryIdx,
                    setIdx = si,
                    ops = ops,
                    onAction = onSwipeAction,
                ) {
                    SetRow(
                        entryIdx = entryIdx,
                        setIdx = si,
                        label = "${si + 1}",
                        set = set,
                        ops = ops,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { ops.addSet(entryIdx) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("加一组", style = MaterialTheme.typography.labelLarge)
                }
                // 批 3：加热身（phase=warmup 的新组，VM 落 addWarmupSet）
                TextButton(onClick = { ops.addWarmupSet(entryIdx) }) {
                    Text("加热身", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun SetRow(
    entryIdx: Int,
    setIdx: Int,
    label: String,
    set: JsonObject,
    ops: SetOps,
) {
    val warmup = WorkoutModel.isWarmupRow(set)
    val failure = set.bool("failure") == true
    val done = set.bool("done") == true
    // per-side 行（workout-model.js isSideSet 口径：sides.L + sides.R 同在）
    val sides = if (WorkoutModel.isSideSet(set)) set.sub("sides") else null
    // Drop/Burst 缩进数据（分侧组的 drops/clusters 落 sides 内，批 4 随 per-side stepper 一起接）
    val drops = set.arr("drops")?.mapNotNull { it as? JsonObject } ?: emptyList()
    val clusters = set.arr("clusters")?.mapNotNull { it as? JsonObject } ?: emptyList()

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (sides != null) {
            // per-side：主行只留序号钮（stepper/done 隐藏，stepper 批 4），下方 L/R 两行
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SetNumberBadge(entryIdx, setIdx, label, warmup, failure, done, ops)
            }
            listOf("L", "R").forEach { sideKey ->
                val sideData = sides.sub(sideKey) ?: JsonObject(emptyMap())
                SideRow(
                    entryIdx = entryIdx,
                    setIdx = setIdx,
                    sideLabel = sideKey,
                    data = sideData,
                    ops = ops,
                )
                // 🆕 批 4 分侧 intensifier：各侧 drops / clusters 缩进行
                // （引擎 removeSideDropAt / removeCluster 两侧对称删，UI 传侧内 index；
                //   clusters 删除复用既有 removeCluster(Int,Int,Int) 签名，见文件头注记）
                (sideData.arr("drops") ?: emptyList()).forEachIndexed { i, d ->
                    val dd = d as? JsonObject ?: return@forEachIndexed
                    SubRow(
                        text = workoutfmt.num(dd.num("w") ?: 0.0) + " kg × " + workoutfmt.num(dd.num("r") ?: 0.0),
                    ) { ops.removeDrop(entryIdx, setIdx, i) }
                }
                (sideData.arr("clusters") ?: emptyList()).forEachIndexed { i, c ->
                    val cc = c as? JsonObject ?: return@forEachIndexed
                    SubRow(
                        text = workoutfmt.num(cc.num("r") ?: 0.0) + " 次",
                    ) { ops.removeCluster(entryIdx, setIdx, i) }
                }
            }
            // 🆕 批 4 侧内 +Drop（引擎 addSideDrop 两侧对称加，按钮一处免双加）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 36.dp, top = 1.dp),
            ) {
                TextButton(onClick = { ops.addDrop(entryIdx, setIdx) }) {
                    Text("+Drop", style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SetNumberBadge(entryIdx, setIdx, label, warmup, failure, done, ops)
                Spacer(Modifier.width(8.dp))
                // 重量 stepper
                Stepper(
                    value = set.num("w") ?: 0.0,
                    suffix = "kg",
                    onMinus = { ops.field(entryIdx, setIdx, "w", -2.5) },
                    onPlus = { ops.field(entryIdx, setIdx, "w", 2.5) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // 次数 stepper（reps 显式字段胜过 r，批 2 同口径）
                Stepper(
                    value = set.num("reps") ?: set.num("r") ?: 0.0,
                    suffix = "次",
                    onMinus = { ops.field(entryIdx, setIdx, "r", -1.0) },
                    onPlus = { ops.field(entryIdx, setIdx, "r", 1.0) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // done Check
                Checkbox(
                    checked = done,
                    onCheckedChange = { ops.toggle(entryIdx, setIdx) },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                )
            }
        }
        // Drop 缩进行：↳ w kg × r + 行尾删除
        drops.forEachIndexed { i, d ->
            SubRow(
                text = workoutfmt.num(d.num("w") ?: 0.0) + " kg × " + workoutfmt.num(d.num("r") ?: 0.0),
            ) { ops.removeDrop(entryIdx, setIdx, i) }
        }
        // Burst/cluster 缩进行：↳ r 次 · restSec 秒 + 行尾删除
        clusters.forEachIndexed { i, c ->
            SubRow(
                text = workoutfmt.num(c.num("r") ?: 0.0) + " 次 · " + workoutfmt.num(c.num("restSec") ?: 0.0) + " 秒",
            ) { ops.removeCluster(entryIdx, setIdx, i) }
        }
    }
}

/** 序号圆钮 + 组操作菜单（批 3）：failure 显 "F" 红色调 > warmup 显 "w" > 序号；done 后绿色调。 */
@Composable
private fun SetNumberBadge(
    entryIdx: Int,
    setIdx: Int,
    label: String,
    warmup: Boolean,
    failure: Boolean,
    done: Boolean,
    ops: SetOps,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    when {
                        failure -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                        done -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                )
                .clickable { menuOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    failure -> "F"
                    warmup -> "w"
                    else -> label
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    failure -> MaterialTheme.colorScheme.error
                    done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("转热身") },
                onClick = { menuOpen = false; ops.makeWarmup(entryIdx, setIdx) },
            )
            DropdownMenuItem(
                text = { Text(if (failure) "取消力竭" else "标记力竭") },
                trailingIcon = {
                    if (failure) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp))
                },
                onClick = { menuOpen = false; ops.toggleFailure(entryIdx, setIdx) },
            )
            DropdownMenuItem(
                text = { Text("+Drop（降重追组）") },
                onClick = { menuOpen = false; ops.addDrop(entryIdx, setIdx) },
            )
            DropdownMenuItem(
                text = { Text("+Burst（力竭爆发组）") },
                onClick = { menuOpen = false; ops.addBurst(entryIdx, setIdx) },
            )
            DropdownMenuItem(
                text = { Text("复制组") },
                onClick = { menuOpen = false; ops.copySet(entryIdx, setIdx) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("删除组", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; ops.removeSet(entryIdx, setIdx) },
            )
        }
    }
}

/** 分侧行（批 4）：side 徽标 + w/r 紧凑 stepper（±2.5 kg / ±1 次）+ done Checkbox（原只读文本改可编辑）。 */
@Composable
private fun SideRow(
    entryIdx: Int,
    setIdx: Int,
    sideLabel: String,
    data: JsonObject,
    ops: SetOps,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 36.dp, top = 1.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                sideLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 🆕 批 4：w 紧凑 stepper（±2.5 kg）
        MiniStepper(
            value = data.num("w") ?: 0.0,
            suffix = "kg",
            onMinus = { ops.setSideField(entryIdx, setIdx, sideLabel, "w", -2.5) },
            onPlus = { ops.setSideField(entryIdx, setIdx, sideLabel, "w", 2.5) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        // 🆕 批 4：r 紧凑 stepper（±1 次）
        MiniStepper(
            value = data.num("r") ?: 0.0,
            suffix = "次",
            onMinus = { ops.setSideField(entryIdx, setIdx, sideLabel, "r", -1.0) },
            onPlus = { ops.setSideField(entryIdx, setIdx, sideLabel, "r", 1.0) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Checkbox(
            checked = data.bool("done") == true,
            onCheckedChange = { ops.toggleSideDone(entryIdx, setIdx, sideLabel) },
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
        )
    }
}

/** Drop/Burst 缩进行（批 3）：↳ 前缀 + 数据文本 + 行尾删除钮。 */
@Composable
private fun SubRow(
    text: String,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 36.dp, top = 1.dp),
    ) {
        Text(
            "↳ " + text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stepper(
    value: Double,
    suffix: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMinus, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Clear, contentDescription = "-", Modifier.size(16.dp))
        }
        Text(
            workoutfmt.num(value) + " " + suffix,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onPlus, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "+", Modifier.size(16.dp))
        }
    }
}

/** 紧凑 stepper（批 4 · 分侧行专用）：SetRow Stepper 同风格缩小版（钮 20dp/icon 14dp/bodySmall，行内双列）。 */
@Composable
private fun MiniStepper(
    value: Double,
    suffix: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
            .padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMinus, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Filled.Clear, contentDescription = "-", Modifier.size(14.dp))
        }
        Text(
            workoutfmt.num(value) + " " + suffix,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onPlus, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "+", Modifier.size(14.dp))
        }
    }
}

/** 今日体重录入对话框（批 4；批 5 升级强制阻塞版）：数字键盘 + kg；
 *  onDismissRequest 空操作 + 无取消钮 = 不可点外部/取消关闭（PWA bwSheet 必填语义），
 *  由 ActiveWorkout 在 bwEmpty 时全屏 scrim + 居中挂载；setBw 确认录入后 bw 有值
 *  → bwEmpty 翻 false → 整块消失。空/非法输入点确定不触发（VM 零调用）。 */
@Composable
private fun BwDialog(onConfirm: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { /* 强制阻塞：点外部不关闭 */ },
        title = { Text("今日体重（必填）") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("体重（kg）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toDoubleOrNull()
                if (v != null) onConfirm(v)
            }) { Text("确定") }
        },
        // 无 dismissButton：录入前不可取消（bwSheet 必填语义）
    )
}

/** 布局切换菜单（批 5 ⑬）：header「完成」旁「布局」TextButton → DropdownMenu（列表/卡片
 *  两档），当前态 trailingIcon Check 勾选；onPick 直通 VM setLayout。 */
@Composable
private fun LayoutMenu(
    workoutView: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("布局", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("列表视图") },
                trailingIcon = {
                    if (workoutView != "cards") Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp))
                },
                onClick = { open = false; onPick("list") },
            )
            DropdownMenuItem(
                text = { Text("卡片视图") },
                trailingIcon = {
                    if (workoutView == "cards") Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp))
                },
                onClick = { open = false; onPick("cards") },
            )
            DropdownMenuItem(
                text = { Text("聚焦视图") },
                trailingIcon = {
                    if (workoutView == "focus") Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp))
                },
                onClick = { open = false; onPick("focus") },
            )
        }
    }
}

/** 聚焦视图（批 6 · FocusView.jsx 复刻）：单组大字 + 微调 + 大 done；cur 持久化在 active.cur。 */
@Composable
private fun FocusBranch(
    s: WorkoutViewModel.UiState.Active,
    ops: SetOps,
    onSwipeAction: (String) -> Unit,
) {
    val entries = s.entries
    val cur = s.cur.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
    val entry = entries.getOrNull(cur) ?: JsonObject(emptyMap())
    val sets = (entry["sets"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    // 当前组 = 首个未完成组；全完成则最后一组（FocusView.jsx 当前组语义）
    val setIdx = sets.indexOfFirst { !jsTruthy(it["done"]) }.let { if (it == -1) sets.size - 1 else it }
    val set = sets.getOrNull(setIdx) ?: JsonObject(emptyMap())
    val w = (set["w"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: 0.0
    val r = (set["r"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: 0.0
    FocusLayout(
        entryName = entry["name"]?.toString()?.trim('"') ?: "动作",
        entryIdx = cur,
        setIdx = setIdx,
        weight = w,
        reps = r,
        warmup = WorkoutModel.isWarmupRow(set),
        done = jsTruthy(set["done"]),
        onAdjust = { key, delta -> ops.field(cur, setIdx, key, delta) },
        onToggle = { ops.toggle(cur, setIdx) },
        onPrevExercise = { ops.setCur(cur - 1) },
        onNextExercise = { ops.setCur(cur + 1) },
        setCount = sets.size,
        entryCount = entries.size,
    )
}

/** 卡片视图（批 5 ⑮）：HorizontalPager（count = entries.size）每页一张 ExerciseBlock
 *  （同款 SetRow/菜单/subrow/per-side 全套）+ 页间圆点 indicator（当前页 primary 加大）
 *  + 底部 Prev/Next OutlinedButton（animateScrollToPage，首/尾页禁用）。 */
@Composable
private fun CardsLayout(
    s: WorkoutViewModel.UiState.Active,
    ops: SetOps,
    onPairWithPrev: (Int) -> Unit,
    onUnpair: (Int) -> Unit,
    onSwipeAction: (String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { s.entries.size })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (s.entries.isEmpty()) {
            Text(
                "本会话还没有动作",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                pageSpacing = 12.dp,
            ) { page ->
                ExerciseBlock(
                    entry = s.entries.getOrNull(page) as? JsonObject ?: JsonObject(emptyMap()),
                    entryIdx = page,
                    highlighted = page == s.cur,
                    ops = ops,
                    onPairWithPrev = onPairWithPrev,
                    onUnpair = onUnpair,
                    onSwipeAction = onSwipeAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 页间圆点 indicator
            if (s.entries.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(s.entries.size) { i ->
                        val selected = i == pagerState.currentPage
                        Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (selected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                    }
                }
            }
            // Prev/Next（首/尾页禁用）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                    enabled = pagerState.currentPage > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("上一动作") }
                OutlinedButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    enabled = pagerState.currentPage < s.entries.size - 1,
                    modifier = Modifier.weight(1f),
                ) { Text("下一动作") }
            }
        }
        Spacer(Modifier.height(120.dp)) // dock 避让
    }
}

/** 滑动组行（批 5 ⑯，list 与 cards 共用）：右滑累计 >80dp 触发复制（primary 蓝渐变）
 *  → ops.copySet（批 3 既有）、左滑累计 >80dp 触发删除（error 红渐变）
 *  → ops.deleteActiveSet（批 5 新契约）；alpha 随滑动距离线性增长；
 *  detectHorizontalDragGestures 自带垂直让路（垂直位移大不触发），行内 Checkbox/stepper
 *  点击不受影响。⚠️ cards 布局内组行滑动抢占 pager 翻页（见文件头 MVP 注记）。 */
@Composable
private fun SwipeRow(
    entryIdx: Int,
    setIdx: Int,
    ops: SetOps,
    onAction: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val threshold = remember(density) { with(density) { 80.dp.toPx() } }
    var dragPx by remember { mutableStateOf(0f) }
    val progress = (abs(dragPx) / threshold).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    dragPx > 0f -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f * progress)
                    dragPx < 0f -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f * progress)
                    else -> Color.Transparent
                },
                RoundedCornerShape(6.dp),
            )
            .pointerInput(entryIdx, setIdx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val total = dragPx
                        dragPx = 0f
                        when {
                            total >= threshold -> { ops.copySet(entryIdx, setIdx); onAction("copied") }
                            total <= -threshold -> { ops.deleteActiveSet(entryIdx, setIdx); onAction("deleted") }
                        }
                    },
                    onDragCancel = { dragPx = 0f },
                ) { change, dragAmount ->
                    dragPx += dragAmount
                    change.consume()
                }
            },
    ) { content() }
}

/** 撤销条（批 5 ⑰）：滑动删除/复制后顶部提示 + 撤销 TextButton（ops.undoDeleteSet）；
 *  5 秒超时消失逻辑在 ActiveWorkout（LaunchedEffect + delay，MVP 不消费 VM undo 态）。 */
@Composable
private fun UndoBar(
    text: String,
    onUndo: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUndo) {
            Text("撤销", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── FinishSummary（完成确认简版） ───────────────────────────────────

@Composable
private fun FinishSummary(s: WorkoutViewModel.UiState.Done, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("完成！", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(s.summary.name ?: "", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile("时长", "${s.summary.durationMin} 分")
            SummaryTile("组数", "${s.summary.sets}")
            SummaryTile("训练量", workoutfmt.num(s.summary.vol))
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("收下", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
