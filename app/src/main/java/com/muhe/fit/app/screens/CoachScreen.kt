package com.muhe.fit.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muhe.fit.app.R
import com.muhe.fit.app.ui.theme.MuHeTheme
import com.muhe.fit.app.ui.theme.muheExtended
import com.muhe.fit.app.viewmodel.CoachJobKind
import com.muhe.fit.app.viewmodel.CoachMessage
import com.muhe.fit.app.viewmodel.CoachMessageKind
import com.muhe.fit.app.viewmodel.CoachProposalUi
import com.muhe.fit.app.viewmodel.CoachRole
import com.muhe.fit.app.viewmodel.CoachUiState
import com.muhe.fit.app.viewmodel.CoachViewModel

/**
 * AI 教练对话屏骨架（M2；交互语义对齐 ck/frontend/src/views/CoachChat.jsx）：
 * 消息线程（用户/教练气泡 + 提案卡）→ typing 指示（job 运行中）→ 输入区（快捷 chip + composer）。
 * 三态：未配置（引导配对）/ thinking（typing 气泡）/ 就绪。
 * 数据源 = CoachApi（StubCoachApi demo 两轮，诚实标注；Go 原生 coach 服务端随 M2 服务端批次
 * 实装——G3 裁定），完整线程历史在客户端本地（S.coach.chat 同构），本批不做持久化。
 */
@Composable
fun CoachScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CoachViewModel = hiltViewModel()
    val state = viewModel.uiState
    CoachBody(
        state = state,
        input = viewModel.input,
        onInputChange = viewModel::onInput,
        onSend = viewModel::send,
        onQuickReview = viewModel::quickReview,
        onAccept = viewModel::acceptProposal,
        onDismiss = viewModel::dismissProposal,
        onBack = onBack,
        modifier = modifier,
    )
}

/** 屏体（参数化：@Preview 不经 Hilt 直接给定状态；LoginScreen 的 Content 分离同款纪律）。 */
@Composable
private fun CoachBody(
    state: CoachUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onQuickReview: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        CoachHeader(state = state, onBack = onBack)

        when (val s = state) {
            // 未配置：未配对登录——Intake 屏随批次接入，当前引导回「我的」页配对
            CoachUiState.NotConfigured -> NotConfiguredBody(onBack = onBack)
            is CoachUiState.Ready -> ReadyBody(
                state = s,
                input = input,
                onInputChange = onInputChange,
                onSend = onSend,
                onQuickReview = onQuickReview,
                onAccept = onAccept,
                onDismiss = onDismiss,
            )
        }
    }
}

/** 顶部栏：返回 + 教练圆徽 + 标题 + 状态副标题（thinking / 有提案 / 待命三态文案） */
@Composable
private fun CoachHeader(state: CoachUiState, onBack: () -> Unit) {
    val statusRes = when (state) {
        is CoachUiState.Ready ->
            if (state.thinking) {
                R.string.coach_status_thinking
            } else if (state.pending != null) {
                R.string.coach_status_suggestion
            } else {
                R.string.coach_status_idle
            }
        CoachUiState.NotConfigured -> R.string.coach_status_idle
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.coach_back_cd))
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "AI",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = stringResource(R.string.coach_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(statusRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.muheExtended.textTertiary,
            )
        }
    }
}

/** 未配置态：居中引导卡（配对后进入对话；Intake 问卷随批次接入） */
@Composable
private fun NotConfiguredBody(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.coach_not_configured_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.coach_not_configured_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.muheExtended.textTertiary,
                )
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.coach_go_setup))
                }
            }
        }
    }
}

