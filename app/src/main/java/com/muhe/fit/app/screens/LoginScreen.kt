package com.muhe.fit.app.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muhe.fit.app.R
import com.muhe.fit.app.ui.theme.MuHeTheme
import com.muhe.fit.app.ui.theme.muheExtended
import com.muhe.fit.app.net.PasskeyManager
import com.muhe.fit.app.viewmodel.AuthApi
import com.muhe.fit.app.viewmodel.LinkApi
import com.muhe.fit.app.viewmodel.LinkErrorKind
import com.muhe.fit.app.viewmodel.LinkOptionsResult
import com.muhe.fit.app.viewmodel.LinkPairResult
import com.muhe.fit.app.viewmodel.LinkStep
import com.muhe.fit.app.viewmodel.LinkUiState
import com.muhe.fit.app.viewmodel.LinkVerifyResult
import com.muhe.fit.app.viewmodel.LinkViewModel
import com.muhe.fit.app.viewmodel.LoginUiState
import com.muhe.fit.app.viewmodel.PairViewModel
import com.muhe.fit.app.viewmodel.RedeemResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * 登录屏（M1 收尾清单 #3，login-design.md V0.1 逐区 1:1）：
 * 品牌区 weight(1f) ≈36% → 认证区（主 CTA 扫码 / 次按钮手输展开 / 辅助说明 / 先逛逛）→ 页脚折叠服务器栏。
 * 六状态（§五）：加载（按钮内圈）/ 配对码错误（就近、无 shake）/ 服务器不可达（banner）/ 扫码成功过场 /
 * 相机无权限（inline + 去授权）/ 访客进入（回调直达）。
 * V1.2 扫码真接线：主 CTA 拉起共享 ScanOverlay（ScanSupport.kt），识别结果经
 * PairViewModel.onScannedCode 满 8 位即兑；权限被拒点亮认证区 inline 行（LoginUiState.CameraDenied）。
 * 动效全部标准曲线 ≤360ms（§六）。
 */
