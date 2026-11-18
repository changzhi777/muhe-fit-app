package com.muhe.fit.app.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.muhe.fit.app.R
import com.muhe.fit.app.data.TokenStore
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── 教练屏状态机（M2 骨架；交互语义对齐 ck/frontend/src/views/CoachChat.jsx） ──
// 线程模型与 JS 真源同构：提案在等待期间不复制进线程（两设备不会对「待决」分歧），
// 决策落定才以回执消息（applied/dismissed）进线程。demo 期线程只在内存，无持久化。

/** 提案 kind（契约 CoachStatus.job.kind / CoachProposal.kind enum：create/review/debrief）。 */
enum class CoachJobKind {
    CREATE,   // 整计划提案（bundle）
    REVIEW,   // 变更集提案（changes）
    DEBRIEF,  // 单次会话复盘（score/highlights，无可决策项）
}

/** 消息角色与 kind（对齐 CoachChat.jsx 气泡语义；intake/reverted 随批次接入再扩）。 */
enum class CoachRole { USER, COACH }

enum class CoachMessageKind { TEXT, ERROR, NO_CHANGE, APPLIED, DISMISSED }

data class CoachMessage(
    val role: CoachRole,
    val kind: CoachMessageKind,
    val text: String,
)

/** 提案卡的屏面形状（stub 期由 VM 从双语资源组装；真接入后从 CoachProposal JSON 解析）。 */
data class CoachProposalUi(
    val kind: CoachJobKind,
    val summary: String,
    // 变更条目标题（review 的 changes[].why / create 的计划名；卡片内以 chips 呈现）
    val changeTitles: List<String>,
    // debrief 的 1–10 分；非 debrief 为 null
    val score: Int? = null,
)

sealed interface CoachUiState {

    /** 未配置：未配对登录（TokenStore 无 token）——引导回「我的」页配对（Intake 屏随批次接入）。 */
    data object NotConfigured : CoachUiState

    /**
     * 就绪。[thinking] = true 时线程尾部渲染 typing 气泡（coach 正在排队的 job 运行中，
     * 真服务为分钟级轮询，stub 折算亚秒）；[pending] 非空时线程尾部渲染提案卡。
     */
    data class Ready(
        val messages: List<CoachMessage>,
        val pending: CoachProposalUi? = null,
        val thinking: Boolean = false,
    ) : CoachUiState
}

/**
 * 教练屏 ViewModel：demo 两轮对话（StubCoachApi）。
 * 第 1 轮任意留言 → review 提案卡；第 2 轮 → nochange 文案。提案决策走 resolve 链路。
 */
