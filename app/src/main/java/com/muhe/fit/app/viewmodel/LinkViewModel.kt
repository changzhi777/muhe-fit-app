package com.muhe.fit.app.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhe.fit.app.net.PasskeyManager
import com.muhe.fit.app.net.PasskeyOutcome
import com.muhe.fit.app.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** device-link 链路四步（步骤进状态供屏面文案分流，见 LoginScreen.linkStepText）。 */
enum class LinkStep {
    /** options（连接服务器换创建参数） */
    FetchOptions,

    /** Credential Manager 注册 passkey（系统弹窗） */
    RegisterPasskey,

    /** verify（烧码 + 签会话 cookie） */
    Verify,

    /** 会话跳板：cookie 面 pair/create → pair/redeem 换 Bearer */
    Session,
}

/** 错误分类（文案映射在 LoginScreen.linkErrorText；Expired 含码失效与 challenge 过期两源）。 */
enum class LinkErrorKind {
    /** 二维码过期/失效 → 「请刷新后重扫」 */
    Expired,

    /** 网络不可达 / 服务器异常 */
    Unreachable,

    /** 扫到的不是登录二维码 */
    NotQr,

    /** 设备不支持 passkey（minSdk 26 但 26-30 需 Play services）→ 降级提示 */
    PasskeyUnsupported,

    /** passkey 创建失败（用户取消归 Cancelled 回 Idle，不进此分类） */
    PasskeyFailed,

    /** verify 校验未通过（attestation 校验失败等） */
    VerifyFailed,

    /** 本机 passkey 已在该账号注册（409 credential-exists） */
    CredentialExists,
}

/**
 * device-link 扫码登录状态机（LoginScreen 第二状态机——与 PairViewModel 六态分治：
 * pair 面语义属 login-design.md §五六态权威，link 链路四步中间态与专属错误自成一体，
 * 仅共用 PairSuccess 过场视觉与 AuthApi 落盘，侵入面最小）。
 */
sealed interface LinkUiState {
    data object Idle : LinkUiState

    /** 进行中：step 供屏面分步文案；profileName 为空显示通用文案 */
    data class Linking(val step: LinkStep, val profileName: String) : LinkUiState

    /** 成功过场：复用 PairSuccess 的对勾 + 600ms 导航 */
    data object LinkSuccess : LinkUiState

    data class LinkError(val kind: LinkErrorKind) : LinkUiState
}

/**
 * 扫桌面端 QR 码登录（device-link 通道）ViewModel。
 * 流程：扫码 URL → 解析 code → options → passkey 注册 → verify（Set-Cookie 落内存 CookieJar）
 * → pair/create（cookie 面）→ pair/redeem 换 Bearer → TokenStore（redeem 实现内落盘）→ 同步首拉。
 *
 * ⚠️ 已知限制（端到端验证阻塞于公网域名，代码完整待 DNS + assetlinks.json 上线后真机验证）：
 * Android 平台 passkey 的 origin 校验走「包名+签名 ↔ RP 域名」数字资产关联（assetlinks.json），
 * 未就绪时 verify/pair-create 会话面在服务端按未验证 origin 处理——表现为 mintPairCode 401，
 * UI 呈「无法连接」，域名上线即通。
 */
