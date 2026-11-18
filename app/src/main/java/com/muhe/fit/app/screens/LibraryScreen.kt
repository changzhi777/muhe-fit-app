package com.muhe.fit.app.screens

import com.muhe.fit.app.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.muhe.fit.app.data.CatalogExercise
import com.muhe.fit.app.ui.theme.MuHeTheme
import com.muhe.fit.app.ui.theme.muheExtended
import com.muhe.fit.app.viewmodel.LibraryUiState
import com.muhe.fit.app.viewmodel.LibraryViewModel

// ── 屏内文案（本批 strings.xml 冻结不可动，暂以文件常量承载。收口批次按下表补 library_*
//    双语键后逐处换 stringResource——中文照抄，英文直译）：─────────────────────────
//    library_title        = 动作库
//    library_count_fmt    = 共 %1$d 个动作
//    library_search_hint  = 搜索动作名称（中 / 英）
//    library_clear_search = 清空搜索（ IconButton contentDescription）
//    library_no_results        = 未找到匹配的动作
//    library_no_results_hint   = 换个关键词，或试试英文名
//    library_empty_catalog     = 动作目录为空

// ── 词表中文标签（canonical 英文词 → 展示词；未收录词原样回显。词表全集 =
//    export-exercises-assets.mjs 运行报告：肌群 50 / 器材 33 / 分类 12，已全收录）──
private val MUSCLE_ZH = mapOf(
    "cardiovascular system" to "心肺", "quadriceps" to "股四头肌", "quads" to "股四头肌",
    "calves" to "小腿", "core" to "核心", "glutes" to "臀肌", "hamstrings" to "腘绳肌",
    "triceps" to "肱三头肌", "chest" to "胸", "shoulders" to "肩", "abs" to "腹肌",
    "abdominals" to "腹肌", "lower abs" to "下腹", "hip flexors" to "屈髋肌",
    "lower back" to "下背", "ankles" to "脚踝", "ankle stabilizers" to "踝稳定肌",
    "obliques" to "腹斜肌", "spine" to "脊柱", "rhomboids" to "菱形肌",
    "adductors" to "内收肌", "inner thighs" to "大腿内侧", "groin" to "腹股沟",
    "pectorals" to "胸大肌", "upper chest" to "上胸", "delts" to "三角肌",
    "deltoids" to "三角肌", "rear deltoids" to "三角肌后束", "upper back" to "上背",
    "forearms" to "前臂", "wrist flexors" to "屈腕肌", "wrist extensors" to "伸腕肌",
    "wrists" to "手腕", "lats" to "背阔肌", "latissimus dorsi" to "背阔肌",
    "biceps" to "肱二头肌", "brachialis" to "肱肌", "grip muscles" to "握力",
    "shins" to "胫前", "soleus" to "比目鱼肌", "rotator cuff" to "肩袖",
    "trapezius" to "斜方肌", "traps" to "斜方肌", "back" to "背",
    "levator scapulae" to "肩胛提肌", "sternocleidomastoid" to "胸锁乳突肌",
    "abductors" to "外展肌", "feet" to "足部", "serratus anterior" to "前锯肌",
    "hands" to "手部",
)
private val EQUIPMENT_ZH = mapOf(
    "body weight" to "自重", "wheel roller" to "健腹轮", "cable" to "绳索",
    "bosu ball" to "波速球", "stationary bike" to "动感单车", "leverage machine" to "杠杆器械",
    "assisted" to "辅助器械", "medicine ball" to "药球", "weighted" to "负重",
    "stability ball" to "瑜伽球", "band" to "弹力带", "resistance band" to "阻力带",
    "ez barbell" to "曲杆", "barbell" to "杠铃", "olympic barbell" to "奥杆",
    "rope" to "训练绳", "dumbbell" to "哑铃", "sled machine" to "雪橇机",
    "elliptical machine" to "椭圆机", "roller" to "泡沫轴", "upper body ergometer" to "上肢功率车",
    "suspension trainer" to "悬挂带", "kettlebell" to "壶铃", "landmine" to "炮筒",
    "weight plate" to "杠铃片", "smith machine" to "史密斯机", "sandbag" to "沙袋",
    "skierg machine" to "滑雪机", "hammer" to "锤", "macebell" to "锤铃",
    "tire" to "轮胎", "trap bar" to "六角杠", "stepmill machine" to "登梯机",
)
private val CATEGORY_ZH = mapOf(
    "calisthenics" to "街健", "strength" to "力量", "mobility" to "灵活性",
    "olympic" to "举重", "plyometrics" to "增强式", "cardio" to "有氧",
    "isometric" to "等长", "yoga" to "瑜伽", "stretching" to "拉伸",
    "pilates" to "普拉提", "rehab" to "康复", "combat" to "格斗",
)

