package com.muhe.fit.app.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.muhe.fit.app.R
import com.muhe.fit.app.sync.SyncError
import com.muhe.fit.app.sync.SyncScheduler
import com.muhe.fit.app.sync.SyncStateBus
import com.muhe.fit.app.sync.SyncUiState
import com.muhe.fit.app.ui.theme.MuHeTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 同步状态横幅（V1.6 同步接线；语义对齐 PWA components/SyncBanner.jsx + ServerSync
 * connectionView 分档）：syncing 转圈小字 / failed 红字 + 重试（点击 requestSync 防抖）/
 * ok 与 idle 静默不显。挂首页顶部（MuheNavHost 经 HomeScreen 的 header 槽位注入）。
 *
 * PWA 的分档细化（离线 vs 服务端错误 vs 401 重配对 vs 访客 quiet）首版收敛为 failed 一档
 * （strings 三键纪律 sync_syncing/sync_failed/sync_retry）；401 → 重配对分流随 M2 批次，
 * error.status 已随 SyncUiState.Failed 随行保留。
 */
@HiltViewModel
class SyncBannerViewModel @Inject constructor(
    bus: SyncStateBus,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    val state = bus.state

    /** 重试走防抖入口（与数据变更/前台恢复同一队列，5s 窗口合一）。 */
    fun retry() = scheduler.requestSync("banner-retry")
}

@Composable
fun SyncBanner(
    modifier: Modifier = Modifier,
    viewModel: SyncBannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SyncBannerContent(state = state, onRetry = viewModel::retry, modifier = modifier)
}

// 屏与状态分离（CheckInScreen 同款拆分保 @Preview——hiltViewModel 无法在预览构造）
@Composable
fun SyncBannerContent(
    state: SyncUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        // ok 静默（PWA「All synced」banner 为 null 同语义）；idle 访客态无话可说
        SyncUiState.Ok, SyncUiState.Idle -> {}
        SyncUiState.Syncing -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.sync_syncing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        is SyncUiState.Failed -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sync_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.sync_retry))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360)
@Composable
private fun SyncBannerSyncingPreview() {
    MuHeTheme {
        SyncBannerContent(state = SyncUiState.Syncing, onRetry = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360)
@Composable
private fun SyncBannerFailedPreview() {
    MuHeTheme {
        SyncBannerContent(
            state = SyncUiState.Failed(SyncError(status = null, code = "offline", message = null)),
            onRetry = {},
        )
    }
}