@HiltViewModel
class CoachViewModel @Inject constructor(
    private val coachApi: CoachApi,
    tokenStore: TokenStore,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    var uiState by mutableStateOf<CoachUiState>(CoachUiState.Ready(emptyList()))
        private set

    /** 输入框内容（截 maxMessageLen 契约下限 1000；真值随 status 轮询携带） */
    var input by mutableStateOf("")
        private set

    // demo 会话进度：0 = 未发过，1 = 已出提案，2 = 已收 nochange（两轮后固定）
    private var turn = 0

    init {
        uiState = if (tokenStore.get() == null) {
            CoachUiState.NotConfigured
        } else {
            // 开场白（对齐 CoachChat.jsx 固定首条 coach 气泡）
            CoachUiState.Ready(listOf(coachMsg(R.string.coach_greeting)))
        }
    }

    fun onInput(value: String) {
        input = value.take(MAX_MESSAGE_LEN)
    }

    /** 发送留言：有提案等待时是 refine 语义，否则排 review job（对齐 send() 的分流）。 */
    fun send() {
        val text = input.trim()
        val ready = uiState as? CoachUiState.Ready ?: return
        if (text.isEmpty() || ready.thinking) return
        input = ""
        sendInternal(text)
    }

    /** 快捷 chip「复盘我的训练」：固定话术排 review job。 */
    fun quickReview() {
        val ready = uiState as? CoachUiState.Ready ?: return
        if (ready.thinking) return
        sendInternal(appContext.getString(R.string.coach_chip_review))
    }

    private fun sendInternal(text: String) {
        val ready = uiState as? CoachUiState.Ready ?: return
        uiState = ready.copy(messages = ready.messages + CoachMessage(CoachRole.USER, CoachMessageKind.TEXT, text), thinking = true)
        viewModelScope.launch {
            val reply = when (turn) {
                0 -> {
                    // 第 1 轮：排队 review job（demo 走 plan/review 均可，选 review 语义），出变更集提案
                    coachApi.postCoachReview(CoachReviewRequest(note = text))
                    turn = 1
                    setPending(reviewProposal())
                    null
                }
                else -> {
                    // 第 2 轮起：对提案 refine 或再复盘，回 nochange 收尾（demo 两轮封顶）
                    if ((uiState as? CoachUiState.Ready)?.pending != null) {
                        coachApi.postCoachPlan(CoachPlanRequest(refine = text))
                    } else {
                        coachApi.postCoachReview(CoachReviewRequest(note = text))
                    }
                    turn = 2
                    CoachMessage(CoachRole.COACH, CoachMessageKind.NO_CHANGE, appContext.getString(R.string.coach_demo_nochange))
                }
            }
            if (reply != null) append(reply)
            uiState = (uiState as? CoachUiState.Ready)?.copy(thinking = false) ?: uiState
        }
    }

    /** 采纳全部变更（对齐 resolvePending 全量 accepted；stub 无真实 change id，以索引占位，
     *  真提案 JSON 批次接入后换 changes[].id）。写回前重读 uiState——resolve 挂起期间用户
     *  可能 send() 追加消息，用挂起前捕获的 ready 整体覆盖会抹掉新消息（sendInternal 尾部同款纪律）。 */
    fun acceptProposal() {
        val ready = uiState as? CoachUiState.Ready ?: return
        val proposal = ready.pending ?: return
        viewModelScope.launch {
            coachApi.postCoachResolve(CoachResolveRequest(accepted = proposal.changeTitles.indices.map { it.toString() }))
            val cur = uiState as? CoachUiState.Ready ?: return@launch
            uiState = cur.copy(
                pending = null,
                messages = cur.messages + CoachMessage(CoachRole.COACH, CoachMessageKind.APPLIED, appContext.getString(R.string.coach_applied)),
            )
        }
    }

    /** 全部放弃（dismissed = true，契约语义：整提案未读即弃不再回来）。重读纪律同 acceptProposal。 */
    fun dismissProposal() {
        val ready = uiState as? CoachUiState.Ready ?: return
        val proposal = ready.pending ?: return
        viewModelScope.launch {
            coachApi.postCoachResolve(CoachResolveRequest(dismissed = true))
            val cur = uiState as? CoachUiState.Ready ?: return@launch
            uiState = cur.copy(
                pending = null,
                messages = cur.messages + CoachMessage(CoachRole.COACH, CoachMessageKind.DISMISSED, appContext.getString(R.string.coach_dismissed)),
            )
        }
    }

    private fun setPending(proposal: CoachProposalUi) {
        val ready = uiState as? CoachUiState.Ready ?: return
        uiState = ready.copy(pending = proposal)
    }

    private fun append(message: CoachMessage) {
        val ready = uiState as? CoachUiState.Ready ?: return
        uiState = ready.copy(messages = ready.messages + message)
    }

    private fun coachMsg(res: Int) = CoachMessage(CoachRole.COACH, CoachMessageKind.TEXT, appContext.getString(res))

    /** demo 提案卡：review 变更集（kind chips 分类 = REVIEW）。 */
    private fun reviewProposal() = CoachProposalUi(
        kind = CoachJobKind.REVIEW,
        summary = appContext.getString(R.string.coach_demo_proposal_summary),
        changeTitles = listOf(
            appContext.getString(R.string.coach_demo_change_1),
            appContext.getString(R.string.coach_demo_change_2),
            appContext.getString(R.string.coach_demo_change_3),
        ),
    )

    companion object {
        // 契约 maxMessageLen 下限（200–4000）；真值随 status 轮询覆盖
        const val MAX_MESSAGE_LEN = 1000
    }
}
