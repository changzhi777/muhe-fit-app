package com.muhe.fit.app.data

import android.content.Context
import android.content.SharedPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

// ── 会话令牌存取（同步协议客户端半的凭据面） ─────────────────────────────────
// 语义对照：ck/frontend/src/lib/api.js 的 remoteBase/remoteToken —— 配对手机以
// `Authorization: Bearer <token>` 访问服务端（POST /api/pair/redeem 换得，契约
// api-go/openapi.yaml /api/pair/redeem：HMAC 签名会话令牌，有效期 SESSION_DAYS）。
// 访客态 = 无 token = 一切留本地、从不触网（SyncClient 以 null 判定跳过同步）。
interface TokenStore {

    /** 当前会话令牌；null = 访客态（未配对）。 */
    fun get(): String?

    /** 配对成功后落盘（pair/redeem 200 的 token 字段）。 */
    fun put(token: String)

    /** 退出/撤销配对。 */
    fun clear()
}

// SharedPreferences 实现（M1）。
// ⚠️ M2 升级项（裁定既有口径）：EncryptedSharedPreferences 依赖 androidx.security:security-crypto，
// 白名单外依赖，按裁定延后——升级时仅替换本实现，TokenStore 接口与注入面零改动。
@Singleton
class PrefsTokenStore @Inject constructor(@ApplicationContext context: Context) : TokenStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("muhe_auth", Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY_TOKEN, null)

    override fun put(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private companion object {
        // key 前缀 muhe_token（M2 换加密存储时沿用同一键名，迁移零成本）
        const val KEY_TOKEN = "muhe_token"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TokenStoreModule {

    @Binds
    @Singleton
    abstract fun bindTokenStore(impl: PrefsTokenStore): TokenStore
}
