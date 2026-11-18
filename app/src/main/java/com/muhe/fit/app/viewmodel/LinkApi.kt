package com.muhe.fit.app.viewmodel

import kotlinx.serialization.json.JsonObject

/**
 * device-link 扫码登录链路的窄接口（照 AuthApi/CoachApi 先例：接口在 viewmodel、
 * 实现与 Hilt 绑定在 net/，ViewModel 零感知传输层；Preview 用轻 stub）。
 *
 * 链路（契约：api-go/openapi.yaml /api/device-link 与 /api/pair 端点）：
 *   扫码(URL) → options(码即凭据,不消费) → Credential Manager 注册 passkey
 *   → verify(烧码 + Set-Cookie 会话) → cookie 就位跳板 pair/create → pair/redeem 换 Bearer
 */
interface LinkApi {

    /** 第一步：POST /api/device-link/options（operationId deviceLinkOptions）。400 = link-invalid（码错/过期/已用）。 */
    suspend fun fetchOptions(code: String): LinkOptionsResult

    /** 第二步：POST /api/device-link/verify（operationId deviceLinkVerify）。credential 逐字透传 RegistrationResponseJSON。 */
    suspend fun verify(code: String, cid: String, credential: JsonObject): LinkVerifyResult

    /** 跳板一：POST /api/pair/create（cookie 会话面——UserOf 先查 cookie 再查 Bearer，服务端 handlers.go:200）。 */
    suspend fun mintPairCode(): LinkPairResult

    /** 跳板二：POST /api/pair/redeem（复用 AuthApi.redeem：token 落盘在其实现内，本接口零感知）。 */
    suspend fun redeemPair(code: String): RedeemResult
}

/** options 结果：Ok 携带 cid（服务端 challenge 会话 id）与 WebAuthnRegistrationOptions JSON（宽松 JsonObject 承载）。 */
sealed interface LinkOptionsResult {
    data class Ok(val cid: String, val options: JsonObject, val profileName: String?) : LinkOptionsResult

    /** 400 link-invalid：码错/过期/已用（服务端对所有原因同一答复）。 */
    data object Invalid : LinkOptionsResult

    data object Unreachable : LinkOptionsResult
}

/** verify 结果（400 分 challenge-expired/link-invalid → Expired、verification failed → VerifyFailed；409 → CredentialExists）。 */
sealed interface LinkVerifyResult {
    data object Ok : LinkVerifyResult
    data object Expired : LinkVerifyResult
    data object VerifyFailed : LinkVerifyResult
    data object CredentialExists : LinkVerifyResult
    data object Unreachable : LinkVerifyResult
}

/** pair/create 结果：401 = cookie 未生效（多因 RP 域名/assetlinks 未就绪，见 LinkViewModel 已知限制）。 */
sealed interface LinkPairResult {
    data class Ok(val code: String) : LinkPairResult
    data object Unreachable : LinkPairResult
}
