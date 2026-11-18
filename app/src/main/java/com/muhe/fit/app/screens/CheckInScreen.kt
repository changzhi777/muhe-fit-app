package com.muhe.fit.app.screens

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.muhe.fit.app.R
import com.muhe.fit.app.data.entity.AttendanceEntity
import com.muhe.fit.app.data.entity.GymCardEntity
import com.muhe.fit.app.data.entity.Stamp
import com.muhe.fit.app.lib.canRenderFmt
import com.muhe.fit.app.lib.normalizeFmt
import com.muhe.fit.app.ui.theme.MuHeTheme
import com.muhe.fit.app.ui.theme.muheExtended
import com.muhe.fit.app.viewmodel.CheckInUiState
import com.muhe.fit.app.viewmodel.CheckInViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 打卡屏（M2，android-app.md §3.4；交互语义参照 ck/frontend/src/views/CheckIn.jsx）：
 * 会员码横滑卡轨（HorizontalPager，尾页固定「添加卡片」位 + 点位指示，重开在最后用的卡上）→
 * 选中卡 QR 全屏亮码（zxing core BitMatrix → ImageBitmap 自绘，黑码白底——闸机读对比度
 * 不读主题色，qr.js:41 语义）+「记录到场」落 attendance（协议第八类首版消费）。
 * 添加卡只允许相机扫码（禁手输，打卡域既有纪律）；非 QR 按 canRenderFmt 明确拒绝。
 * V1.2 增卡编辑（改名 + 重扫）与删除（先 recordDeletion 记戳再删行——CheckIn.jsx CardFace
 * 语义）；扫码组件面已抽 ScanSupport.kt 与登录屏共享。
 * 历史段：本月列表 + 本月 N 次·连续 N 天。相机权限走 ActivityResultContracts.RequestPermission
 * 原生 API（Accompanist 白名单外不用），被拒态引导系统设置。
 */
@Composable
fun CheckInScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CheckInViewModel = hiltViewModel(),
) {
    // 前台恢复即刷新（V1.3；同步挂接后拉下来的新卡/新记录即见）。addObserver 落在已 RESUMED
    // 的生命周期上会同步补发一次 ON_RESUME——与 init 的 reload 重叠，两次本地查询可忽略
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 屏与 VM 分离：VM 依赖 Room/SharedPreferences 无法在 @Preview 构造（同 LoginScreen
    // 的 Preview 专用 stub 思路，这里走 Content 参数化）
    CheckInContent(
        state = viewModel.uiState,
        onBack = onBack,
        onActiveChanged = viewModel::onActiveCardChanged,
        onMove = viewModel::moveCard,
        onScanAdd = viewModel::addCard,
        onUpdateCard = viewModel::updateCard,
        onDeleteCard = viewModel::deleteCard,
        onRecord = viewModel::checkIn,
        onShiftMonth = viewModel::shiftMonth,
        modifier = modifier,
    )
}

