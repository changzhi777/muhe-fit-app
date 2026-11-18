package com.muhe.fit.app.navigation

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DateRange as DateRangeOutlined
import androidx.compose.material.icons.outlined.Home as HomeOutlined
import androidx.compose.material.icons.outlined.List as ListOutlined
import androidx.compose.material.icons.outlined.Person as PersonOutlined
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.muhe.fit.app.R
import com.muhe.fit.app.screens.CheckInScreen
import com.muhe.fit.app.screens.LibraryScreen
import com.muhe.fit.app.screens.CoachScreen
import com.muhe.fit.app.screens.HomeScreen
import com.muhe.fit.app.screens.WorkoutHistoryScreen
import com.muhe.fit.app.screens.WorkoutScreen
import com.muhe.fit.app.screens.LoginScreen
import com.muhe.fit.app.screens.PlanScreen
import com.muhe.fit.app.screens.ProfileScreen
import com.muhe.fit.app.screens.StatsScreen
import com.muhe.fit.app.screens.SyncBanner

/**
 * M1 屏单导航（android-app.md §三；裁定 #5 2026-11-17 实机反馈修订：统计独立 Tab 回归，
 * 底栏 4 → **5 位**：首页 / 计划 / ▶Start 中央 / 统计 / 我的，首页融合统计撤销）。
 *
 * 四个一级 Tab + ▶Start 中央凸起主行动点（非 Tab，不进路由，点击为动作回调）：
 *   首页 = 今日训练 + streak + 金刚区（screens/HomeScreen，统计摘要区已撤除）
 *   计划 = 周计划骨架（routines 编排入口，screens/PlanScreen）
 *   统计 = 热图/体重/周量/Est.1RM 真数据（screens/StatsScreen，Stats.jsx 移植）
 *   我的 = 聚合页（§3.3，screens/ProfileScreen）
 * 动作库降二级（首页金刚区入口 + RoutineEdit 内嵌选择器）；打卡屏 M2 经金刚区进入（§3.4）。
 * 训练会话屏（Workout）M1 落地后，▶Start 改为进入/恢复会话路由。
 * 登录/配对页（Routes.LOGIN，login-design.md）与教练对话屏（Routes.COACH，M2 骨架）同为
 * 全屏二级路由：由「我的」进入，底栏隐藏；配对成功/访客进入/返回均 popBackStack 回「我的」。
 */
object Routes {
    const val HOME = "home"
    const val PLAN = "plan"
    const val STATS = "stats"
    const val PROFILE = "profile"

    /** 登录/配对页（M1 收尾清单 #3）：二级全屏路由，无底栏 */
    const val LOGIN = "login"

    /** AI 教练对话屏（M2 骨架）：二级全屏路由，无底栏，「我的」页 AI 教练行进入 */
    const val COACH = "coach"

    /** 打卡屏（M2，§3.4）：二级全屏路由，无底栏，首页金刚区「打卡」进入 */
    const val CHECKIN = "checkin"

    /** 训练会话屏（批 2 · MVP）：二级全屏路由，无底栏，▶Start 中央 FAB 进入/恢复 */
    const val WORKOUT = "workout"
    const val LIBRARY = "library"

    /**
     * 训练历史屏（批 6 · 编辑模式 UI 闭环）：二级全屏路由，无底栏，「我的」训练数据组进入。
     * 条目点击 → WorkoutSessionPrefs.setLastEditId 载体（一次性）→ nav(WORKOUT)，
     * WorkoutScreen ON_RESUME 的 load() 优先消费进编辑模式（见 WorkoutViewModel.load()）。
     */
    const val HISTORY = "history"
}

/** 一级 Tab：选中 Filled / 未选中 Outlined（Material 3 规范），label 取双语资源 */
private data class TabSpec(
    val route: String,
    @StringRes val labelRes: Int,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
)

// 图标纪律：只用 material-icons-core（material3 传递依赖，白名单内）；
// CalendarMonth/BarChart 属 icons-extended（白名单外不引入），以 core 的 DateRange 承载
// 日历语义、List 承载统计列表语义（import 别名避让 kotlin.collections.List）。
private val LEFT_TABS = listOf(
    TabSpec(Routes.HOME, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.HomeOutlined),
    TabSpec(Routes.PLAN, R.string.nav_plan, Icons.Filled.DateRange, Icons.Outlined.DateRangeOutlined),
)

// 中央 ▶Start 右侧两 Tab（裁定 #5 修订：统计独立 Tab 回归）
private val RIGHT_TABS = listOf(
    TabSpec(Routes.STATS, R.string.nav_stats, Icons.Filled.ListIcon, Icons.Outlined.ListOutlined),
    TabSpec(Routes.PROFILE, R.string.nav_profile, Icons.Filled.Person, Icons.Outlined.PersonOutlined),
)

/** 一级 Tab 项（两侧共用同一渲染；NavigationBar 子级须 RowScope） */
@Composable
private fun RowScope.TabItem(tab: TabSpec, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
                contentDescription = stringResource(tab.labelRes),
            )
        },
        label = { Text(stringResource(tab.labelRes)) },
    )
}