@Composable
fun LoginScreen(
    onPaired: () -> Unit,
    onGuest: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairViewModel = hiltViewModel(),
    linkViewModel: LinkViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState
    val linkState = linkViewModel.uiState
    val view = LocalView.current
    val context = LocalContext.current
    var scanning by rememberSaveable { mutableStateOf(false) }
    // 扫码入口来源（Pair=主 CTA 配对码 / Link=扫码加入）：同一 ScanOverlay 复用，onFound
    // 按 ?link= URL 正则分流（LinkViewModel.LINK_CODE_REGEX）——两码互认互不排斥
    var scanMode by rememberSaveable { mutableStateOf(ScanMode.Pair) }

    // 六状态 + link 四步消费：成功过场触觉 + 600ms 停留后导航（pair/link 两机共用）；配对码错误轻震一次（§六触觉三处之二）
    LaunchedEffect(state, linkState) {
        when {
            state == LoginUiState.PairSuccess -> {
                view.hapticConfirm()
                delay(600)
                onPaired()
            }
            linkState is LinkUiState.LinkSuccess -> {
                view.hapticConfirm()
                delay(600)
                onPaired()
            }
            state == LoginUiState.CodeInvalid -> view.hapticTick()
            else -> Unit
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 结构（§3.1）：Column 三段式；品牌区 weight(1f) 吃掉剩余空间，永不挤掉认证区。
        // 规范同节提到 verticalScroll——与 weight(1f) 互斥，按结构图主诉求取 weight 弹性
        // （折叠态总内容 ~600dp 不致溢出，小屏由品牌区留白压缩吸收；IME 空间同理由它吸收）。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .imePadding(),
        ) {
            val entered = remember { MutableTransitionState(false).apply { targetState = true } }

            // ▍品牌区（页面进入动效 240ms；认证区/页脚延迟 60/120ms，总长 ≤360ms）
            AnimatedVisibility(
                visibleState = entered,
                enter = fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 20 },
                modifier = Modifier.weight(1f),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    BrandBlock(Modifier.padding(vertical = 32.dp))
                }
            }

            Spacer(Modifier.height(32.dp)) // 品牌区底 ↔ 主 CTA ≥32dp（weight 弹性兜底）

            // ▍认证区
            AnimatedVisibility(
                visibleState = entered,
                enter = fadeIn(tween(240, delayMillis = 60, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(240, delayMillis = 60, easing = FastOutSlowInEasing)) { it / 20 },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    ScanCta(
                        onClick = { scanning = true },
                        contentDescriptionText = stringResource(R.string.login_cta_scan_cd),
                    )
                    // 相机无权限（§五）：主按钮下方 inline 提示 +「去授权」跳系统设置；
                    // 扫码按钮不置灰（可重试）。触发路径随 M2 权限请求接入生效
                    CameraDeniedRow(visible = state == LoginUiState.CameraDenied)
                    Spacer(Modifier.height(12.dp)) // 主 CTA ↔ 次按钮 12dp
                    // 「扫码加入」次级入口（device-link 通道）：扫电脑端登录二维码 → passkey
                    // 注册 → Bearer 兑换一键完成；与配对码扫码共用 ScanOverlay，正则分流
                    PressableSecondaryButton(
                        label = stringResource(R.string.login_cta_link),
                        onClick = {
                            scanMode = ScanMode.Link
                            scanning = true
                        },
                    )
                    Spacer(Modifier.height(8.dp)) // link 入口 ↔ 手输按钮 8dp（同级紧凑）
                    ManualEntry(
                        state = state,
                        pairingCode = viewModel.pairingCode,
                        baseUrl = viewModel.baseUrl,
                        onCodeInput = viewModel::onCodeInput,
                        onRedeem = viewModel::redeem,
                    )
                    // device-link 链路状态区（四步进行态 / 七类错误就近 banner，复用 ErrorBanner 风格）
                    LinkStatusCard(
                        state = linkState,
                        baseUrl = viewModel.baseUrl,
                        onRetry = { linkViewModel.retry(context) },
                    )
                    Spacer(Modifier.height(16.dp)) // 次按钮 ↔ 辅助说明 16dp
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.login_hint_line1),
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = MaterialTheme.muheExtended.textTertiary,
                        )
                        Text(
                            text = stringResource(R.string.login_hint_line2),
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = MaterialTheme.muheExtended.textTertiary,
                        )
                    }
                    Spacer(Modifier.height(24.dp)) // 辅助说明 ↔ 先逛逛 24dp（组间远距）
                    GhostButton(onClick = onGuest)
                }
            }

            Spacer(Modifier.height(16.dp)) // 先逛逛 ↔ 页脚 ≥16dp

            // ▍页脚（折叠服务器行常驻——自托管诚实性 §七-3）
            AnimatedVisibility(
                visibleState = entered,
                enter = fadeIn(tween(240, delayMillis = 120, easing = FastOutSlowInEasing)),
            ) {
                ServerFooter(
                    uiState = state,
                    baseUrl = viewModel.baseUrl,
                    reachable = viewModel.serverReachable,
                    onBaseUrlInput = viewModel::onBaseUrlInput,
                    onTestConnection = viewModel::testConnection,
                )
            }
        }

        // 扫码成功过场覆盖层（§五）：scrim 60% + 对勾圆徽 scale-in + 600ms 后导航（上方 LaunchedEffect）
        PairingOverlay(visible = state == LoginUiState.PairSuccess)

        // 扫码覆盖层（V1.2 真接线 + device-link 分流）：同一 ScanOverlay 复用，onFound 按
        // ?link= URL 正则分流——URL → LinkViewModel（扫桌面端登录二维码）；其余 → 配对码
        // clean 满 8 位即兑。两入口（主 CTA / 扫码加入）共用此分流，title/hint 按入口切换；
        // 相机被拒仍点亮认证区 inline 行（CameraDeniedRow）
        AnimatedVisibility(visible = scanning, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            ScanOverlay(
                title = stringResource(
                    if (scanMode == ScanMode.Pair) R.string.login_cta_scan else R.string.link_scan_title,
                ),
                hint = stringResource(
                    if (scanMode == ScanMode.Pair) R.string.login_scan_hint else R.string.link_scan_hint,
                ),
                onFound = { raw, _ ->
                    scanning = false
                    if (LinkViewModel.LINK_CODE_REGEX.containsMatchIn(raw)) {
                        linkViewModel.onScannedLinkUrl(raw, context)
                    } else {
                        viewModel.onScannedCode(raw)
                    }
                },
                onDismiss = { scanning = false },
                onDenied = viewModel::onCameraDenied,
            )
        }
    }
}

