package com.muhe.fit.app.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muhe.fit.app.R
import com.muhe.fit.app.lib.StatsCalc
import com.muhe.fit.app.lib.StatsCalc.BwPoint
import com.muhe.fit.app.ui.theme.MuHeTheme
import com.muhe.fit.app.ui.theme.muheExtended
import com.muhe.fit.app.viewmodel.StatsUiState
import com.muhe.fit.app.viewmodel.StatsViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 统计 Tab（Stats.jsx 结构移植，裁定 #5 修订：统计回归独立 Tab，5 Tab 架构）：
 * 汇总瓦片 → 训练热图（近12周）→ 体重趋势 → 周训练量 → 重点动作 Est.1RM → 肌图占位。
 * 聚合在 StatsCalc 纯函数 + StatsViewModel（Room 只读），图表 Canvas/Grid 自绘零新依赖。
 * BodyMap 三视图（肌平衡/疲劳/力量）本批占位卡——人体 SVG 移植随 M2 后段批次。
 * Screen+Body 拆分保 @Preview（CoachScreen/LoginScreen 同纪律）。
 *
 * 双语键清单（P9 批 B 收口时落 strings.xml / values-en，本批 strings 禁触以文件常量承载，
 * LibraryScreen V1.4 先例）：`stats_tile_streak_weeks` = 「连续周数」 / "Week streak"
 * （JS 真源 Stats.jsx:642 文案即 t('Week streak')）；现有 stats_tile_streak（「连续天数」）
 * 因瓦片换周口径（lib/Streak.kt）暂无消费方，键保留不删（不碰现有键纪律）。
 */

@Composable
fun StatsScreen(
    onGoHome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: StatsViewModel = hiltViewModel()
    StatsBody(state = viewModel.uiState, onGoHome = onGoHome, modifier = modifier)
}

/** 屏体（参数化：@Preview 直接给定状态，不经 Hilt） */
@Composable
private fun StatsBody(
    state: StatsUiState?,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        // 首帧聚合未回（Room suspend 快，通常一闪而过）
        state == null -> Box(modifier = modifier.fillMaxSize())
        // 空态：无训练记录引导去首页开练
        !state.hasWorkouts -> EmptyBody(onGoHome = onGoHome, modifier = modifier)
        else -> ContentBody(state = state, modifier = modifier)
    }
}

@Composable
private fun ContentBody(state: StatsUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.stats_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.stats_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
        )

        // ① 汇总瓦片（Stats.jsx tiles：总训练/本月/连续周/30天体重变化）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = stringResource(R.string.stats_tile_workouts),
                value = state.totalWorkouts.toString(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stats_tile_month),
                value = state.monthWorkouts.toString(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                // P9 批 B：瓦片换周口径（streakWeeks），label 暂文件常量（键清单见头注）
                label = stringResource(R.string.stats_tile_streak_weeks),
                value = state.streakWeeks.toString(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stats_tile_weight),
                value = state.bwDelta?.let { stringResource(R.string.stats_value_kg, it) } ?: "–",
                modifier = Modifier.weight(1f),
            )
        }

        // ② 训练热图：近12周，按当日完成组数四分位着色（Heatmap.jsx 语义）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.stats_heatmap_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.stats_heatmap_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.muheExtended.textTertiary,
                )
                HeatGrid(columns = state.heatColumns)
                HeatLegend()
            }
        }

        // ③ 体重完整趋势（BodyweightDao 全量 → Canvas 折线，LineChart.jsx 数据形状）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.stats_weight_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    state.bwPoints.lastOrNull()?.let { last ->
                        Text(
                            text = stringResource(
                                R.string.stats_weight_latest,
                                stringResource(R.string.stats_value_kg, StatsCalc.fmtNum(last.weightKg)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.muheExtended.textTertiary,
                        )
                    }
                }
                if (state.bwPoints.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stats_weight_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.muheExtended.textTertiary,
                    )
                } else {
                    WeightChart(
                        points = state.bwPoints,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                }
            }
        }

        // ④ 周训练量：本周 + 近四周总组数趋势（肌群细分待 M2+ catalogue，注记在 StatsCalc）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.stats_volume_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.stats_volume_sets, state.currentWeekSets),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.weekRows.all { it.sets == 0 }) {
                    Text(
                        text = stringResource(R.string.stats_volume_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.muheExtended.textTertiary,
                    )
                } else {
                    // 循环外一次算好（原来在 forEachIndexed 体内每行重算 O(n) max）
                    val maxSets = state.weekRows.maxOf { it.sets }.coerceAtLeast(1)
                    state.weekRows.forEachIndexed { index, week ->
                        val weeksAgo = state.weekRows.size - 1 - index
                        val label = if (weeksAgo == 0) {
                            stringResource(R.string.stats_volume_this_week)
                        } else {
                            stringResource(R.string.stats_volume_weeks_ago, weeksAgo)
                        }
                        VolumeRow(
                            label = label,
                            sets = week.sets,
                            fraction = week.sets / maxSets.toFloat(),
                            highlight = week.isCurrent,
                        )
                    }
                }
            }
        }

        // ⑤ 重点动作 Est.1RM：每组动作最新会话 → epley 估算，降序
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.stats_onerm_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.estimates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stats_onerm_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.muheExtended.textTertiary,
                    )
                } else {
                    state.estimates.take(ONERM_ROWS).forEach { lift ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lift.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val dateText = lift.date.format(DateTimeFormatter.ofPattern("M/d"))
                                Text(
                                    text = stringResource(
                                        R.string.stats_onerm_detail,
                                        StatsCalc.fmtNum(lift.weight),
                                        StatsCalc.fmtNum(lift.reps),
                                    ) + " · " + dateText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.muheExtended.textTertiary,
                                )
                            }
                            Text(
                                text = stringResource(R.string.stats_value_kg, StatsCalc.fmtNum(lift.est)),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        // ⑥ 肌图占位卡：BodyMap 三视图（肌平衡/疲劳/力量）人体 SVG 随 M2 后段移植
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.stats_bodymap_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.stats_bodymap_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.muheExtended.textTertiary,
                )
            }
        }
    }
}