@Composable
fun MuheApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current
    // ▶Start 占位提示：训练会话屏 M1 落地后改为进入/恢复会话路由
    val startPendingText = stringResource(R.string.toast_start_pending)
    // 一级 Tab 切换（保留各 Tab 状态，回栈首）
    val goTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // insets 用 Scaffold 默认（systemBars）：一级 Tab 内容吃 statusBar padding，
        // 二级页（底栏隐藏）连 navigationBar 也由 content padding 回收——edge-to-edge 下
        // 全屏内容不再画进系统栏底下（此前显式清零导致状态栏遮挡标题行，2026-11-18 修复）
        // login / coach / checkin 为全屏二级页：隐藏底栏，Scaffold padding 自动回收导航条空间
        bottomBar = {
            if (currentRoute != Routes.LOGIN && currentRoute != Routes.COACH && currentRoute != Routes.CHECKIN && currentRoute != Routes.LIBRARY && currentRoute != Routes.WORKOUT && currentRoute != Routes.HISTORY) {
                // FAB 放 NavigationBar 外的 Box：NavigationBar 的 Surface 按 shape 裁切子内容，
                // 凸出上缘的圆弧会被削平（2026-11-19 真机实测 FAB bounds 削平在栏上缘）；
                // Box 不裁切，TopCenter + offset(y=-24dp) 实现 §3.1 中央凸起。
                // ⚠️ FAB 容器勿 fillMaxHeight：bottomBar 槽位是松约束（maxHeight=全屏），
                // fill 会把 Row 撑满全高（布局塌陷根因，2026-11-18）
                Box {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        LEFT_TABS.forEach { tab ->
                            TabItem(tab, selected = currentRoute == tab.route, onClick = { goTab(tab.route) })
                        }
                        // ▶Start 右侧：统计 + 我的（5 Tab 下两侧各 2 个 NavigationBarItem）
                        RIGHT_TABS.forEach { tab ->
                            TabItem(tab, selected = currentRoute == tab.route, onClick = { goTab(tab.route) })
                        }
                    }
                    // ▶Start 中央主行动点（§3.1）：不上 Tab 位、不进路由，凸出底栏上缘；
                    // 有进行中会话时恢复进入（M1 会话屏落地后接 Workout 路由）
                    FloatingActionButton(
                        onClick = { navController.navigate(Routes.WORKOUT) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-24).dp)
                            .size(56.dp),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.nav_start),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable(Routes.HOME) {
                // 首页（V1.6 列顶挂同步状态横幅：syncing 转圈 / failed 红字+重试 / ok 静默，
                // 对齐 PWA SyncBanner 语义；状态源 SyncStateBus，重试经 SyncScheduler 防抖）
                HomeScreen(
                    // 批 8 · 401 分流：token 失效 → SyncBanner VM.reauth 清 token + bus 复位
                    // Idle，此处导航回登录页重新配对（launchSingleTop 防重复入栈）
                    header = {
                        SyncBanner(
                            onReauth = {
                                navController.navigate(Routes.LOGIN) { launchSingleTop = true }
                            },
                        )
                    },
                    onCheckInClick = { navController.navigate(Routes.CHECKIN) },
                )
            }
            composable(Routes.PLAN) { PlanScreen() }
            composable(Routes.STATS) {
                // 统计 Tab（裁定 #5 修订）：真数据版 Stats.jsx 移植；空态按钮回首页开练
                StatsScreen(onGoHome = { goTab(Routes.HOME) })
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onPairClick = { navController.navigate(Routes.LOGIN) },
                    onCoachClick = { navController.navigate(Routes.COACH) },
                    onLibraryClick = { navController.navigate(Routes.LIBRARY) },
                    onHistoryClick = { navController.navigate(Routes.HISTORY) },
                )
            }
            composable(Routes.COACH) {
                // AI 教练对话屏（M2 骨架）：数据源 StubCoachApi demo 两轮，Go 原生服务端随服务端批次实装
                CoachScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.WORKOUT) {
                // 训练会话屏（批 2 · MVP）：▶Start 真实目的地；onFinished 回底栏 Tab。
                // 批 6 · 编辑模式接线：历史屏选中条目 → WorkoutSessionPrefs.setLastEditId
                // 载体（一次性）→ 本屏 ON_RESUME 触发 WorkoutViewModel.load() 优先消费
                //（consumeLastEditId 读后即清 → 编辑档 prefs.save 落盘 + editingWorkoutId
                // 标记，完成时同 id REPLACE 覆盖原条目）。消费点在 load() 内部，NavHost 零竞序处理。
                WorkoutScreen(onFinished = { navController.popBackStack() })
            }
            composable(Routes.CHECKIN) {
                // 打卡屏（M2）：会员码亮码 + attendance 到场记录 + 相机扫码添加（禁手输）
                CheckInScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LIBRARY) {
                // 动作库（M2）：catalogue 5,632 正身 assets 精简包 + 中英搜索（裁定：入口挂「我的」二级页）
                LibraryScreen()
            }
            composable(Routes.HISTORY) {
                // 训练历史屏（批 6 · 编辑模式 UI 闭环）：分组列表 + 条目转编辑
                //（载体 setLastEditId → 训练屏 ON_RESUME load() 优先消费进编辑模式）
                WorkoutHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { navController.navigate(Routes.WORKOUT) },
                )
            }
            composable(Routes.LOGIN) {
                // 扫码已 V1.2 真接线（LoginScreen 内共享 ScanOverlay → PairViewModel.onScannedCode）
                LoginScreen(
                    onPaired = { navController.popBackStack() },
                    onGuest = { navController.popBackStack() },
                 )
            }
        }
    }
}
