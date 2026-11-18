package com.muhe.fit.app.screens

// ── FocusLayout（批 6 · FocusView.jsx 复刻核心）──────────────────────────────
//  真源：ck/frontend/src/views/FocusView.jsx（352 行）——单组聚焦大字视图。
//  本组件 = 参数化纯 UI（不经 Hilt/VM，全部状态由调用方映射进参数；
//  WorkoutScreen 主线批 5 收口后一行接入布局分叉，本批不接线）。
//
//  取 FocusView.jsx 核心交互语义（drops/bursts/sides/cardio/superset/菜单等
//  复杂面不进本批，归后续批）：
//    · 顶部：动作名（titleLarge，热身组随 🔥 徽标）+「第 x/n 组 · 动作 i/n」小字
//      （PWA Prescription 徽章 focus-progress 语义）
//    · 中部：重量 / 次数并排大字（displayLarge + 单位），各配 56dp 圆形 ±
//      （重量步进 ±2.5、次数 ±1；PWA Stepper 的聚焦化简化——真实步进策略
//      weightIncrement/stepOwned 归接线层裁定）
//    · 底部：全宽 88dp 大 done 按钮（PWA「Completed/Complete」语义：已完成态
//      带 ✓、可再点取消）+ 上一个/下一个动作切换行（PWA focus-pager 的
//      动作级简化）
//
//  ⚠️ setIdx/entryIdx 为 0 基序号（对齐 PWA setIdx 语义），显示时 +1 并钳到计数内。
//  setCount/entryCount 为本批新增参数（带默认值，插在 onNextExercise 之后、modifier
//  之前）——「第 x/n 组 · 动作 i/n」的 n 需要总数；既有签名位次一字未动。
//
//  文案：本批 strings.xml 冻结不可动，暂以文件常量承载。收口批次按下表补 focus_*
//  双语键后逐处换 stringResource（中文照抄，英文直译）：─────────────────────────
//    focus_set_progress_fmt = 第 %1$d/%2$d 组 · 动作 %3$d/%4$d
//    focus_warmup_badge     = 🔥
//    focus_weight_label     = 重量
//    focus_reps_label       = 次数
//    focus_weight_unit      = kg
//    focus_reps_unit        = 次
//    focus_adjust_plus      = ＋（按钮面）
//    focus_adjust_minus     = −（按钮面）
//    focus_done             = 完成此组
//    focus_done_completed   = 已完成 ✓
//    focus_prev_exercise    = 上一个动作
//    focus_next_exercise    = 下一个动作
//    focus_weight_plus_cd   = 重量加 2.5 公斤（contentDescription）
//    focus_weight_minus_cd  = 重量减 2.5 公斤（contentDescription）
//    focus_reps_plus_cd     = 次数加 1（contentDescription）
//    focus_reps_minus_cd    = 次数减 1（contentDescription）

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.muhe.fit.app.ui.theme.MuHeTheme
import kotlin.math.floor
import kotlin.math.round

private const val SET_PROGRESS_FMT = "第 %1${'$'}d/%2${'$'}d 组 · 动作 %3${'$'}d/%4${'$'}d"
private const val WARMUP_BADGE = "🔥"
private const val WEIGHT_LABEL = "重量"
private const val REPS_LABEL = "次数"
private const val WEIGHT_UNIT = "kg"
private const val REPS_UNIT = "次"
private const val ADJUST_PLUS = "＋"
private const val ADJUST_MINUS = "−"
private const val DONE_LABEL = "完成此组"
private const val DONE_COMPLETED = "已完成 ✓"
private const val PREV_EXERCISE = "上一个动作"
private const val NEXT_EXERCISE = "下一个动作"
private const val WEIGHT_PLUS_CD = "重量加 2.5 公斤"
private const val WEIGHT_MINUS_CD = "重量减 2.5 公斤"
private const val REPS_PLUS_CD = "次数加 1"
private const val REPS_MINUS_CD = "次数减 1"

private const val WEIGHT_STEP = 2.5
private const val REPS_STEP = 1.0

// fmtNum：步进半值（2.5/1）下最常见的两位小数内展示；整数去尾零（80.0 → "80"），
// 小数保真（2.5 → "2.5"）。PWA fmtNum 的聚焦视图子集。
private fun fmtNum(v: Double): String {
    val r = round(v * 100.0) / 100.0
    return if (r == floor(r)) r.toInt().toString() else r.toString()
}

