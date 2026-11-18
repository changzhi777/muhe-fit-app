package com.muhe.fit.app.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.muhe.fit.app.R
import com.muhe.fit.app.ui.theme.MuHeTheme
import com.muhe.fit.app.ui.theme.muheExtended
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 首页（android-app.md §3.2 纵向顺序；裁定 #5 修订后）：同步横幅 → 问候行 → 今日训练卡
 * （周历条 + today row + Start）→ streak 卡 → 金刚区。
 * 统计摘要四卡已撤除——统计职责移交统计 Tab（screens/StatsScreen，裁定 #5 2026-11-17
 * 实机反馈修订：5 Tab 架构，首页融合撤销）；今日训练卡/streak/金刚区保留。
 * 静态占位（PendingCard）数据域（coach queue / weekTally）随 M1 后续批次接入后逐个替换。
 * [header] 槽位 = 列顶注入位（V1.6 同步横幅 SyncBanner 由 NavHost 注入；默认空保
 * @Preview 零 VM 可构造——hiltViewModel 无法在预览环境实例化）。
 */
@Composable
fun HomeScreen(
    onCheckInClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val startPendingText = stringResource(R.string.toast_start_pending)
    val entryPendingText = stringResource(R.string.toast_entry_pending)
    val weekDays = stringArrayResource(R.array.week_day_labels)
    // 周历条高亮位：LocalDate 周一=1 → 0 基索引；静态占位，接入 weekTally 后由数据驱动。
    // 不用 remember 缓存——跨午夜前台常驻时 remember(Unit) 永远停在昨天（计算本身纳秒级）
    val todayIndex = LocalDate.now().dayOfWeek.value - 1
    val todayText = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val onEntryClick = { Toast.makeText(context, entryPendingText, Toast.LENGTH_SHORT).show() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ⓪ 同步状态横幅（V1.6）：syncing 转圈 / failed 红字+重试 / ok 静默不显
        header()
        // ① 问候行（同步状态点待同步域 M1 接入后补）
        Text(
            text = stringResource(R.string.home_greeting_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = todayText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
        )

        // ② 今日训练卡：周历 7 圆点 + today row + Start
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    weekDays.forEachIndexed { index, day ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == todayIndex) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                    ),
                            )
                            Text(
                                text = day,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (index == todayIndex) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.muheExtended.textTertiary
                                },
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.home_today_workout),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.home_today_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.muheExtended.textTertiary,
                    )
                }
                Button(
                    onClick = { Toast.makeText(context, startPendingText, Toast.LENGTH_SHORT).show() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.nav_start))
                }
            }
        }

        // ③ streak 卡（weekTally 数据源，M1 接入）
        PendingCard(title = stringResource(R.string.home_section_streak))

        // ④ 金刚区：动作库｜历史｜打卡｜肌图（§3.1 动作库降二级；打卡 M2 已接线 → checkin 路由）
        SectionLabel(text = stringResource(R.string.home_section_quick))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEntryClick, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.quick_library), maxLines = 1)
            }
            OutlinedButton(onClick = onEntryClick, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.entry_history), maxLines = 1)
            }
            OutlinedButton(onClick = onCheckInClick, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.quick_checkin), maxLines = 1)
            }
            OutlinedButton(onClick = onEntryClick, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.quick_muscles), maxLines = 1)
            }
        }
    }
}

/** 区块小标题（金刚区共用） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 占位卡（streak 卡用）：标题 + 「M1 后续批次接入」副文本 */
@Composable
private fun PendingCard(title: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.placeholder_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.muheExtended.textTertiary,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360, heightDp = 720)
@Composable
private fun HomeScreenPreview() {
    // 深色唯一主题（裁定 #1）：MuHeTheme 即全局深色方案
    MuHeTheme {
        HomeScreen()
    }
}
