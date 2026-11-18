package com.muhe.fit.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muhe.fit.app.viewmodel.WorkoutViewModel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults

/**
 * 休息计时 dock（批 2 · Workout 屏；RestTimer.jsx 一栏两义 MVP 版——rest only，work hold 批 3）。
 * 底部常驻浮层：剩余 mm:ss 大字（tnum）+ ±15s + 暂停/继续 + Skip。
 * 纯 UI：状态由 WorkoutViewModel 持有，本组件只渲染。
 */
@Composable
fun RestTimerDock(
    timer: WorkoutViewModel.RestTimer,
    onSkip: () -> Unit,
    onAdjust: (Int) -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (timer.paused) "⏸" else "休息",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = fmtDock(timer.remainSec),
                        style = MaterialTheme.typography.displaySmall,
                        color = if (timer.paused) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onAdjust(-15) }) { Text("-15s") }
                    TextButton(onClick = { onAdjust(15) }) { Text("+15s") }
                    TextButton(onClick = onPause) { Text(if (timer.paused) "继续" else "暂停") }
                    Button(
                        onClick = onSkip,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("跳过") }
                }
            }
        }
    }
}

/** mm:ss（tnum 由调用处 TextStyle 承担；fmtRest.js 同语义的 dock 本地版）。 */
private fun fmtDock(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