@Composable
private fun CheckInContent(
    state: CheckInUiState,
    onBack: () -> Unit,
    onActiveChanged: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onScanAdd: (String, String) -> Unit,
    onUpdateCard: (GymCardEntity, String, String, String, (Boolean) -> Unit) -> Unit,
    onDeleteCard: (GymCardEntity) -> Unit,
    onRecord: (GymCardEntity) -> Unit,
    onShiftMonth: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val doneText = stringResource(R.string.checkin_done_toast)
    val addedText = stringResource(R.string.checkin_card_added)
    val updatedText = stringResource(R.string.checkin_card_updated)
    val removedText = stringResource(R.string.checkin_card_removed)
    val goneText = stringResource(R.string.checkin_card_gone)
    val notQrText = stringResource(R.string.checkin_not_qr)
    val scanAddTitle = stringResource(R.string.checkin_scan_add)
    val scanHint = stringResource(R.string.checkin_scan_hint)

    var scanning by rememberSaveable { mutableStateOf(false) }
    // 编辑/删除面板目标卡与草稿（remember 非可保存——配置变更丢草稿属可接受，不做 Saveable 复杂化）
    var editingCard by remember { mutableStateOf<GymCardEntity?>(null) }
    var editLabel by remember { mutableStateOf("") }
    var editValue by remember { mutableStateOf("") }
    var editFmt by remember { mutableStateOf("qrcode") }
    var deletingCard by remember { mutableStateOf<GymCardEntity?>(null) }

    fun openEdit(card: GymCardEntity) {
        editingCard = card
        editLabel = card.label.orEmpty()
        editValue = card.value.orEmpty()
        editFmt = card.fmt ?: "qrcode"
    }

    // 扫码结果（value + zxing 制式名）：非 QR 明确拒绝（canRenderFmt 闸，CheckIn.jsx doScan 语义）；
    // 编辑面板打开时结果回填重扫草稿，否则按「扫码添加」落库
    val onScanFound: (String, String) -> Unit = { value, rawFmt ->
        scanning = false
        if (!canRenderFmt(rawFmt)) {
            Toast.makeText(context, notQrText, Toast.LENGTH_SHORT).show()
        } else {
            val fmt = normalizeFmt(rawFmt)
            val editing = editingCard
            if (editing != null) {
                editValue = value
                editFmt = fmt
            } else {
                onScanAdd(value, fmt)
                view.hapticConfirm()
                Toast.makeText(context, addedText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val cardLabels = state.cards.associate { it.id to it.label }
    val stampFormat = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HeaderRow(onBack = onBack, hasCards = state.cards.isNotEmpty()) }
        item {
            CardRail(
                state = state,
                onActiveChanged = onActiveChanged,
                onMove = onMove,
                onAddClick = { scanning = true },
                onEdit = ::openEdit,
                onDelete = { deletingCard = it },
                onRecord = { card ->
                    // savingVisit 守卫：写入进行中不重复触发，成功 Toast 不在写库前误报
                    if (!state.savingVisit) {
                        onRecord(card)
                        view.hapticConfirm()
                        Toast.makeText(context, doneText, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        // 空态引导（CheckIn.jsx 无卡时的 muted 提示）
        if (state.cards.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.checkin_add_hint),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                    color = MaterialTheme.muheExtended.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.checkin_section_history),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // 本月统计（口径恒锚本月，不随历史区选中月走——设计「本月 N 次 / streak」）
        if (state.monthCount > 0) {
            item {
                Text(
                    text = stringResource(R.string.checkin_month_stats, state.monthCount, state.streakDays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // 月切换（‹ yyyy/MM ›；下一月在本月处失效——未来无数据）
        item {
            MonthSwitcher(
                month = state.selectedMonth,
                atLatest = state.selectedMonth == YearMonth.now(),
                onShift = onShiftMonth,
            )
        }
        if (state.monthEntries.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.checkin_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.muheExtended.textTertiary,
                )
            }
        } else {
            items(state.monthEntries, key = { it.id }) { entry ->
                HistoryRow(entry, cardLabels, stampFormat)
            }
        }
    }

    // 全屏扫码覆盖层（CameraX 预览 + zxing 解码；离开组合即解绑相机）
    AnimatedVisibility(visible = scanning, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        ScanOverlay(
            title = scanAddTitle,
            hint = scanHint,
            onFound = onScanFound,
            onDismiss = { scanning = false },
        )
    }

    // 编辑面板（改名 + 重扫；码值禁手输——唯一来源是重扫）
    editingCard?.let { card ->
        EditCardPanel(
            labelDraft = editLabel,
            valueDraft = editValue,
            onLabelInput = { editLabel = it },
            onRescan = { scanning = true },
            onSave = {
                onUpdateCard(card, editLabel, editValue, editFmt) { ok ->
                    Toast.makeText(context, if (ok) updatedText else goneText, Toast.LENGTH_SHORT).show()
                }
                editingCard = null
            },
            onDismiss = { editingCard = null },
        )
    }

    // 删除确认（CheckIn.jsx confirmSheet danger 语义）
    deletingCard?.let { card ->
        ConfirmPanel(
            title = stringResource(R.string.checkin_remove_title),
            message = card.label ?: stringResource(R.string.checkin_card_default),
            onConfirm = {
                onDeleteCard(card)
                Toast.makeText(context, removedText, Toast.LENGTH_SHORT).show()
                deletingCard = null
            },
            onDismiss = { deletingCard = null },
        )
    }
}

/**
 * 头行：返回 + 标题 + 副题（有卡「在健身房出示此码」/ 无卡「添加你的健身卡」，CheckIn.jsx sub 语义）。
 */
@Composable
private fun HeaderRow(onBack: () -> Unit, hasCards: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowLeft, // back：core 白名单内承载
                contentDescription = stringResource(R.string.checkin_back),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.checkin_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (hasCards) R.string.checkin_subtitle_cards else R.string.checkin_subtitle_empty,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.muheExtended.textTertiary,
            )
        }
    }
}

/**
 * 会员码横滑卡轨：每张卡一页，尾页固定「添加卡片」位（CheckIn.jsx 同款尾页 + 点位）。
 * 重开在最后用的卡上（lastCardId 记忆；卡已删时回第一张，CheckIn.jsx useLayoutEffect 语义）；
 * 停稳的页回调 onActiveChanged（驱动 VM 记忆）；◀▶ 借 moveGymCard 语义重排（多卡时显示）。
 */
@Composable
private fun CardRail(
    state: CheckInUiState,
    onActiveChanged: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAddClick: () -> Unit,
    onEdit: (GymCardEntity) -> Unit,
    onDelete: (GymCardEntity) -> Unit,
    onRecord: (GymCardEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cards = state.cards
    val pageCount = cards.size + 1
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // 停稳即记忆（settledPage 而非 currentPage：滑动途中不写 lastGymCardId；
    // 守卫走 pagerState.pageCount 取实时值，避免闭包捕获陈旧 cards）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page < pagerState.pageCount - 1) onActiveChanged(page)
        }
    }
    // 重开在最后用的卡：cards 内容/顺序、lastCardId 变化时对位（卡已删回第一张 = max(0, -1)）。
    // key 用 cards 整表（List.equals 逐元素）而非 cards.size——◀▶ 重排不改 size，
    // 只按 size 对位会漏掉重排后的页面错位（2026-11-18 修复）
    LaunchedEffect(cards, state.lastCardId) {
        val idx = maxOf(0, cards.indexOfFirst { it.id == state.lastCardId })
        if (pagerState.currentPage != idx && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(idx)
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth().height(460.dp),
        ) { page ->
            val card = cards.getOrNull(page)
            if (card == null) {
                AddCardPage(onAddClick = onAddClick)
            } else {
                CardPage(
                    card = card,
                    index = page,
                    count = cards.size,
                    onMove = onMove,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onRecord = onRecord,
                )
            }
        }
        // 点位指示（含尾页添加位一颗，CheckIn.jsx ci-dots 语义）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pageCount) { i ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                )
            }
        }
    }
}

/**
 * 单张卡：卡头（编辑/删除 + 卡名，CheckIn.jsx ci-card-hd 语义）+ QR 亮码板（白底黑码）+
 * 码值回显（闸机挑食时店员可手抄）+ ◀▶ 重排行（多卡才显示）+「记录到场」主按钮。
 * QR 以 1 模块 1 像素自绘并以 FilterQuality.None 最近邻显示——任意缩放都保持码格锐利
 * （qr.js pixelated 语义）。
 */
@Composable
private fun CardPage(
    card: GymCardEntity,
    index: Int,
    count: Int,
    onMove: (Int, Int) -> Unit,
    onEdit: (GymCardEntity) -> Unit,
    onDelete: (GymCardEntity) -> Unit,
    onRecord: (GymCardEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onEdit(card) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.checkin_edit_card),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = card.label ?: stringResource(R.string.checkin_card_default),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onDelete(card) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.checkin_remove_card),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // 亮码板：白色底盘 + MARGIN 2 模块静区（EncodeHintType.MARGIN）——扫码器吃高对比
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(12.dp),
        ) {
            QrCodeImage(
                value = card.value.orEmpty(),
                size = 230.dp,
                contentDescriptionText = card.label ?: stringResource(R.string.checkin_card_default),
            )
        }
        Text(
            text = card.value.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        if (count > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconButton(
                    onClick = { onMove(index, index - 1) },
                    enabled = index > 0,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.checkin_move_left),
                    )
                }
                Text(
                    text = stringResource(R.string.checkin_card_position, index + 1, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { onMove(index, index + 1) },
                    enabled = index < count - 1,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.checkin_move_right),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onRecord(card) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
        ) {
            Text(
                text = stringResource(R.string.checkin_record),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** 尾页「添加卡片」位：整页可点进扫码（CheckIn.jsx ci-add 语义；禁手输，无文本输入面）。 */
@Composable
private fun AddCardPage(onAddClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onAddClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.checkin_add_card),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 编辑卡面板（CheckIn.jsx CardSheet 的编辑形态）：重扫换码 + 卡名输入 + QR 预览 + 保存。
 * 码值无手输面——唯一来源是重扫；label 留空落默认卡名（commit 语义 label.trim() || t('Gym card')）。
 */
@Composable
private fun EditCardPanel(
    labelDraft: String,
    valueDraft: String,
    onLabelInput: (String) -> Unit,
    onRescan: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.checkin_edit_card),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FilledTonalButton(
                onClick = onRescan,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(22.dp),
            ) {
                Text(stringResource(R.string.checkin_rescan))
            }
            OutlinedTextField(
                value = labelDraft,
                onValueChange = onLabelInput,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                label = { Text(stringResource(R.string.checkin_card_label)) },
                placeholder = {
                    Text(
                        text = stringResource(R.string.checkin_label_placeholder),
                        color = MaterialTheme.muheExtended.textTertiary,
                    )
                },
            )
            // 码值预览（只展示不编辑——禁手输纪律；重扫后即时重生成）
            if (valueDraft.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(12.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(8.dp),
                ) {
                    QrCodeImage(
                        value = valueDraft,
                        size = 150.dp,
                        contentDescriptionText = labelDraft.ifBlank { valueDraft },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.checkin_cancel))
                }
                Button(
                    onClick = onSave,
                    enabled = valueDraft.isNotBlank(),
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text(stringResource(R.string.checkin_save_card))
                }
            }
        }
    }
}