/** 空态：无训练记录 → 引导去首页开练 */
@Composable
private fun EmptyBody(onGoHome: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.stats_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.stats_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.muheExtended.textTertiary,
                )
                Button(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.stats_empty_go_home))
                }
            }
        }
    }
}

/** 汇总瓦片：标签 + 主数值 */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.muheExtended.textTertiary,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** 热图格子：5 档 primary 透明度（Heatmap.jsx l0-l4 对应） */
@Composable
private fun HeatGrid(columns: List<List<StatsCalc.HeatCell>>, modifier: Modifier = Modifier) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val primary = MaterialTheme.colorScheme.primary
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        columns.forEach { column ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                column.forEach { cell ->
                    val color = when (cell.level) {
                        0 -> empty
                        1 -> primary.copy(alpha = 0.25f)
                        2 -> primary.copy(alpha = 0.45f)
                        3 -> primary.copy(alpha = 0.70f)
                        else -> primary
                    }
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color),
                    )
                }
            }
        }
    }
}

/** 热图图例：少 → 多 */
@Composable
private fun HeatLegend(modifier: Modifier = Modifier) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val primary = MaterialTheme.colorScheme.primary
    val levels = listOf(empty, primary.copy(alpha = 0.25f), primary.copy(alpha = 0.45f), primary.copy(alpha = 0.70f), primary)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.stats_legend_less),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
        )
        levels.forEach { color ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
        Text(
            text = stringResource(R.string.stats_legend_more),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
        )
    }
}

/**
 * 体重折线（LineChart.jsx 数据形状的 Canvas 自绘半）：x 按称重日期序均分（JS 按时间轴，
 * 首版均分简化注记在案），y 轴 12% 留白 + 平线时 ±1 撑开（LineChart.jsx:56-59）。
 */
@Composable
private fun WeightChart(points: List<BwPoint>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        if (points.size == 1) {
            drawCircle(lineColor, radius = 4.dp.toPx(), center = center)
            return@Canvas
        }
        val ys = points.map { it.weightKg }
        var ymin = ys.min(); var ymax = ys.max()
        if (ymin == ymax) { ymin -= 1; ymax += 1 }
        val pad = (ymax - ymin) * 0.12
        ymin -= pad; ymax += pad

        val w = size.width
        val h = size.height
        val margin = 6.dp.toPx()
        val plotW = w - margin * 2
        val plotH = h - margin * 2
        val xs = points.mapIndexed { i, _ -> margin + plotW * i / (points.size - 1) }
        fun yOf(v: Double): Float = (margin + ((ymax - v) / (ymax - ymin) * plotH)).toFloat()

        val path = Path()
        points.forEachIndexed { i, p ->
            val x = xs[i]
            val y = yOf(p.weightKg)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // 末点实心圆（LineChart.jsx:153 last dot 口径）
        drawCircle(
            color = lineColor,
            radius = 4.dp.toPx(),
            center = Offset(xs.last(), yOf(points.last().weightKg)),
        )
    }
}

/** 周训练量行：标签 + 比例条 + 组数（本周高亮 primary，往周弱化） */
@Composable
private fun VolumeRow(label: String, sets: Int, fraction: Float, highlight: Boolean) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val bar = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .weight(2f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(track),
        ) {
            if (sets > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0.05f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bar),
                )
            }
        }
        Text(
            text = stringResource(R.string.stats_volume_sets, sets),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

private const val ONERM_ROWS = 8

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360, heightDp = 880)
@Composable
private fun StatsScreenPreview() {
    // 深色唯一主题（裁定 #1）：MuHeTheme 即全局深色方案；
    // 直接给定状态（不经 Hilt），数据 = 双会话样例，覆盖热图/折线/周量/1RM 全区块
    val today = LocalDate.now()
    val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong())
    val tally = linkedMapOf(
        monday to 18, monday.plusDays(2) to 14, monday.plusDays(4) to 22,
        monday.minusDays(7) to 16, monday.minusDays(5) to 12,
    )
    val columns = StatsCalc.heatGrid(tally, today, weeks = 12)
    MuHeTheme {
        StatsBody(
            state = StatsUiState(
                hasWorkouts = true,
                totalWorkouts = 42,
                monthWorkouts = 11,
                streakWeeks = 5,
                bwDelta = "-0.8",
                heatColumns = columns,
                weekRows = StatsCalc.weeklySetTotals(tally, today, weeks = 4),
                currentWeekSets = 54,
                estimates = listOf(
                    StatsCalc.LiftEstimate("深蹲", 116.7, 100.0, 5.0, today.minusDays(2)),
                    StatsCalc.LiftEstimate("卧推", 80.0, 60.0, 10.0, today.minusDays(4)),
                ),
                bwPoints = listOf(
                    BwPoint(today.minusDays(24), 82.4),
                    BwPoint(today.minusDays(17), 81.2),
                    BwPoint(today.minusDays(10), 80.5),
                    BwPoint(today.minusDays(3), 79.6),
                ),
            ),
            onGoHome = {},
        )
    }
}
