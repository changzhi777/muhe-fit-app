package com.muhe.fit.app.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ── 网络栈装配（依赖白名单裁定 #12：Retrofit + OkHttp + kotlinx-serialization） ──
@Module
@InstallIn(SingletonComponent::class)
object NetModule {

    /** 宽松 JSON：服务端把 state 视为不透明（契约 State schema），未知字段透传不丢。 */
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun okHttpClient(auth: AuthInterceptor, cookies: MemoryCookieJar): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth)
        .cookieJar(cookies)
        // 超时口径对齐前端 api.js：GET 20s / PUT（整档）60s（api.js:45 TIMEOUT_GET_MS/TIMEOUT_MS）。
        // M1 简化：call 统一 60s——GET 偏宽无协议影响，按调用面收紧留给 M1 后续批次
        // （Retrofit @Tag 或 OkHttp per-call timeout 属性）。
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    // 日志扩展点：okhttp-logging-interceptor 不在依赖白名单（libs.versions.toml 只有 okhttp 主件），
    // 排障期日志先经 adb logcat / 代理抓包；如需 HttpLoggingInterceptor 须先过白名单裁定。

    /**
     * Retrofit：baseUrl 在首次 provision 时从 ServerConfig 读取（进程内改址不重建本实例，
     * 见 ServerConfig.setBaseUrl 的 M1 批次注记——登录批次接入服务器栏时一并处理）。
     */
    @Provides
    @Singleton
    fun retrofit(config: ServerConfig, client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun muheApi(retrofit: Retrofit): MuheApi = retrofit.create(MuheApi::class.java)

    /** device-link 面（/api/device-link 端点，与 MuheApi 同 baseUrl = ServerConfig）。 */
    @Provides
    @Singleton
    fun muheLinkApi(retrofit: Retrofit): MuheLinkApi = retrofit.create(MuheLinkApi::class.java)
}

/**
 * 进程内存 CookieJar：按 host 分桶、按 cookie 名覆盖（同名新值胜），读取按 okhttp
 * Cookie.matches 过滤（path/domain 语义；过期过滤省略——内存 jar 仅 device-link
 * 一次性链路使用，且 okhttp Cookie 无 hasExpired 方法，进程生命周期即会话生命周期）。全收不筛——device-link 是一次性
 * 链路，会话 cookie 只有 verify 一处来源；sync 日常响应不发 Set-Cookie，不受影响。
 */
@Singleton
class MemoryCookieJar @Inject constructor() : CookieJar {

    private val lock = Any()
    private val byHost = HashMap<String, LinkedHashMap<String, Cookie>>() // host → (name → cookie)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val bucket = byHost.getOrPut(url.host) { LinkedHashMap() }
            for (c in cookies) bucket[c.name] = c
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        byHost[url.host]?.values?.filter { c -> c.matches(url) } ?: emptyList()
    }
}