/** 品牌区：logo 48dp 占位（icon 批次补）/ 品牌名 28sp / slogan 16sp / 隐私句 13sp tertiary */
@Composable
private fun BrandBlock(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // logo 占位：品牌 mark 资产未到，48dp 容器先占位（icon 批次补）
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Spacer(Modifier.height(16.dp)) // logo ↔ 名称 16dp
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium, // 28/36 w600（Type.kt 基线）
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp)) // 名称 ↔ slogan 8dp
        Text(
            text = stringResource(R.string.login_slogan),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp)) // slogan ↔ 隐私句 4dp
        Text(
            text = stringResource(R.string.login_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
        )
    }
}

/**
 * 主 CTA「扫码配对」：primary 填充 56dp pill，label 16sp w600 onPrimary，
 * ripple 默认 + 按压 scale 0.98（§六 90/150ms spring 低刚度）。
 * qr-code 图标属 icons-extended（白名单外不引入），M2 扫码批次补——当前 label 居中；
 * loading 时图标位换 20dp 进度圈（onPrimary），M2 相机启动时接。
 */
@Composable
private fun ScanCta(
    onClick: () -> Unit,
    contentDescriptionText: String,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scanCtaPress",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(pressScale)
            .semantics { contentDescription = contentDescriptionText }, // 无障碍硬线 §四
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        Text(
            text = stringResource(R.string.login_cta_scan),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * 手输配对码：折叠态 = 灰填充 56dp 次按钮（surfaceContainerHigh，label w500 onSurfaceVariant）；
 * 点击展开 = OutlinedTextField（等宽 tnum、自动大写过滤 A-Z0-9、8 位计数暗示，不做分格输入）
 * + 确认 filled-tonal 44dp（primaryContainer/onPrimaryContainer）。
 * 展开动画 animateContentSize 200ms（§六）；错误态 2dp error 边框 + 框下 13sp error 文案，无 shake。
 */
@Composable
private fun ManualEntry(
    state: LoginUiState,
    pairingCode: String,
    baseUrl: String,
    onCodeInput: (String) -> Unit,
    onRedeem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(200, easing = FastOutSlowInEasing)),
    ) {
        if (!expanded) {
            PressableSecondaryButton(
                label = stringResource(R.string.login_cta_manual),
                onClick = { expanded = true },
            )
        } else {
            val isError = state == LoginUiState.CodeInvalid
            // 错误边框 crossfade 150ms（§六）；M3 默认聚焦 2dp primary / 静默 1dp outline 正合 §四
            val unfocusedBorder by animateColorAsState(
                targetValue = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                animationSpec = tween(150),
                label = "codeBorder",
            )
            OutlinedTextField(
                value = pairingCode,
                onValueChange = onCodeInput,
                modifier = Modifier.fillMaxWidth(),
                enabled = state != LoginUiState.Loading,
                isError = isError,
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.login_code_placeholder),
                        color = MaterialTheme.muheExtended.textTertiary,
                    )
                },
                supportingText = {
                    // 错误优先；常态为 8 位计数暗示（不加分格）
                    Text(
                        text = if (isError) {
                            stringResource(R.string.login_error_code_invalid)
                        } else {
                            stringResource(R.string.login_code_count, pairingCode.length)
                        },
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.muheExtended.textTertiary
                        },
                    )
                },
                textStyle = TextStyle(
                    // 等宽 + tnum 逐位对齐（§3.3 配对码输入）
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrect = false,
                ),
                keyboardActions = KeyboardActions(onDone = { onRedeem() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = unfocusedBorder,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
            FilledTonalButton(
                onClick = onRedeem,
                enabled = state != LoginUiState.Loading && pairingCode.length == PairViewModel.CODE_LENGTH,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                if (state == LoginUiState.Loading) {
                    // 加载态：触发按钮内 20dp 圈（§五），label 保留，禁用防双击
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Text(stringResource(R.string.login_code_confirm))
                }
            }
            // 服务器不可达 banner：就近呈现于触发它的控件（确认按钮）正下方（§四/§五）
            ErrorBanner(
                visible = state == LoginUiState.Unreachable,
                text = stringResource(R.string.login_error_unreachable, baseUrl),
                onRetry = onRedeem,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 次按钮通用形态：灰填充 56dp pill + 按压 scale（主/次按钮专用，ghost 只用 ripple） */
@Composable
private fun PressableSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "secondaryPress",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(pressScale),
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge) // 16/24 w500（Type.kt 基线）
    }
}

