package com.muhe.fit.app.net

import com.muhe.fit.app.viewmodel.AuthApi
import com.muhe.fit.app.viewmodel.LinkApi
import com.muhe.fit.app.viewmodel.LinkOptionsResult
import com.muhe.fit.app.viewmodel.LinkPairResult
import com.muhe.fit.app.viewmodel.LinkVerifyResult
import com.muhe.fit.app.viewmodel.RedeemResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.POST
import javax.inject.Inject
import javax.inject.Singleton

// ── device-link 扫码登录链路（契约：api-go/openapi.yaml /api/device-link/*，operationId 一一对应）──
// 服务端真源：api-go/internal/auth/handlers.go DeviceLinkOptions/DeviceLinkVerify——
// options 无会话（码即凭据，不消费可重试）；verify 烧码 + Set-Cookie 签会话。
// Cookie 通路：NetModule 的 MemoryCookieJar 把 verify 的 Set-Cookie 留在内存，
// 随后 pair/create 同 host 自动携带（服务端 UserOf cookie 优先于 Bearer，handlers.go:200）。
interface MuheLinkApi {

    /** POST /api/device-link/options（operationId deviceLinkOptions）：400 = link-invalid。 */
    @POST("api/device-link/options")
    suspend fun deviceLinkOptions(@Body body: DeviceLinkOptionsRequest): DeviceLinkOptionsResponse

    /** POST /api/device-link/verify（operationId deviceLinkVerify）：400 = link-invalid/challenge-expired/verification failed；409 = credential-exists。 */
    @POST("api/device-link/verify")
    suspend fun deviceLinkVerify(@Body body: DeviceLinkVerifyRequest): DeviceLinkVerifyResponse
}

// ── DTO（字段名 = 契约原键；options/credential 宽松承载透传，WebAuthn JSON 即官方格式） ──

@Serializable
data class DeviceLinkOptionsRequest(val code: String)

/**
 * options 应答：cid = 服务端 challenge 会话 id（verify 时回传）；options =
 * PublicKeyCredentialCreationOptionsJSON（challenge/rp{id,name}/user{id,name,displayName}/
 * pubKeyCredParams/excludeCredentials/authenticatorSelection{residentKey:required, userVerification:preferred}/attestation:none），
 * additionalProperties: true——逐字段透传给 Credential Manager，未知字段不丢。
 */
@Serializable
data class DeviceLinkOptionsResponse(
    val cid: String,
    val options: JsonObject,
    // 契约字段 id/name = 码所属档案；仅用于 UI 展示（「正在加入账号 X」），缺席安全兜底
    val id: String? = null,
    val name: String? = null,
)

/**
 * verify 请求：credential = androidxCredentials CreatePublicKeyResponse.registrationResponseJson
 * 逐字透传（契约 WebAuthnRegistrationCredential 注记「sent back verbatim」——两者同为
 * RegistrationResponseJSON 官方格式，零映射）。name 可选（契约 maxLength 40），首版不带，
 * 服务端按档案默认命名。
 */
@Serializable
data class DeviceLinkVerifyRequest(
    val code: String,
    val cid: String,
    val credential: JsonObject,
    val name: String? = null,
)

@Serializable
data class DeviceLinkVerifyResponse(
    // SessionUser 块透传（本链路只消费 Set-Cookie，user 仅日志/展示留用）
    val user: JsonObject? = null,
)

// ── LinkApi 的真实现（viewmodel/LinkApi.kt 窄接口；错误映射与屏态解耦） ─────────
// CancellationException 一律透传（屏退出取消时不得误报 Unreachable，MuheAuthApi 同纪律）。
@Singleton
class LinkSessionApi @Inject constructor(
    private val link: MuheLinkApi,
    private val pair: MuheApi,
    private val auth: AuthApi,
    private val json: Json,
) : LinkApi {

    override suspend fun fetchOptions(code: String): LinkOptionsResult = try {
        withContext(Dispatchers.IO) {
            val r = link.deviceLinkOptions(DeviceLinkOptionsRequest(code))
            LinkOptionsResult.Ok(cid = r.cid, options = r.options, profileName = r.name)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        // 400 服务端恒 link-invalid（码错/过期/已用/账号停用同一答复）；429 限频按不可达文案就近
        if (e.code() == 400) LinkOptionsResult.Invalid else LinkOptionsResult.Unreachable
    } catch (_: IOException) {
        LinkOptionsResult.Unreachable
    } catch (_: Exception) {
        LinkOptionsResult.Unreachable
    }

    override suspend fun verify(code: String, cid: String, credential: JsonObject): LinkVerifyResult = try {
        withContext(Dispatchers.IO) { link.deviceLinkVerify(DeviceLinkVerifyRequest(code, cid, credential)) }
        LinkVerifyResult.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        when {
            // 400 三种：link-invalid / challenge-expired（options 与 verify 间隔超时）/ verification failed
            // ——按响应 body 的 code 字段分流（契约 Error schema {error, code}），解析不出按验证失败就近
            e.code() == 400 -> when (errorCode(e)) {
                "link-invalid", "challenge-expired" -> LinkVerifyResult.Expired
                else -> LinkVerifyResult.VerifyFailed
            }
            // 409 credential-exists / passkey-limit：前者常因本机已注册过同一 passkey
            e.code() == 409 -> LinkVerifyResult.CredentialExists
            else -> LinkVerifyResult.Unreachable
        }
    } catch (_: IOException) {
        LinkVerifyResult.Unreachable
    } catch (_: Exception) {
        LinkVerifyResult.Unreachable
    }

    override suspend fun mintPairCode(): LinkPairResult = try {
        withContext(Dispatchers.IO) {
            val r = pair.pairCreate()
            LinkPairResult.Ok(r.code)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        // 401 = cookie 未生效/已失效：服务端 UserOf 先 cookie 后 Bearer，本链路此时无 Bearer——
        // 多因 RP 域名与 assetlinks.json 未就绪（Android origin 校验不过，cookie 虽签发但
        // 会话体系按未验证 origin 拒收），端到端验证随公网域名上线（已知限制，注记在 LinkViewModel）
        LinkPairResult.Unreachable
    } catch (_: IOException) {
        LinkPairResult.Unreachable
    } catch (_: Exception) {
        LinkPairResult.Unreachable
    }

    override suspend fun redeemPair(code: String): RedeemResult = try {
        auth.redeem(code) // token 落盘在 MuheAuthApi.redeem 内（网络层拥有 token 生命周期）
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        RedeemResult.Unreachable
    }

    /** 读 4xx/5xx 响应体的契约 code 字段（Error schema {error, code}），解析失败返 null。 */
    private fun errorCode(e: HttpException): String? = runCatching {
        val raw = e.response()?.errorBody()?.string() ?: return null
        json.parseToJsonElement(raw).jsonObject["code"]?.jsonPrimitive?.content
    }.getOrNull()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LinkApiModule {

    @Binds
    @Singleton
    abstract fun bindLinkApi(impl: LinkSessionApi): LinkApi
}
