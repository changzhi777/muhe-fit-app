package com.muhe.fit.app.net

import com.muhe.fit.app.data.TokenStore
import com.muhe.fit.app.viewmodel.AuthApi
import com.muhe.fit.app.viewmodel.RedeemResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ── AuthApi 的真实现（接管 viewmodel/AuthApi.kt 预留的 M1 对接点） ─────────────
// redeem → POST /api/pair/redeem（契约：码即凭据，security []；400 = 码无效/过期/已用，
// 服务端对所有原因同一答复——api.js pairRedeem:323 同口径映射 InvalidCode）；
// 成功即持久化 token（网络层拥有 token 生命周期，ViewModel 零感知）。
// ping → GET /api/config 裸探（无 token、无 body，最便宜的安全端点）；任何 HTTP 应答 =
// 可达（401/403 也算「服务器在」），只有连接层失败 = 不可达（api.js whyUnreachable:262 的
// reached 判定同思想）。探活 10s 上限（login-design 批次预留口径），独立 client 速配——
// 不走 Retrofit（避免为此给共享 client 动超时）。
@Singleton
class MuheAuthApi @Inject constructor(
    private val api: MuheApi,
    private val tokens: TokenStore,
    config: ServerConfig,
    client: OkHttpClient,
) : AuthApi {

    private val probeUrl = config.baseUrl + "api/config"

    // 共享 client 派生（newBuilder 克隆）：只改探活超时，主链路超时口径不受影响
    private val probeClient = client.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun redeem(code: String): RedeemResult = try {
        val r = api.pairRedeem(PairRedeemRequest(code))
        tokens.put(r.token)
        RedeemResult.Success(r.token)
    } catch (e: HttpException) {
        if (e.code() == 400) RedeemResult.InvalidCode else RedeemResult.Unreachable
    } catch (_: IOException) {
        RedeemResult.Unreachable
    } catch (e: Exception) {
        // 取消要透传（屏退出取消 redeem 协程时不得误报 Unreachable）；其余非 JSON 应答
        // （代理页/解析失败）等意外失败：对三态 UI 即「服务器不可达」
        // （对齐 api.js exchange:94 的 bad-response 防御——非本服务的数据不可信）
        if (e is CancellationException) throw e
        RedeemResult.Unreachable
    }

    // 同步 execute：调用方 suspend 链不自动切线程，必须显式落 IO（否则主线程直调
    // 抛 NetworkOnMainThreadException；2026-11-18 修复）
    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            probeClient.newCall(Request.Builder().url(probeUrl).build()).execute().use { true }
        } catch (_: Exception) {
            false
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthApiModule {

    @Binds
    @Singleton
    abstract fun bindAuthApi(impl: MuheAuthApi): AuthApi
}
