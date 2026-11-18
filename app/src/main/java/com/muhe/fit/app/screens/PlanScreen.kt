package com.muhe.fit.app.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.muhe.fit.app.R
import com.muhe.fit.app.ui.theme.MuHeTheme
import com.muhe.fit.app.ui.theme.muheExtended

/**
 * 计划 · 周计划骨架（routines 编排入口，android-app.md §3.1）：
 * 周一~周日行 + 每行「安排训练」占位按钮。RoutineEdit 二级页与计划域数据
 * 随后续批次接入后，行内容改为 routine 摘要、按钮进入编排页。
 */
@Composable
fun PlanScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val entryPendingText = stringResource(R.string.toast_entry_pending)
    val weekDays = stringArrayResource(R.array.week_day_labels)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.plan_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        weekDays.forEach { day ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = day,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { Toast.makeText(context, entryPendingText, Toast.LENGTH_SHORT).show() }) {
                        Text(text = stringResource(R.string.plan_arrange))
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.placeholder_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360, heightDp = 720)
@Composable
private fun PlanScreenPreview() {
    // 深色唯一主题（裁定 #1）：MuHeTheme 即全局深色方案
    MuHeTheme {
        PlanScreen()
    }
}