/** 删除确认面板（CheckIn.jsx confirmSheet danger 语义）：标题 + 卡名 + 取消/删除（error 填充）。 */
@Composable
private fun ConfirmPanel(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.muheExtended.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.checkin_cancel))
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.checkin_remove_confirm))
                }
            }
        }
    }
}

/** QR 亮码：按显示像素生成（produceState 派 Default 线程 + Lru 缓存），最近邻滤波保持码格锐利。 */
@Composable
private fun QrCodeImage(
    value: String,
    size: Dp,
    contentDescriptionText: String,
    modifier: Modifier = Modifier,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, value, sizePx) {
        if (value.isNotEmpty()) {
            this@produceState.value = withContext(Dispatchers.Default) {
                renderQrBitmap(value, sizePx)
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = contentDescriptionText,
            modifier = modifier.size(size),
            filterQuality = FilterQuality.None,
        )
    } else {
        Box(modifier = modifier.size(size))
    }
}

/**
 * QR 位图机内缓存（按字节 Lru，上限 ~12MB）：pager 来回滑动免重复编码——每次编码
 * 都是全量矩阵运算，滑两卡再滑回来不该再算一遍。键 = "值@像素尺寸"。
 */
private val qrBitmapCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/**
 * zxing core 生成 BitMatrix → ImageBitmap 自绘（qr.js renderQrToCanvas 的 Android 半）：
 * 黑码白底——闸机读的是对比度不是主题色（qr.js:41 注释语义）；MEDIUM 纠错（抗划痕的
 * 良好默认）+ MARGIN 2 模块静区。生成失败（WriterException）返回 null 由调用方占位。
 */
