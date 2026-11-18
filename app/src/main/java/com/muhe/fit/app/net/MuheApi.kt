package com.muhe.fit.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

// ── M1 面端点（契约：api-go/openapi.yaml，operationId 一一对应） ────────────────
// 语义真源（JS 唯一后端通信层）：ck/frontend/src/lib/api.js —— Bearer 注入、
// 错误携带 status/data、超时口径均以它为准；本接口只声明传输面，协议编排见 sync/SyncClient。
//
// 鉴权：除 pair/redeem（security: []，码即凭据）外全部走 AuthInterceptor 的
// Authorization: Bearer <token>（对齐 api.js remoteHeaders()）。
interface MuheApi {

    /** POST /api/pair/create（operationId pairCreate）：铸造 8 位配对码，5 分钟一次性有效。 */
    @POST("api/pair/create")
    suspend fun pairCreate(): PairCreateResponse

    /** POST /api/pair/redeem（operationId pairRedeem）：配对码换 Bearer token。400 = 码无效/过期/已用。 */
    @POST("api/pair/redeem")
    suspend fun pairRedeem(@Body body: PairRedeemRequest): PairRedeemResponse

    /** GET /api/data（operationId getData）：拉全档；state 为 null = 服务端还没有档（rev 0）。 */
    @GET("api/data")
    suspend fun getData(): DataGetResponse

    /** GET /api/data/rev（operationId getDataRev）：免整档比版本——已登录客户端前台轮询入口。 */
    @GET("api/data/rev")
    suspend fun getDataRev(): DataRevResponse

    /**
     * PUT /api/data（operationId putData）：整档推送（服务端原子写、无合并；5 MiB 上限 413）。
     *
     * 返回 Response<JsonObject> 让 409 透明暴露（对齐前端 api.js「409 的 body 随错误一起送回」）：
     *   200 → { ok: true, ts, rev, wid }
     *   409 → { error: "conflict", rev, state }（state = 服务端当前整档，供客户端合并重推）
     * 409/其他非 2xx 的 body 不进 converter，经 errorBody() 由 SyncClient 手工解析。
     * baseRev/baseWid/stamped 由 SyncClient 以 JsonObject 组装（字段按需出现，契约：
     * baseRev 省略或 null = 无条件覆盖；baseWid 仅在持有 _wid 时携带）。
     */
    @PUT("api/data")
    suspend fun putData(@Body body: JsonObject): Response<JsonObject>

    /** GET /api/config（operationId getConfig）：登录面旗标；coach 键仅已登录调用方携带。 */
    @GET("api/config")
    suspend fun getConfig(): ConfigResponse
}

// ── DTO（字段名 = 契约原键，kotlinx @SerialName 处 snake_case） ────────────────
// 透传形状（user/coach/media/state 条目）一律 JsonElement/JsonObject 宽松承载——
// 服务端把 state 视为不透明（契约 State schema "representative, not enforced"），
// 客户端未知字段透传不丢（sync/StateDoc 的解析同口径）。

@Serializable
data class PairRedeemRequest(val code: String)

@Serializable
data class PairRedeemResponse(
    val token: String,
    // SessionUser（契约 #/components/schemas/SessionUser）；M1 只消费 token，用户块透传给登录批次
    val user: JsonElement? = null,
)

@Serializable
data class PairCreateResponse(val code: String)

@Serializable
data class DataGetResponse(
    val state: JsonElement? = null,
    val rev: Long = 0,
)

@Serializable
data class DataRevResponse(
    val rev: Long = 0,
    // 首次写入前缺失（契约：absent before its first write）
    val wid: String? = null,
)

@Serializable
data class ConfigResponse(
    @SerialName("invite_only") val inviteOnly: Boolean = false,
    @SerialName("allow_guest") val allowGuest: Boolean = false,
    val coach: JsonElement? = null,
    // 仅实例开 PASSWORD_LOGIN=1 时出现
    @SerialName("password_login") val passwordLogin: Boolean? = null,
    // 仅实例设 DEFAULT_LANG 时出现
    @SerialName("default_lang") val defaultLang: String? = null,
    val media: JsonElement? = null,
)
