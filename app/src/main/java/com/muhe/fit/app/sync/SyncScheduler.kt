package com.muhe.fit.app.sync

import com.muhe.fit.app.data.TokenStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 同步调度器（V1.6 同步接线）：SyncClient 之上的触发面——把三类触发源汇成防抖合并的
 * syncNow 调用，并把结果投到 SyncStateBus（idle/syncing/ok/failed 四态）。
 *
 * 触发源（语义真源 useStore.js checkRev/poll + PWA SyncBanner）：
 *   ① 登录态变化——PairViewModel redeem 成功 → syncNow("pair-redeemed") 立即拉取
 *     （新会话 baseRev 未立，pullIfChanged 首拉建基座，等效 PWA boot pull）；
 *   ② 数据变更——CheckInViewModel 各写路径落库后 → requestSync("data-change")（防抖合并）；
 *   ③ 前台恢复——MainActivity ON_RESUME → requestSync("foreground")（首次进入 =
 *     启动引导同步，权限弹窗收起同样触发——5s 窗口合一，重复请求无协议副作用）。
 *
 * 防抖语义（实况 2026-11-18 注记）：**静默窗式防抖（trailing）**——收到请求起窗，窗口内每续到
 * 一个请求窗口重新计满 5s，静止满 5s 才执行（持续连发会推迟执行，本地写入连发场景实测足够）；
 * 原因标签 latest-wins。执行期间到达的请求排队下一轮（再吃满一个静默窗）。
 * 本地写入往往连发（加卡→打卡），一窗一次整档推送足够。
 *
 * 访客态（无 token）照 SyncClient 纪律：一切留本地、从不触网，状态停在 Idle。
 */
@Singleton
class SyncScheduler @Inject constructor(
    private val client: SyncClient,
    private val tokens: TokenStore,
    private val bus: SyncStateBus,
) {

    // 进程级私有作用域：单例与进程同寿，无取消面；IO 派发（Room/Retrofit 挂起皆安全）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 请求队列：执行进行中到达的请求排队等下一轮（容量外丢最旧——同步是合并语义，旧因无价值）
    private val requests = Channel<String>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // syncNow（旁路）与防抖路径互斥，防两个 syncNow 并发写档
    private val mutex = Mutex()

    /** 最近一次执行的原因标签（latest-wins 的落点；排障观测用）。 */
    @Volatile
    var lastReason: String? = null
        private set

    init {
        scope.launch {
            while (true) {
                // 等首个请求起窗；窗口内续到的请求并入（latest-wins），窗口静止即执行
                var reason = requests.receive()
                while (true) {
                    val next = withTimeoutOrNull(DEBOUNCE_MS) { requests.receive() } ?: break
                    reason = next
                }
                runSync(reason)
            }
        }
    }

    /** 防抖入口：data-change / foreground / banner-retry 走这里（5s 窗口合一）。 */
    fun requestSync(reason: String) {
        requests.trySend(reason)
    }

    /** 立即同步（旁路防抖窗口）：登录态变化等必须马上拉取的场景（pair-redeemed）。 */
    fun syncNow(reason: String) {
        scope.launch { runSync(reason) }
    }

    private suspend fun runSync(reason: String) {
        if (tokens.get() == null) {
            bus.publish(SyncUiState.Idle)   // 访客态：无话可说，横幅不显
            return
        }
        // 异常护栏（2026-11-18 修复）：本方法是单消费者循环的唯一体内调用，SyncClient 的
        // try 面未覆盖全部路径（buildLocalDoc 读表 / errorBody 读取 / 409 合并落库都可能抛）——
        // 未捕获异常会杀死消费者协程（此后所有 requestSync 静默积压、永不执行），且 Syncing
        // 已发布、终态永不到，横幅永久转圈。此处兜底转 Failed，取消照常上抛。
        try {
            mutex.withLock {
                lastReason = reason
                bus.publish(SyncUiState.Syncing)
                bus.publish(
                    when (val outcome = client.syncNow()) {
                        is SyncOutcome.Failed -> SyncUiState.Failed(outcome.error)
                        else -> SyncUiState.Ok   // UpToDate / Pulled / Pushed / GuestSkip 同归成功档
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bus.publish(SyncUiState.Failed(SyncError(status = null, code = "sync-error", message = e.message)))
        }
    }

    companion object {
        /** 防抖窗口（本地写入连发的合并跨度；PWA poll 30s 更宽，Android 首版取短保即时感）。 */
        const val DEBOUNCE_MS = 5_000L
    }
}