/** 就绪态：消息线程 + typing/提案卡 + composer */
@Composable
private fun ReadyBody(
    state: CoachUiState.Ready,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onQuickReview: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val messages = state.messages
    // 尾部固定 demo 标注 item 计入滚动目标（原 tailCount 少算一项，最底一行永远滚不进视口）
    val tailCount = messages.size + (if (state.thinking) 1 else 0) + (if (state.pending != null) 1 else 0) + 1

    // 新消息/typing/提案出现时滚到底（对齐 scrollIntoView 语义）
    LaunchedEffect(tailCount) {
        if (tailCount > 0) listState.animateScrollToItem(tailCount - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        items(messages) { message -> MessageBubble(message) }
        if (state.thinking) item { TypingBubble() }
        state.pending?.let { proposal ->
            item {
                ProposalCard(proposal = proposal, onAccept = onAccept, onDismiss = onDismiss)
            }
        }
        // demo 诚实标注：当前线程为 stub 演示数据
        item {
            Text(
                text = stringResource(R.string.coach_demo_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.muheExtended.textTertiary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        }

        Composer(
            state = state,
            input = input,
            onInputChange = onInputChange,
            onSend = onSend,
            onQuickReview = onQuickReview,
        )
    }
}

/** 气泡：用户右（primaryContainer）/ 教练左（surfaceContainerHigh）/ 错误 errorContainer */
@Composable
private fun MessageBubble(message: CoachMessage) {
    val isUser = message.role == CoachRole.USER
    val isError = message.kind == CoachMessageKind.ERROR
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val container = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                    ),
                )
                .background(container)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** typing 指示：三点气泡 + 常规时长说明（真服务为分钟级异步轮询） */
@Composable
private fun TypingBubble() {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = "···",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.coach_typing_eta),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
        )
    }
}

/** 提案卡：kind 分类 chip + 摘要 + 变更条目 + 采纳/放弃动作（逐条勾选随真提案 JSON 批次接入） */
@Composable
private fun ProposalCard(
    proposal: CoachProposalUi,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val kindRes = when (proposal.kind) {
        CoachJobKind.CREATE -> R.string.coach_kind_create
        CoachJobKind.REVIEW -> R.string.coach_kind_review
        CoachJobKind.DEBRIEF -> R.string.coach_kind_debrief
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(kindRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
                proposal.score?.let { score ->
                    Text(
                        text = stringResource(R.string.coach_debrief_score, score),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.muheExtended.textTertiary,
                    )
                }
            }
            Text(
                text = proposal.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            proposal.changeTitles.forEach { title ->
                Text(
                    text = "· $title",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.coach_proposal_dismiss))
                }
                Button(onClick = onAccept) {
                    Text(text = stringResource(R.string.coach_proposal_accept))
                }
            }
        }
    }
}

/** 输入区：快捷 chip（仅待命时）+ 文本框 + 发送（placeholder 随提案/job 状态分流） */
@Composable
private fun Composer(
    state: CoachUiState.Ready,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onQuickReview: () -> Unit,
) {
    val idle = !state.thinking && state.pending == null
    val hintRes = when {
        state.pending?.kind == CoachJobKind.CREATE -> R.string.coach_input_hint_create
        state.thinking -> R.string.coach_input_hint_thinking
        else -> R.string.coach_input_hint
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (idle) {
            OutlinedButton(onClick = onQuickReview, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.coach_chip_review))
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(hintRes)) },
                enabled = !state.thinking,
                maxLines = 4,
            )
            TextButton(
                onClick = onSend,
                enabled = !state.thinking && input.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.coach_send))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360, heightDp = 720)
@Composable
private fun CoachScreenPreview() {
    // 深色唯一主题（裁定 #1）：MuHeTheme 即全局深色方案；
    // 直接给定 Ready 态（不经 Hilt），demo 线程 = 开场白 + 用户留言 + 教练回复 + review 提案卡
    MuHeTheme {
        CoachBody(
            state = CoachUiState.Ready(
                messages = listOf(
                    CoachMessage(CoachRole.COACH, CoachMessageKind.TEXT, "你好，我是你的教练。未经你确认，什么都不会变。"),
                    CoachMessage(CoachRole.USER, CoachMessageKind.TEXT, "帮我看看最近的训练"),
                    CoachMessage(CoachRole.COACH, CoachMessageKind.TEXT, "最近的训练完成度不错，我有两处建议。"),
                ),
                pending = CoachProposalUi(
                    kind = CoachJobKind.REVIEW,
                    summary = "基于你的记录，我建议做两处小调整。",
                    changeTitles = listOf("深蹲组数 4 → 5", "卧推重量进阶 +2.5 kg"),
                ),
            ),
            input = "",
            onInputChange = {},
            onSend = {},
            onQuickReview = {},
            onAccept = {},
            onDismiss = {},
            onBack = {},
        )
    }
}