private fun renderQrBitmap(value: String, sizePx: Int): ImageBitmap? {
    val key = "$value@$sizePx"
    qrBitmapCache.get(key)?.let { return it.asImageBitmap() }
    val hints = mapOf(
        EncodeHintType.MARGIN to 2,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = try {
        QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    } catch (_: WriterException) {
        return null
    }
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { android.graphics.Color.WHITE }
    for (y in 0 until h) {
        val offset = y * w
        for (x in 0 until w) {
            if (matrix[x, y]) pixels[offset + x] = android.graphics.Color.BLACK
        }
    }
    val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    qrBitmapCache.put(key, bmp)
    return bmp.asImageBitmap()
}

/** 历史区月切换行：‹ yyyy/MM ›；下一月按钮在本月处禁用（未来月无数据）。 */
private val monthFormatter = DateTimeFormatter.ofPattern("yyyy/MM")

@Composable
private fun MonthSwitcher(
    month: YearMonth,
    atLatest: Boolean,
    onShift: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val monthText = remember(month) { month.format(monthFormatter) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onShift(-1) }, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.checkin_prev_month),
            )
        }
        Text(
            text = monthText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onShift(1) }, enabled = !atLatest, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.checkin_next_month),
            )
        }
    }
}

/** 打卡历史行：本地化「日期 时间」+ 次行卡名（卡已删回落占位名）+ 备注（有则带出）。 */
@Composable
private fun HistoryRow(
    entry: AttendanceEntity,
    cardLabels: Map<String, String?>,
    formatter: DateTimeFormatter,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val atText = remember(entry.at) {
        formatter.format(Instant.ofEpochMilli(entry.at).atZone(zone))
    }
    val cardLabel = cardLabels[entry.gymCardId]
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.checkin_card_default)
    val subText = if (entry.note.isNullOrBlank()) cardLabel else "$cardLabel · ${entry.note}"
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = atText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
        )
    }
}

