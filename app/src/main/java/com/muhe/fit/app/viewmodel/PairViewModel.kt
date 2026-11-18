package com.muhe.fit.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.muhe.fit.app.sync.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 登录屏状态机（login-design.md §五 六状态；「访客进入」是导航动作不进状态）。
 * 触觉与 600ms 过场导航由 LoginScreen 的 LaunchedEffect 消费，状态本身保持纯。
 */
sealed interface LoginUiState {
    /** 待机 */
    data object Idle : LoginUiState

    /** 加载中：redeem 请求进行——触发按钮内 20dp 圈 + 禁用防双击（10s 超时由网络层兜） */
    data object Loading : LoginUiState

    /** 配对成功过场：240ms 描边变色（M2 接取景框）+ 对勾 scale-in + 600ms 后导航 */
    data object PairSuccess : LoginUiState

    /** 配对码错误：输入框 error 边框 + 框下文案；无 shake（§六禁用清单）；清空待重输但焦点保留 */
    data object CodeInvalid : LoginUiState

    /** 服务器不可达（redeem 路径）：认证区就近 banner + 状态点转 error */
    data object Unreachable : LoginUiState

    /** 相机无权限：主按钮下方 inline 提示 +「去授权」；触发路径随 M2 相机权限请求接入生效 */
    data object CameraDenied : LoginUiState
}

/**
 * 登录屏 ViewModel：pair/redeem 流程状态（构造注入 [AuthApi]——✅ V0.9 已由 net/MuheAuthApi
 * 真实现接管，本类零改动）。
 */
@HiltViewModel
class PairViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val syncScheduler: SyncScheduler? = null,
) : ViewModel() {

    var uiState by mutableStateOf<LoginUiState>(LoginUiState.Idle)
        private set

    /** 配对码输入：大写 + 只留 A-Z0-9 + 截 8 位（与设计规范 clean 同构；0/O/1/I 由服务端归一） */
    var pairingCode by mutableStateOf("")
        private set

    /** 服务器栏展示的 base URL（host 形态，默认生产地址） */
    var baseUrl by mutableStateOf(DEFAULT_BASE_URL)
        private set

    /** 服务器可达性三态：null=未测灰 / true=可达绿 / false=不可达红（状态点 crossfade 150ms） */
    var serverReachable by mutableStateOf<Boolean?>(null)
        private set

    fun onCodeInput(raw: String) {
        pairingCode = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(CODE_LENGTH)
        // 重输即退回待机：error 边框消失（150ms crossfade），banner 收起
        if (uiState == LoginUiState.CodeInvalid || uiState == LoginUiState.Unreachable || uiState == LoginUiState.CameraDenied) {
            uiState = LoginUiState.Idle
        }
    }

    fun onBaseUrlInput(raw: String) {
        baseUrl = raw.trim()
    }

    /** 兑换配对码；不足 8 位不发起（计数暗示已提示长度） */
    fun redeem() {
        if (uiState == LoginUiState.Loading || pairingCode.length < CODE_LENGTH) return
        uiState = LoginUiState.Loading
        viewModelScope.launch {
            uiState = when (val result = authApi.redeem(pairingCode)) {
                // 登录态变化触发源①：token 落盘即立拉全档（新会话 baseRev 未立 → 首拉建基座，
                // 等效 PWA boot pull；旁路防抖窗口立即执行）
                is RedeemResult.Success -> LoginUiState.PairSuccess.also {
                    syncScheduler?.syncNow("pair-redeemed")
                }
                // 清空待重输，焦点保留在框（设计规范 §五）
                RedeemResult.InvalidCode -> LoginUiState.CodeInvalid.also { pairingCode = "" }
                RedeemResult.Unreachable -> LoginUiState.Unreachable.also { serverReachable = false }
            }
        }
    }

    /** 测试连接：只改状态点与面板内 banner，不打断认证区（失败不惊吓，§七-4） */
    fun testConnection() {
        if (uiState == LoginUiState.Loading) return
        viewModelScope.launch { serverReachable = authApi.ping() }
    }

    /**
     * 扫码（M2 真接线，V0.8 的 startScan demo 已撤）：取景框识别结果 clean 与手输同构
     * （大写→过滤 A-Z0-9→截 8 位）。满 8 位直接进 redeem（配对码即码值本身，§2.4 复用
     * 配对码格式）；扫到的不是配对码 → CodeInvalid（复用既有错误态，无新状态）。
     */
    fun onScannedCode(raw: String) {
        if (uiState == LoginUiState.Loading) return
        val code = raw.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(CODE_LENGTH)
        if (code.length == CODE_LENGTH) {
            pairingCode = code
            redeem()
        } else {
            uiState = LoginUiState.CodeInvalid
        }
    }

    /** 扫码覆盖层内相机权限被拒：点亮认证区 inline 无权限行（login-design.md §五），随 V1.2 生效 */
    fun onCameraDenied() {
        if (uiState == LoginUiState.Loading) return
        uiState = LoginUiState.CameraDenied
    }

    companion object {
        const val CODE_LENGTH = 8
        const val DEFAULT_BASE_URL = "mufit.qingmulife.com"
    }
}
