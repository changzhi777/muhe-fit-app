package com.muhe.fit.app.net

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** passkey 注册结果：Registered 携带 RegistrationResponseJSON（逐字透传给 /api/device-link/verify）。 */
sealed interface PasskeyOutcome {
    data class Registered(val registrationResponseJson: String) : PasskeyOutcome

    /** 用户在系统弹窗取消 → 静默回待机，不算错误。 */
    data object Cancelled : PasskeyOutcome

    /** 设备/Play services 不支持 → 降级提示（改用配对码）。 */
    data object Unsupported : PasskeyOutcome

    /** 创建失败（证书校验、锁屏凭据缺失等）→ 重试提示。 */
    data object Failed : PasskeyOutcome
}

// ── Credential Manager 封装（device-link 扫码登录的 passkey 注册面；白名单外过审项，注记见 libs.versions.toml）──
//
// 支持面（minSdk 26 兼容口径）：API 31+ 原生；26-30 经 credentials-play-services-auth 走
// Google Play services 降级（isSupported=false 时 UI 出 link_error_passkey 文案引导配对码）。
// 宿主硬要求：Credential Manager 的 Activity Result 流程要求 FragmentActivity——MainActivity
// 已改基类（ComponentActivity → FragmentActivity，Compose/Hilt 全兼容）。
//
// options 透传（= 字段映射的零损耗等价物）：服务端 options 即官方 PublicKeyCredentialCreationOptionsJSON
// （go-webauthn opts.Response 序列化，与前端 simplewebauthn startRegistration 输入同形）——
// challenge(base64url) / rp.id / user{id,name,displayName} / pubKeyCredParams / excludeCredentials /
// authenticatorSelection{residentKey:required, userVerification:preferred} / attestation:none
// 逐字段原样，androidx 1.3 的 CreatePublicKeyCredentialRequest(requestJson) 直收此格式；唯一补位 =
// rp.id 缺失时回填扫码 URL host（RP ID 理论与 instance RP_ID 配置一致，URL host 是扫码面自证）。
@Singleton
class PasskeyManager @Inject constructor() {

    /** 可用性预判：宿主为 FragmentActivity 且设备支持 passkey 创建（否则 UI 走配对码降级提示）。 */
    // credentials 1.3.0 无公开 isSupported API（javap 实证，1.5+ 才有）——库自身 API 23+，
    // minSdk 26 全覆盖：宿主判定即过，passkey 真可用性由 register 的运行时异常路径兜底。
    fun isSupported(activity: Activity): Boolean =
        activity is FragmentActivity

    /**
     * 注册本机 passkey：拉起系统凭据创建弹窗（指纹/PIN/图案）。成功返回的
     * registrationResponseJson 与契约 WebAuthnRegistrationCredential「sent back verbatim」同格式。
     */
    suspend fun register(activity: FragmentActivity, options: JsonObject, rpIdFallback: String?): PasskeyOutcome {
        val request = CreatePublicKeyCredentialRequest(ensureRpId(options, rpIdFallback).toString())
        return try {
            val response = CredentialManager.create(activity).createCredential(activity, request)
            (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
                ?.let { PasskeyOutcome.Registered(it) }
                ?: PasskeyOutcome.Failed
        } catch (e: CancellationException) {
            throw e // 协程取消透传（屏退出），不得吞成 Failed
        } catch (e: GetCredentialException) {
            if (e is GetCredentialCancellationException) PasskeyOutcome.Cancelled else PasskeyOutcome.Failed
        } catch (_: Exception) {
            // 非凭据体系异常（超时/平台崩溃等）：对 UI 即「创建失败，请重试」
            PasskeyOutcome.Failed
        }
    }

    /** rp.id 缺失时回填扫码 URL host（返回新 JsonObject，原对象不动）。 */
    private fun ensureRpId(options: JsonObject, rpIdFallback: String?): JsonObject {
        if (rpIdFallback == null) return options
        val rp = options["rp"] as? JsonObject ?: return options
        if (rp.containsKey("id")) return options
        val rpPatched = JsonObject(rp.toMap() + ("id" to JsonPrimitive(rpIdFallback)))
        return JsonObject(options.toMap() + ("rp" to rpPatched))
    }
}
