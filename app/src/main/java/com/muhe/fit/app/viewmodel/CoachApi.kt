package com.muhe.fit.app.viewmodel

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

// ── AI 教练 API 占位（M2 客户端骨架；契约：api-go/openapi.yaml /api/coach/*） ──
// 与 net/ 包并行属地的边界：MuheApi.kt 不含 coach 方法且本批次不改 net/ 文件，
// 故按 viewmodel/AuthApi.kt 先例自带接口。真网络实现（Retrofit 复用 NetModule 的
// OkHttp/AuthInterceptor）随 M2 服务端批次接线——届时参照 net/MuheAuthApi 接管
// AuthApi 的方式：实现落 net/ 包 + 删 Stub 绑定。
// ⚠️ 与 AuthApi 批次不同：**ViewModel 必须同步改造**（2026-11-18 勘误，原「零改动」声明
// 与事实不符）——真服务是异步 job 队列：202 排队后需 getCoachStatus 轮询（本接口四方法中
// getCoachStatus 目前零调用）、pending 从 JsonElement 解析实体化、提案 14 天过期由轮询结算、
// maxMessageLen 真值随轮询携带；CoachViewModel 的 turn 驱动 demo 状态机在真绑定后必然误触发。
//
// 契约语义：coach 是异步 job 队列，无同步 chat/history 端点——
//   发起 = POST 排队（202，body { job: { id } }），结果经 GET status 轮询
//   （job / pending / last / cap），决策 = POST pending/resolve。
//   完整历史在客户端本地线程（对齐 ck/frontend CoachChat.jsx：S.coach.chat + log）。
// 错误面（403 consent / 409 busy·shared / 429 cap / 503 off）本批不展开，注释标位。

/** 教练状态轮询（operationId getCoachStatus → GET /api/coach/status）。 */
interface CoachApi {

    /**
     * GET /api/coach/status：job 运行态 + 等待中的提案 + 当日预算。
     * 唯一让过期提案退役的入口（14 天过期由首个 status 轮询结算）。
     */
    suspend fun getCoachStatus(): CoachStatusResponse

    /** POST /api/coach/plan：排 create job（intake 开卷）/ refine job（对等待中的提案改词）。 */
    suspend fun postCoachPlan(body: CoachPlanRequest): CoachJobQueuedResponse

    /** POST /api/coach/review：排 review job（对上次复盘以来的训练给变更集）。 */
    suspend fun postCoachReview(body: CoachReviewRequest): CoachJobQueuedResponse

    /**
     * POST /api/coach/pending/resolve：记录提案决策并清掉等待提案。
     * 变更的实际落地由客户端改自身状态走 PUT /api/data 同步，本端点只记账。
     * 无提案等待时是 no-op 不是错误（契约明文）。
     */
    suspend fun postCoachResolve(body: CoachResolveRequest)
}

// ── DTO（字段名 = 契约原键，@SerialName 处 snake_case；透传形状 JsonElement 宽承载） ──

@Serializable
data class CoachStatusResponse(
    // CoachStatus.job / pending / cap / last 一律宽承载（提案 schema additionalProperties: true）
    val job: JsonElement? = null,
    val pending: JsonElement? = null,
    val cap: JsonElement? = null,
    // 本实例保留的留言/refine 上限（200–4000，随轮询走，管理员改动即时生效）
    @SerialName("maxMessageLen") val maxMessageLen: Int? = null,
    val last: JsonElement? = null,
)

@Serializable
data class CoachPlanRequest(
    // CoachIntake 形状宽承载（goal/experience/daysPerWeek/equipment…）
    val intake: JsonElement? = null,
    // 对等待中提案的改词（截 maxMessageLen）
    val refine: String? = null,
    val lang: String? = null,
)

@Serializable
data class CoachReviewRequest(
    val note: String? = null,
    val lang: String? = null,
)

@Serializable
data class CoachResolveRequest(
    val accepted: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
    // true = 整个提案未读即弃
    val dismissed: Boolean? = null,
)

/** 202 CoachJobQueued 响应：{ job: { id } }，id 为 16 hex。 */
@Serializable
data class CoachJobQueuedResponse(
    val job: CoachJobRef? = null,
)

@Serializable
data class CoachJobRef(val id: String)

// ── Stub 实现（诚实标注：M2 骨架演示数据，无网络调用） ──────────────────────
// Go 原生 coach 服务端按 G3 裁定属 M2 服务端批次实装（api-go internal/coach 首版重写）；
// 本 stub 只为对话屏骨架提供可交互的两轮 demo，不冒充真服务。

@Singleton
class StubCoachApi @Inject constructor() : CoachApi {

    override suspend fun getCoachStatus(): CoachStatusResponse {
        delayStub()
        // 空状态：无 job / 无提案；预算与上限留默认（UI 不显示 cap 行）
        return CoachStatusResponse(maxMessageLen = 1000)
    }

    override suspend fun postCoachPlan(body: CoachPlanRequest): CoachJobQueuedResponse {
        delayStub()
        return CoachJobQueuedResponse(CoachJobRef(STUB_JOB_ID))
    }

    override suspend fun postCoachReview(body: CoachReviewRequest): CoachJobQueuedResponse {
        delayStub()
        return CoachJobQueuedResponse(CoachJobRef(STUB_JOB_ID))
    }

    override suspend fun postCoachResolve(body: CoachResolveRequest) {
        delayStub()
    }

    /** 模拟 job 排队到出结果的时延（真服务是分钟级异步轮询，stub 折算为亚秒停顿）。 */
    private suspend fun delayStub() {
        kotlinx.coroutines.delay(STUB_DELAY_MS)
    }

    private companion object {
        const val STUB_JOB_ID = "stub000000000000"
        const val STUB_DELAY_MS = 900L
    }
}

/** ViewModel 层绑定（ViewModelComponent：屏级消费，不进单例图）。 */
@Module
@InstallIn(ViewModelComponent::class)
abstract class CoachApiModule {

    @Binds
    abstract fun bindCoachApi(impl: StubCoachApi): CoachApi
}