/** 词表映射（小写键比对），未收录词原样回显。 */
private fun zhLabel(value: String, map: Map<String, String>): String =
    map[value.lowercase()] ?: value

/**
 * 动作库（屏单 v2 裁定：动作库降为「我的」二级页，本屏只做屏体与数据）：
 * 顶部常驻搜索 + 全量/过滤结果列表 + 条目详情底部弹层。导航路由与入口行接线归收口批次。
 */
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    LibraryBody(
        state = viewModel.uiState,
        onQueryChange = viewModel::onQueryChange,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryBody(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 选中详情（易失 UI 态留屏面，不进 VM——弹层关闭即弃）
    var selected by remember { mutableStateOf<CatalogExercise?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (state.loaded) {
                Text(
                    text = stringResource(R.string.library_count_fmt, state.results.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.muheExtended.textTertiary,
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = {
                Text(text = stringResource(R.string.library_search_hint), color = MaterialTheme.muheExtended.textTertiary)
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.library_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        when {
            !state.loaded -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.results.isEmpty() -> EmptyState(queryBlank = state.query.isBlank())
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.results, key = { it.id }) { ex ->
                    ExerciseRow(exercise = ex, onClick = { selected = ex })
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    selected?.let { ex ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            ExerciseDetail(ex)
        }
    }
}

/** 结果行：中文名 +（覆盖时）英文名 + 肌群/器材 chips。 */
@Composable
private fun ExerciseRow(exercise: CatalogExercise, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = exercise.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (exercise.nameEn != exercise.name) {
            Text(
                text = exercise.nameEn,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.muheExtended.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            exercise.muscles.map { zhLabel(it, MUSCLE_ZH) }.distinct().take(2).forEach { TagChip(it) }
            exercise.equipment?.let { TagChip(zhLabel(it, EQUIPMENT_ZH)) }
        }
    }
}

/** 详情弹层内容：双语名 + 分类/器材/肌群全量 chips。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseDetail(exercise: CatalogExercise) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = exercise.name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (exercise.nameEn != exercise.name) {
            Text(
                text = exercise.nameEn,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.muheExtended.textTertiary,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            exercise.category?.let { TagChip(zhLabel(it, CATEGORY_ZH)) }
            exercise.equipment?.let { TagChip(zhLabel(it, EQUIPMENT_ZH)) }
            exercise.muscles.map { zhLabel(it, MUSCLE_ZH) }.distinct().forEach { TagChip(it) }
        }
        // 说明/指令文字数据 M2+ 进精简包（现包只含目录面字段，见 export-exercises-assets.mjs 头注）
    }
}

/** 轻量标签 chip（纯展示，肌群/器材/分类共用）。 */
@Composable
private fun TagChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 空态 / 无结果态（查询空白 + 目录空 = 目录缺失；有查询 = 无匹配）。 */
@Composable
private fun EmptyState(queryBlank: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (queryBlank) stringResource(R.string.library_empty_catalog) else stringResource(R.string.library_no_results),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!queryBlank) {
            Text(
                text = stringResource(R.string.library_no_results_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.muheExtended.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1319, widthDp = 360, heightDp = 720)
@Composable
private fun LibraryScreenPreview() {
    // 深色唯一主题（裁定 #1）：MuHeTheme 即全局深色方案
    MuHeTheme {
        LibraryBody(
            state = LibraryUiState(
                query = "",
                loaded = true,
                results = listOf(
                    CatalogExercise(
                        id = "0025",
                        name = "杠铃卧推",
                        nameEn = "barbell bench press",
                        muscles = listOf("pectorals", "triceps", "shoulders"),
                        equipment = "barbell",
                        category = "strength",
                    ),
                    CatalogExercise(
                        id = "0076",
                        name = "杠铃深蹲",
                        nameEn = "barbell squat",
                        muscles = listOf("quads", "glutes", "hamstrings", "calves"),
                        equipment = "barbell",
                        category = "strength",
                    ),
                    CatalogExercise(
                        id = "0213",
                        name = "引体向上",
                        nameEn = "pull up",
                        muscles = listOf("lats", "biceps"),
                        equipment = "body weight",
                        category = "strength",
                    ),
                ),
            ),
            onQueryChange = {},
        )
    }
}
