package com.muhe.fit.app.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步状态四态广播（V1.6 同步接线；UI 消费面）。
 * 语义对齐 PWA SyncBanner / ServerSync.connectionView 的 tone 分档（ok/wait/off/bad/quiet
 * 收敛为四态）：ok 静默不显、syncing 小字转圈、failed 红字+重试、idle 无话可说。
 * 状态由 SyncScheduler 驱动（SyncClient 的 SyncOutcome → 四态投影），UI 只读不写。
 */
sealed interface SyncUiState {

    /** 访客态 / 从未同步：无话可说（横幅不显）。 */
    data object Idle : SyncUiState

    /** 一次 syncNow 进行中（横幅：转圈小字）。 */
    data object Syncing : SyncUiState

    /** 最近一次同步成功——UI 静默不显（PWA「All synced」banner 为 null 同语义）。 */
    data object Ok : SyncUiState

    /**
     * 失败（横幅：红字 + 重试）。error 随行给后续分流用：
     * status null = 离线（PWA off 档）、401 = 待重配对（bad/lock 档，M1 遗留未分流——
     * strings 三键纪律下暂统一「同步失败」文案，分流随 M2 批次）。
     */
    data class Failed(val error: SyncError) : SyncUiState
}

/** 同步状态总线：SyncScheduler 写，UI（SyncBanner / VM 刷新）读。 */
@Singleton
class SyncStateBus @Inject constructor() {

    private val _state = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    /** 仅调度器发布（同包 internal；UI 侧永远 collect 只读）。 */
    internal fun publish(next: SyncUiState) {
        _state.value = next
    }
}