@Composable
fun FocusLayout(
    entryName: String,
    entryIdx: Int,
    setIdx: Int,
    weight: Double,
    reps: Double,
    warmup: Boolean,
    done: Boolean,
    onAdjust: (String, Double) -> Unit,
    onToggle: () -> Unit,
    onPrevExercise: () -> Unit,
    onNextExercise: () -> Unit,
    setCount: Int = 1,
    entryCount: Int = 1,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 顶部：动作名 + 组进度（PWA Prescription focus-progress 徽章语义）──
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    entryName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (warmup) {
                    Spacer(Modifier.width(8.dp))
                    Text(WARMUP_BADGE, style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            val dispSet = (setIdx + 1).coerceIn(1, setCount.coerceAtLeast(1))
            val dispEntry = (entryIdx + 1).coerceIn(1, entryCount.coerceAtLeast(1))
            // P7c：分母同样钳 ≥1（空会话渲染时显示 1/1 而非 1/0）
            Text(
                String.format(SET_PROGRESS_FMT, dispSet, setCount.coerceAtLeast(1), dispEntry, entryCount.coerceAtLeast(1)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 中部：重量 / 次数并排大字 + 56dp 圆形 ± ──
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BigValue(
                    label = WEIGHT_LABEL,
                    value = fmtNum(weight),
                    unit = WEIGHT_UNIT,
                    onMinus = { onAdjust("w", -WEIGHT_STEP) },
                    onPlus = { onAdjust("w", WEIGHT_STEP) },
                    minusCd = WEIGHT_MINUS_CD,
                    plusCd = WEIGHT_PLUS_CD,
                )
                BigValue(
                    label = REPS_LABEL,
                    value = fmtNum(reps),
                    unit = REPS_UNIT,
                    onMinus = { onAdjust("r", -REPS_STEP) },
                    onPlus = { onAdjust("r", REPS_STEP) },
                    minusCd = REPS_MINUS_CD,
                    plusCd = REPS_PLUS_CD,
                )
            }
        }

        // ── 底部：大 done 按钮（PWA Completed/Complete 语义：再点取消）──
        Button(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            if (done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (done) DONE_COMPLETED else DONE_LABEL,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Spacer(Modifier.height(8.dp))

        // ── 上一个/下一个动作切换（PWA focus-pager 的动作级简化）──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevExercise) {
                Text(PREV_EXERCISE, style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onNextExercise) {
                Text(NEXT_EXERCISE, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// 大字值块：标签小字 → 大字数值（displayLarge 粗体 + 单位下沉基线）→ 56dp 圆形 ± 行
@Composable
private fun BigValue(
    label: String,
    value: String,
    unit: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusCd: String,
    plusCd: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            AdjustButton(ADJUST_MINUS, minusCd, onMinus)
            AdjustButton(ADJUST_PLUS, plusCd, onPlus)
        }
    }
}

// 56dp 圆形调节按钮（纯面符号 ＋/−，零图标依赖——Icons.Add/Remove 归属面不一，
// 以文字承载避 icons-extended 争议，白名单纪律零触碰）
@Composable
private fun AdjustButton(symbol: String, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = contentDescription) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Focus · 未完成组", showBackground = true)
@Composable
private fun FocusLayoutPreview() {
    MuHeTheme {
        FocusLayout(
            entryName = "杠铃卧推",
            entryIdx = 1,
            entryCount = 5,
            setIdx = 2,
            setCount = 4,
            weight = 80.0,
            reps = 10.0,
            warmup = false,
            done = false,
            onAdjust = { _, _ -> },
            onToggle = {},
            onPrevExercise = {},
            onNextExercise = {},
        )
    }
}

@Preview(name = "Focus · 已完成热身组", showBackground = true)
@Composable
private fun FocusLayoutDonePreview() {
    MuHeTheme {
        FocusLayout(
            entryName = "哑铃侧平举（热身）",
            entryIdx = 0,
            entryCount = 5,
            setIdx = 0,
            setCount = 2,
            weight = 10.0,
            reps = 15.0,
            warmup = true,
            done = true,
            onAdjust = { _, _ -> },
            onToggle = {},
            onPrevExercise = {},
            onNextExercise = {},
        )
    }
}