/** ghost「先逛逛」：透明无边框，15sp w500 textTertiary + chevron 16dp；不用 primary 色（§四纪律） */
@Composable
private fun GhostButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.muheExtended.textTertiary),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.login_guest),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight, // chevron：core 白名单内承载
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 相机无权限 inline 行（§五）：13sp 提示 +「去授权」跳系统应用设置；expandVertically+fadeIn 180ms */
@Composable
private fun CameraDeniedRow(visible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(180, easing = FastOutSlowInEasing)) +
            fadeIn(tween(180, easing = FastOutSlowInEasing)),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.login_camera_denied),
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                context.startActivity(intent)
            }) {
                Text(stringResource(R.string.login_camera_grant))
            }
        }
    }
}

/**
 * 错误 banner（§四）：errorContainer 底 12dp 圆角，13sp onErrorContainer + 重试 text 按钮；
 * expandVertically + fadeIn 180ms（§六），就近呈现于触发控件旁，不用 dialog/snackbar。
 */
@Composable
private fun ErrorBanner(visible: Boolean, text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(180, easing = FastOutSlowInEasing)) +
            fadeIn(tween(180, easing = FastOutSlowInEasing)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.login_retry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * 页脚折叠服务器栏（§四）：折叠 44dp 透明行（状态点 + 「服务器 <host>」+ 展开箭头，整栏可点），
 * 展开 surfaceContainerLow 12dp 面板（base URL 输入 + 测试连接 text + 保存 filled-tonal 44dp），
 * animateContentSize 200ms。保存提交到 ViewModel（M1 内存态——DataStore 不在依赖白名单，
 * 持久化载体归 M1 网络层批次裁定）。ping 失败在面板内就近 banner（redeem 失败由认证区 banner 承载）。
 */
@Composable
private fun ServerFooter(
    uiState: LoginUiState,
    baseUrl: String,
    reachable: Boolean?,
    onBaseUrlInput: (String) -> Unit,
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 展开面板输入用本地草稿，「保存」才提交（保持按钮语义真实）
    var draft by rememberSaveable(baseUrl) { mutableStateOf(baseUrl) }

    // 状态点三态 crossfade 150ms（§六）：灰=未测 / primary=可达 / error=不可达
    val dotColor by animateColorAsState(
        targetValue = when (reachable) {
            null -> MaterialTheme.colorScheme.outline
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(150),
        label = "serverDot",
    )
    val dotDescription = stringResource(
        when (reachable) {
            null -> R.string.login_dot_unknown
            true -> R.string.login_dot_reachable
            false -> R.string.login_dot_unreachable
        },
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (expanded) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .animateContentSize(tween(200, easing = FastOutSlowInEasing)),
    ) {
        // 折叠/展开头行：44dp 触达；clickable 自带 merge descendants，状态点/箭头语义随行朗读（§四无障碍）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape)
                    .semantics { contentDescription = dotDescription },
            )
            Text(
                text = stringResource(R.string.login_server_prefix, baseUrl),
                style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.muheExtended.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.muheExtended.textTertiary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(stringResource(R.string.login_server_address)) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                ErrorBanner(
                    visible = reachable == false && uiState != LoginUiState.Unreachable,
                    text = stringResource(R.string.login_error_unreachable, draft),
                    onRetry = onTestConnection,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onTestConnection) {
                        Text(stringResource(R.string.login_server_test))
                    }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = {
                            onBaseUrlInput(draft)
                            expanded = false
                        },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Text(stringResource(R.string.login_server_save))
                    }
                }
            }
        }
    }
}

/** 配对成功过场覆盖层（§五）：scrim 60% + 56dp 对勾圆徽 scale 0.6→1.0 spring + 「配对成功」文案 */
@Composable
private fun PairingOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = FastOutSlowInEasing)),
        modifier = modifier,
    ) {
        // 对勾圆徽 scale 0.6→1.0（§五 spring 180ms 档）；240ms 取景框描边变色随 M2 接 CameraX
        val badgeScale = remember { Animatable(0.6f) }
        LaunchedEffect(Unit) {
            badgeScale.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            scaleX = badgeScale.value
                            scaleY = badgeScale.value
                        }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.login_pair_success),
                    style = MaterialTheme.typography.bodyLarge, // 16sp
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 触觉（§六仅三处）：配对成功 CONFIRM / 配对码错误轻震（第三处扫码锁定归 M2） */
private fun View.hapticConfirm() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

