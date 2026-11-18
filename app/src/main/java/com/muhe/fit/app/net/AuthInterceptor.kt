package com.muhe.fit.app.net

import com.muhe.fit.app.data.TokenStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

// ── Bearer 令牌注入（对齐 ck/frontend/src/lib/api.js 的 remoteHeaders()） ──────
// 配对手机每个请求带 `Authorization: Bearer <token>`（/api/pair/redeem 之外的全部端点）。
// 无 token（访客态）裸发——前端 bootstrap（pairRedeem，api.js:314）同样不带远程凭据，
// 配对码本身就是那次请求的凭据。401 由调用面处理（api.js 把 status 挂上 error 语义，
// 此处不拦截不改写）。
@Singleton
class AuthInterceptor @Inject constructor(private val tokens: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.get() ?: return chain.proceed(chain.request())
        val authorized = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authorized)
    }
}
