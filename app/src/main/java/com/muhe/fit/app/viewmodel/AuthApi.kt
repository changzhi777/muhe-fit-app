package com.muhe.fit.app.viewmodel

/** 兑换结果三态：分别驱动「配对成功过场 / 配对码错误 / 服务器不可达」（login-design.md §五） */
sealed interface RedeemResult {
    /** 成功：携带同步 token（token 持久化归 M1 网络层批次） */
    data class Success(val token: String) : RedeemResult

    /** 配对码无效/过期 → 输入框 error 边框 + 框下文案（就近，无 shake） */
    data object InvalidCode : RedeemResult

    /** 网络失败 → 就近 banner + 服务器栏状态点转 error */
    data object Unreachable : RedeemResult
}

/**
 * 认证接口（pair/redeem 是唯一认证路线，login-design.md §八）。
 *
 * ✅ M1 网络层批次已对接：实现在 `net/MuheAuthApi.kt`（redeem → POST /api/pair/redeem +
 * token 落盘 TokenStore；ping → GET /api/config 10s 探活），Hilt 绑定在同文件
 * dagger.Module——原 StubAuthApi/LoginApiModule 占位已按交接注记删除。
 */
interface AuthApi {
    /** 兑换配对码：8 位 A-Z0-9（码表无 0/O/1/I），服务端 clean 归一化 */
    suspend fun redeem(code: String): RedeemResult

    /** 测试连接：服务器栏状态点语义（true=可达绿点 / false=不可达红点） */
    suspend fun ping(): Boolean
}