private fun View.hapticTick() {
    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

/** 扫码入口来源（同屏两入口共用 ScanOverlay，正则分流见 LoginScreen onFound）。 */
private enum class ScanMode { Pair, Link }

/** link 四步分步文案（LinkViewModel.Linking 消费）。 */
@Composable
private fun linkStepText(step: LinkStep): String = when (step) {
    LinkStep.FetchOptions -> stringResource(R.string.link_step_options)
    LinkStep.RegisterPasskey -> stringResource(R.string.link_step_passkey)
    LinkStep.Verify -> stringResource(R.string.link_step_verify)
    LinkStep.Session -> stringResource(R.string.link_step_session)
}

/** link 七类错误文案就近映射（Unreachable 带 baseUrl 参；重试按钮复用 login_retry）。 */
@Composable
private fun linkErrorText(kind: LinkErrorKind, baseUrl: String): String = when (kind) {
    LinkErrorKind.Expired -> stringResource(R.string.link_error_expired)
    LinkErrorKind.Unreachable -> stringResource(R.string.link_error_unreachable, baseUrl)
    LinkErrorKind.NotQr -> stringResource(R.string.link_error_not_qr)
    LinkErrorKind.PasskeyUnsupported -> stringResource(R.string.link_error_passkey)
    LinkErrorKind.PasskeyFailed -> stringResource(R.string.link_error_passkey_fail)
    LinkErrorKind.VerifyFailed -> stringResource(R.string.link_error_verify)
    LinkErrorKind.CredentialExists -> stringResource(R.string.link_error_exists)
}

/**
 * device-link 链路状态区：Linking = surfaceContainerLow 圆角卡（加入账号 + 分步文案 + 小进度圈，
 * animateContentSize 200ms 跟随步进，§六口径）；LinkError = 复用 ErrorBanner（重试从 options
 * 重起全链路，options 码不消费、passkey 未烧时可原码重来；用户取消 passkey 回 Idle 不进此区）。
 */
@Composable
private fun LinkStatusCard(
    state: LinkUiState,
    baseUrl: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is LinkUiState.Linking -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .animateContentSize(tween(200, easing = FastOutSlowInEasing))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (state.profileName.isEmpty()) {
                    stringResource(R.string.link_status_joining_anon)
                } else {
                    stringResource(R.string.link_status_joining, state.profileName)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.muheExtended.textTertiary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = linkStepText(state.step),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.muheExtended.textTertiary,
                )
            }
        }
        is LinkUiState.LinkError -> ErrorBanner(
            visible = true,
            text = linkErrorText(state.kind, baseUrl),
            onRetry = onRetry,
            modifier = modifier.padding(top = 8.dp),
        )
        else -> Unit
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360, heightDp = 720)
@Composable
private fun LoginScreenPreview() {
    // 深色唯一主题（裁定 #1）；VM 直接构造（预览专用 stub，Hilt 不驱动 @Preview）供静态预览。
    // 真实绑定 = net/MuheAuthApi（V0.9 接管，见 viewmodel/AuthApi.kt 交接注记）。
    MuHeTheme {
        LoginScreen(
            onPaired = {},
            onGuest = {},
            viewModel = PairViewModel(PreviewAuthApi),
            linkViewModel = PreviewLinkViewModel,
        )
    }
}

/** 仅 @Preview 用：恒两错误态（同原 StubAuthApi 语义），不参与 Hilt 绑定。 */
private object PreviewAuthApi : AuthApi {
    override suspend fun redeem(code: String): RedeemResult = RedeemResult.InvalidCode
    override suspend fun ping(): Boolean = false
}

/** 仅 @Preview 用：link 链路恒不可达（状态区静默），不参与 Hilt 绑定。 */
private object PreviewLinkApi : LinkApi {
    override suspend fun fetchOptions(code: String): LinkOptionsResult = LinkOptionsResult.Unreachable
    override suspend fun verify(code: String, cid: String, credential: JsonObject): LinkVerifyResult =
        LinkVerifyResult.Unreachable
    override suspend fun mintPairCode(): LinkPairResult = LinkPairResult.Unreachable
    override suspend fun redeemPair(code: String): RedeemResult = RedeemResult.Unreachable
}

private val PreviewLinkViewModel = LinkViewModel(PreviewLinkApi, PasskeyManager())