@HiltViewModel
class LinkViewModel @Inject constructor(
    private val linkApi: LinkApi,
    private val passkeys: PasskeyManager,
    private val syncScheduler: SyncScheduler? = null,
) : ViewModel() {

    var uiState by mutableStateOf<LinkUiState>(LinkUiState.Idle)
        private set

    /** 重试锚点：最近一次扫码 URL（retry 时从 options 重起全链路——码未烧可在新码上重来） */
    private var lastUrl: String? = null

    fun onScannedLinkUrl(raw: String, context: Context) {
        if (uiState is LinkUiState.Linking || uiState is LinkUiState.LinkSuccess) return
        val activity = context as? FragmentActivity
        if (activity == null) {
            // 理论不可达（单 Activity + FragmentActivity 基类）；防御性归入不支持分类
            uiState = LinkUiState.LinkError(LinkErrorKind.PasskeyUnsupported)
            return
        }
        startLink(raw, activity)
    }

    fun retry(context: Context) {
        val url = lastUrl ?: run {
            uiState = LinkUiState.Idle
            return
        }
        val activity = context as? FragmentActivity
        if (activity == null) {
            uiState = LinkUiState.LinkError(LinkErrorKind.PasskeyUnsupported)
            return
        }
        startLink(url, activity)
    }

    /** 清错误回待机（错误 banner 不带关闭钮，重扫/手输任意输入面自然覆盖——留口给屏面） */
    fun dismissError() {
        if (uiState is LinkUiState.LinkError) uiState = LinkUiState.Idle
    }

    private fun startLink(url: String, activity: FragmentActivity) {
        if (uiState is LinkUiState.Linking || uiState is LinkUiState.LinkSuccess) return
        lastUrl = url
        viewModelScope.launch {
            // ① 码解析：QR 内容 = <ORIGIN>/?link=<code>（服务端 DeviceLinkQR 组装）；
            //    12 字符 b64url（字母数字 + - _），QueryEscape 不改这些字符
            val code = LINK_CODE_REGEX.find(url)?.groupValues?.get(1)
            if (code == null) {
                uiState = LinkUiState.LinkError(LinkErrorKind.NotQr)
                return@launch
            }
            // RP ID 兜底源：扫码 URL host（服务端 options.rp.id 缺失时由 PasskeyManager 回填）
            val rpFallback = runCatching { Uri.parse(url).host }.getOrNull()

            // ② options（码即凭据，不消费——用户取消 passkey 弹窗可原码重试）
            uiState = LinkUiState.Linking(LinkStep.FetchOptions, "")
            val opts = when (val r = linkApi.fetchOptions(code)) {
                is LinkOptionsResult.Ok -> r
                LinkOptionsResult.Invalid -> {
                    uiState = LinkUiState.LinkError(LinkErrorKind.Expired)
                    return@launch
                }
                LinkOptionsResult.Unreachable -> {
                    uiState = LinkUiState.LinkError(LinkErrorKind.Unreachable)
                    return@launch
                }
            }

            // ③ passkey 注册（用户取消 → 静默回待机；码未消费可原码重进）
            if (!passkeys.isSupported(activity)) {
                uiState = LinkUiState.LinkError(LinkErrorKind.PasskeyUnsupported)
                return@launch
            }
            uiState = LinkUiState.Linking(LinkStep.RegisterPasskey, opts.profileName ?: "")
            val credJson = when (val p = passkeys.register(activity, opts.options, rpFallback)) {
                is PasskeyOutcome.Registered -> p.registrationResponseJson
                PasskeyOutcome.Cancelled -> {
                    uiState = LinkUiState.Idle
                    return@launch
                }
                PasskeyOutcome.Unsupported -> {
                    uiState = LinkUiState.LinkError(LinkErrorKind.PasskeyUnsupported)
                    return@launch
                }
                PasskeyOutcome.Failed -> {
                    uiState = LinkUiState.LinkError(LinkErrorKind.PasskeyFailed)
                    return@launch
                }
            }
            val credential = runCatching { Json.parseToJsonElement(credJson).jsonObject }.getOrNull()
            if (credential == null) {
                uiState = LinkUiState.LinkError(LinkErrorKind.PasskeyFailed)
                return@launch
            }

            // ④ verify：烧码 + Set-Cookie（MemoryCookieJar 留存，跳板自动携带）
            uiState = LinkUiState.Linking(LinkStep.Verify, opts.profileName ?: "")
            when (linkApi.verify(code, opts.cid, credential)) {
                LinkVerifyResult.Ok -> Unit
                LinkVerifyResult.Expired -> {
                    uiState = LinkUiState.LinkError(LinkErrorKind.Expired)
                    return@launch
                }
                LinkVerifyResult.VerifyFailed -> {
                    uiState = LinkUiState.LinkError(LinkErrorKind.VerifyFailed)
                    return@launch
                }
                LinkVerifyResult.CredentialExists -> {
                    uiState = LinkUiState.LinkError(LinkErrorKind.CredentialExists)
                    return@launch
                }
                LinkVerifyResult.Unreachable -> {
                    uiState = LinkUiState.LinkError(LinkErrorKind.Unreachable)
                    return@launch
                }
            }

            // ⑤ 会话跳板：cookie 面 pair/create → pair/redeem 换 Android 所需 Bearer
            //   （登录态变化触发源同 pair-redeemed 口径：token 落盘即立拉全档建基座）
            uiState = LinkUiState.Linking(LinkStep.Session, opts.profileName ?: "")
            val pairCode = when (val p = linkApi.mintPairCode()) {
                is LinkPairResult.Ok -> p.code
                LinkPairResult.Unreachable -> {
                    uiState = LinkUiState.LinkError(LinkErrorKind.Unreachable)
                    return@launch
                }
            }
            uiState = when (val r = linkApi.redeemPair(pairCode)) {
                is RedeemResult.Success -> LinkUiState.LinkSuccess.also {
                    syncScheduler?.syncNow("device-link")
                }
                // 刚铸即兑不应 invalid；网络面异常统一「无法连接」就近
                else -> LinkUiState.LinkError(LinkErrorKind.Unreachable)
            }
        }
    }

    companion object {
        /** QR 内容正则：\?link=<code>（服务端 Origin + "/?link=" + QueryEscape(code)）。 */
        val LINK_CODE_REGEX = Regex("""\?link=([A-Za-z0-9_-]+)""")
    }
}