/** 触觉（打卡两处）：扫码添加成功 / 记录到场——CONFIRM（<R 回落 LONG_PRESS，LoginScreen 同款） */
private fun View.hapticConfirm() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360, heightDp = 720)
@Composable
private fun CheckInScreenPreview() {
    // 深色唯一主题（裁定 #1）；预览态两卡 + 两条历史，VM 不经 Hilt（见 CheckInScreen 拆分注）
    val now = System.currentTimeMillis()
    val sample = CheckInUiState(
        cards = listOf(
            GymCardEntity("c1", "powerhouse 旗舰店", "MUFIT-2026-0001", "qrcode", "{}", Stamp(ts = now)),
            GymCardEntity("c2", "活力汇 · 滨江店", "GYM-88291033", "qrcode", "{}", Stamp(ts = now)),
        ),
        lastCardId = "c1",
        selectedMonth = YearMonth.now(),
        monthEntries = listOf(
            AttendanceEntity("a1", "c1", now, null, "{}", Stamp(ts = now)),
            AttendanceEntity("a2", "c1", now - 86_400_000, "早课", "{}", Stamp(ts = now - 86_400_000)),
        ),
        monthCount = 2,
        streakDays = 2,
    )
    MuHeTheme {
        CheckInContent(
            state = sample,
            onBack = {},
            onActiveChanged = {},
            onMove = { _, _ -> },
            onScanAdd = { _, _ -> },
            onUpdateCard = { _, _, _, _, _ -> },
            onDeleteCard = {},
            onRecord = {},
            onShiftMonth = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360, heightDp = 720)
@Composable
private fun CheckInScreenEmptyPreview() {
    // 空态：无卡引导扫码添加
    MuHeTheme {
        CheckInContent(
            state = CheckInUiState(),
            onBack = {},
            onActiveChanged = {},
            onMove = { _, _ -> },
            onScanAdd = { _, _ -> },
            onUpdateCard = { _, _, _, _, _ -> },
            onDeleteCard = {},
            onRecord = {},
            onShiftMonth = {},
        )
    }
}
