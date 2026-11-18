package com.muhe.fit.app.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.muhe.fit.app.R
import com.muhe.fit.app.ui.theme.MuHeTheme
import com.muhe.fit.app.ui.theme.muheExtended
import com.muhe.fit.app.viewmodel.ProfileViewModel

// §3.3 分组项（资源 id 列表；各组落地批次：登录配对/围度 M1，打卡域/Coach M2）
private val TRAINING_ITEMS = listOf(
    R.string.entry_history,
    R.string.profile_progress_photos,
    R.string.profile_measurements,
    R.string.profile_balance,
)

private val SETTINGS_ITEMS = listOf(
    R.string.profile_pref_training,
    R.string.profile_reminders,
    R.string.profile_units_lang,
    R.string.profile_equipment,
    R.string.profile_appearance,
    R.string.profile_coach,
)

private val DATA_ITEMS = listOf(
    R.string.profile_backup,
    R.string.profile_reset,
)

/**
 * 我的 · 聚合页（android-app.md §3.3 分组顺序）：
 * 账号卡（登录态 + 本月训练聚合）→ 训练数据组 → 设置组 → 数据组 → 关于行。
 * M2 数据充实：登录态走 TokenStore、训练历史/围度行副标题读 Room 真数（无表的行为保持占位）、
 * AI 教练行接线 nav(coach)。Admin 入口仅 admin 角色可见（§3.3），角色体系落地后补；
 * 其余项二级行为随所属域批次接入，骨架期统一占位 Toast。
 */
@Composable
fun ProfileScreen(
    onPairClick: () -> Unit,
    onCoachClick: () -> Unit,
    onLibraryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProfileViewModel = hiltViewModel()
    // ON_RESUME 刷新（2026-11-18 修复）：配对成功 popBackStack 回本页时 VM 已存活不重建，
    // 登录态/聚合行停在旧值——前台恢复即 refresh（登录页返回 / 打卡返回 / 同步后回来都覆盖）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ProfileBody(
        paired = viewModel.paired,
        workoutCount = viewModel.workoutCount,
        measurementCount = viewModel.measurementCount,
        monthSessions = viewModel.monthSessions,
        monthStreak = viewModel.monthStreak,
        onPairClick = onPairClick,
        onCoachClick = onCoachClick,
        onLibraryClick = onLibraryClick,
        onLogout = viewModel::logout,
        modifier = modifier,
    )
}

/** 屏体（数据参数化：@Preview 不经 Hilt 直接给定状态；LoginScreen 的 Content 分离同款纪律）。 */
@Composable
private fun ProfileBody(
    paired: Boolean,
    workoutCount: Int,
    measurementCount: Int,
    monthSessions: Int,
    monthStreak: Int,
    onPairClick: () -> Unit,
    onCoachClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entryPendingText = stringResource(R.string.toast_entry_pending)
    val onEntryClick = { Toast.makeText(context, entryPendingText, Toast.LENGTH_SHORT).show() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 账号卡：§3.3 承载 pair/redeem 登录态 + 同步状态 + 本月训练聚合
        item {
            AccountCard(
                paired = paired,
                monthSessions = monthSessions,
                monthStreak = monthStreak,
                onPairClick = onPairClick,
                onLogout = onLogout,
            )
        }

        item { SectionHeader(R.string.profile_section_training) }
        item { ProfileRow(label = stringResource(R.string.library_title), onClick = onLibraryClick) }
        items(TRAINING_ITEMS) { res ->
            ProfileRow(
                label = stringResource(res),
                subtitle = subtitleFor(res, workoutCount, measurementCount),
                onClick = onEntryClick,
            )
        }

        item { SectionHeader(R.string.profile_section_settings) }
        items(SETTINGS_ITEMS) { res ->
            if (res == R.string.profile_coach) {
                // AI 教练行：nav(coach) 二级路由（对话屏骨架，StubCoachApi demo 数据）
                ProfileRow(
                    label = stringResource(res),
                    subtitle = stringResource(R.string.profile_coach_subtitle),
                    onClick = onCoachClick,
                )
            } else {
                ProfileRow(label = stringResource(res), onClick = onEntryClick)
            }
        }

        item { SectionHeader(R.string.profile_section_data) }
        items(DATA_ITEMS) { res -> ProfileRow(label = stringResource(res), onClick = onEntryClick) }

        // 关于行：版本与开源信息（版本规程 V1.0.x；AGPL 义务入口 FORK.md 由 web 侧承载）
        item { SectionHeader(R.string.profile_section_about) }
        item {
            Text(
                text = stringResource(R.string.profile_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.muheExtended.textTertiary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * 训练数据组行副标题：有 Room 表的读真数，无表的保持占位文案（M2 充实口径）。
 * 进展照片 / 结构平衡暂无对应表，随所属批次接入。
 */
@Composable
private fun subtitleFor(res: Int, workoutCount: Int, measurementCount: Int): String = when (res) {
    R.string.entry_history -> stringResource(R.string.profile_row_count, workoutCount)
    R.string.profile_measurements -> stringResource(R.string.profile_row_count, measurementCount)
    else -> stringResource(R.string.profile_row_pending)
}

/**
 * 账号卡：已配对显示配对态 + 本月训练聚合（Room workouts ∪ attendance 日期并集，
 * 访客也有本地数据）+ 退出入口；访客态保持配对按钮直达 login 路由。
 */
@Composable
private fun AccountCard(
    paired: Boolean,
    monthSessions: Int,
    monthStreak: Int,
    onPairClick: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val logoutToast = stringResource(R.string.profile_toast_logout)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (paired) {
                        stringResource(R.string.profile_paired)
                    } else {
                        stringResource(R.string.profile_guest)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (monthSessions > 0) {
                        stringResource(R.string.profile_month_summary, monthSessions, monthStreak)
                    } else {
                        stringResource(R.string.profile_month_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.muheExtended.textTertiary,
                )
                if (paired) {
                    TextButton(onClick = {
                        onLogout()
                        Toast.makeText(context, logoutToast, Toast.LENGTH_SHORT).show()
                    }) {
                        Text(text = stringResource(R.string.profile_logout))
                    }
                } else {
                    TextButton(onClick = onPairClick) {
                        Text(text = stringResource(R.string.profile_login))
                    }
                }
            }
        }
    }
}

/** 分组标题 */
@Composable
private fun SectionHeader(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** 分组列表项：主文案 + 可选副标题（数据充实行） */
@Composable
private fun ProfileRow(label: String, onClick: () -> Unit, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.muheExtended.textTertiary,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360, heightDp = 720)
@Composable
private fun ProfileScreenPreview() {
    // 深色唯一主题（裁定 #1）：MuHeTheme 即全局深色方案；
    // 数据参数化直给（不经 Hilt）：已配对态 + Room 计数示例
    MuHeTheme {
        ProfileBody(
            paired = true,
            workoutCount = 42,
            measurementCount = 17,
            monthSessions = 9,
            monthStreak = 3,
            onPairClick = {},
            onCoachClick = {},
            onLibraryClick = {},
            onLogout = {},
        )
    }
}
