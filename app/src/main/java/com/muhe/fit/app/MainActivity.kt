package com.muhe.fit.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.muhe.fit.app.navigation.MuheApp
import com.muhe.fit.app.sync.SyncScheduler
import com.muhe.fit.app.ui.theme.MuHeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 唯一 Activity（单屏 Compose 架构；导航在 navigation/MuheNavHost.kt）。
 * 基类 FragmentActivity：Credential Manager（androidx.credentials）的 Activity Result
 * 流程硬要求 FragmentActivity 宿主——device-link 扫码登录的 passkey 注册依赖；Compose/
 * Hilt/edge-to-edge 全兼容（FragmentActivity extends ComponentActivity）。
 * edge-to-edge + 深色唯一主题（裁定 #1 / C10）——状态栏/导航条底色由 Theme.MuHeFit 与 MuHeTheme 保证。
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var syncScheduler: SyncScheduler

    // 前台恢复触发同步（V1.6 接线，触发源③）：ON_RESUME → requestSync("foreground")。
    // 首次进入（onCreate→ON_RESUME）也落一次 = 启动引导同步（useStore boot pull 口径）；
    // 权限弹窗/分享面板收起同样触发——5s 防抖窗口合一，重复请求无协议副作用（rev 先比再拉）。
    private val foregroundSync = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) syncScheduler.requestSync("foreground")
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
