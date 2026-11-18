package com.muhe.fit.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.muhe.fit.app.navigation.MuheApp
import com.muhe.fit.app.sync.SyncScheduler
import com.muhe.fit.app.ui.theme.MuHeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 唯一 Activity（单屏 Compose 架构；导航在 navigation/MuheNavHost.kt）。
 * 基类 FragmentActivity：Credential Manager（androidx.credentials）的 Activity Result
 * 流程硬要求 FragmentActivity 宿主——device-link 扫码登录的 passkey 注册依赖；Compose/
 * Hilt/edge-to-edge 全兼容（FragmentActivity extends ComponentActivity）。
 * edge-to-edge + 深色唯一主题（裁定 #1 / C10）——状态栏/导航条底色由 Theme.MuHeFit 与 MuHeTheme 保证。
 */

/** 前台轮询间隔（真源 useStore.js poll：前台期间每 30s 拉一次 rev 比对）。 */
private const val POLL_INTERVAL_MS = 30_000L

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var syncScheduler: SyncScheduler

    // 前台期间 30s 轮询的 Job：ON_START 启动 / ON_STOP 取消（与 STARTED 态对齐——
    // 可见即算前台，权限弹窗等 paused-but-started 态不打断轮询）
    private var pollJob: Job? = null

    // 前台同步触发（V1.6 接线 + 批 8 补齐 M1 遗留 30s 前台轮询）：
    //   ON_RESUME → requestSync("foreground")：首进（onCreate→ON_RESUME）= 启动引导同步
    //     （useStore boot pull 口径）；权限弹窗/分享面板收起同样触发——5s 防抖窗口合一。
    //   ON_START/ON_STOP → 轮询启停：真源 useStore.js poll——前台每 30s 拉一次 rev 比对；
    //     首轮延迟 30s，启动首拉已由 ON_RESUME 的 foreground 承担不重复。
    //     访客态不查 token、协程空转无害：Scheduler.runSync 对无 token 一律 publish(Idle)
    //     返回（零网络成本），按简洁原则不在本层分流。
    private val foregroundSync = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> syncScheduler.requestSync("foreground")
            Lifecycle.Event.ON_START -> {
                pollJob?.cancel()   // 防御性撤旧（生命周期事件严格配对，正常不命中）
                pollJob = lifecycleScope.launch {
                    while (isActive) {
                        delay(POLL_INTERVAL_MS)
                        syncScheduler.requestSync("poll")
                    }
                }
            }
            Lifecycle.Event.ON_STOP -> {
                pollJob?.cancel()
                pollJob = null
            }
            else -> {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(foregroundSync)
        // 系统栏图标固定亮色（dark style = 亮图标）：无参 enableEdgeToEdge 会跟随系统明暗，
        // 系统浅色 + 本 app 深色唯一主题时状态栏图标画在近黑底上不可读（2026-11-18 修复）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MuHeTheme {
                MuheApp()
            }
        }
    }
}
