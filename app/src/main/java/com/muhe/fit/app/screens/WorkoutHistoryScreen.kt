package com.muhe.fit.app.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muhe.fit.app.data.WorkoutSessionPrefs
import com.muhe.fit.app.data.dao.WorkoutDao
import com.muhe.fit.app.lib.StatsCalc
import com.muhe.fit.app.lib.str
import com.muhe.fit.app.sync.SyncStateBus
import com.muhe.fit.app.sync.SyncUiState
import com.muhe.fit.app.ui.theme.MuHeTheme
import com.muhe.fit.app.ui.theme.muheExtended
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 训练历史列表屏（批 6 · 编辑模式 UI 闭环）：
 * Room workouts 全量 → end 降序 → 按 d 分组（今天 / 昨天 / 当年 M月d日；跨年按 yyyy年M月
 * 归并——「更早按月」口径）→ 分组头 + 条目卡（会话名 + 时长 + 完成组数）。完成组数从 raw
 * 解析统计走 StatsCalc.setsDone（StatsViewModel parseRaw 同口径：坏行降级不炸，warmup
 * 不计、per-side ×2）；时长 = entity.end - start（落库白名单字段，免解析 raw）。
 *
 * 条目点击 → WorkoutHistoryViewModel.selectForEdit 写 WorkoutSessionPrefs.setLastEditId
 * 载体（一次性信号）→ onOpen() nav(workout) → WorkoutScreen ON_RESUME 触发
 * WorkoutViewModel.load() 优先消费（consumeLastEditId 读后即清 → 编辑档 prefs.save 落盘 +
 * editingWorkoutId 标记，完成时同 id REPLACE 覆盖原条目）。跨屏不直传 id 的原因：
 * @HiltViewModel 每目的地新实例，历史屏拿不到训练屏的 VM；消费点在 load() 内部，
 * NavHost 零竞序处理。
 *
 * ⚠️ WorkoutHistoryViewModel 与屏同文件（批 6 允许清单只有本文件；常规归属 viewmodel/
 * 包，收口批次归位时一并迁）。Screen/Body 拆分保 @Preview（VM 依赖 Room 无法预览构造，
 * CheckInScreen 先例）。
 *
 * history_* 键清单（本批 strings.xml 冻结：文案以文件常量承载，收口统一换 stringResource）：
 *   history_title history_back_cd history_count_suffix
 *   history_empty history_empty_hint
 *   history_today history_yesterday history_min_suffix history_sets_suffix
 *   history_duration_lt1 history_duration_missing history_untitled
 */

// ── history_* 文案文件常量（中英收口时落 values/ + values-en/，键见上方清单） ──
private const val HISTORY_TITLE = "训练历史"
private const val HISTORY_BACK_CD = "返回"
private const val HISTORY_COUNT_SUFFIX = " 次"
private const val HISTORY_EMPTY = "还没有训练记录"
private const val HISTORY_EMPTY_HINT = "完成一次训练后会出现在这里"
private const val HISTORY_TODAY = "今天"
private const val HISTORY_YESTERDAY = "昨天"
private const val HISTORY_MIN_SUFFIX = " 分钟"
private const val HISTORY_SETS_SUFFIX = " 组"
private const val HISTORY_DURATION_LT1 = "不足 1 分钟"
private const val HISTORY_DURATION_MISSING = "时长未知"
private const val HISTORY_UNTITLED = "训练"

/** 历史条目卡数据（raw 解析产出；解析失败行降级：组数 0 / 名取快照兜底） */
data class WorkoutHistoryEntry(
    val id: String,
    val name: String,
    val durationText: String,
    val doneSets: Int,
)

/** 日期分组（组内 end 降序；组序 = end 降序首遇序，天然新→旧） */
data class WorkoutHistoryGroup(
    val label: String,
    val entries: List<WorkoutHistoryEntry>,
)

data class WorkoutHistoryUiState(
    /** Room 首查完成标记（null = 加载中，不落骨架屏——本地查询 ms 级） */
    val loaded: Boolean,
    val groups: List<WorkoutHistoryGroup>,
    val totalCount: Int,
)

/**
 * 训练历史屏数据源（批 6）。Room 只读走既有 getAll（不建新 Dao——StatsViewModel 同口径）；
 * 同步落地即刷新（syncBus Ok → refresh，StatsViewModel/CheckInViewModel 同款口径）。
 */
@HiltViewModel
class WorkoutHistoryViewModel @Inject constructor(
    private val workoutDao: WorkoutDao,
    private val sessionPrefs: WorkoutSessionPrefs,
    syncBus: SyncStateBus,
) : ViewModel() {

    var uiState by mutableStateOf<WorkoutHistoryUiState?>(null)
        private set

    init {
        refresh()
        viewModelScope.launch {
            syncBus.state.collect { if (it is SyncUiState.Ok) refresh() }
        }
    }

    /** 首帧 + 同步落地聚合（Room suspend DAO 自主安全） */
    fun refresh() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val entities = workoutDao.getAll().sortedByDescending { it.end }
            // 桶键：当年按 d（日粒度），跨年按 yyyy-MM（「更早按月」归并）
            val buckets = LinkedHashMap<String, MutableList<WorkoutHistoryEntry>>()
            val labels = HashMap<String, String>()
            for (e in entities) {
                val raw = runCatching { Json.parseToJsonElement(e.raw) as? JsonObject }.getOrNull()
                val date = runCatching { LocalDate.parse(e.d) }.getOrNull()
                val key = if (date != null && date.year != today.year) {
                    "%04d-%02d".format(date.year, date.monthValue)
                } else {
                    e.d
                }
                labels.getOrPut(key) { groupLabel(key, date, today) }
                val durationMs = e.end - e.start
                val durationText = when {
                    durationMs <= 0L -> HISTORY_DURATION_MISSING
                    durationMs < 60_000L -> HISTORY_DURATION_LT1
                    else -> "${durationMs / 60_000L}$HISTORY_MIN_SUFFIX"
                }
                buckets.getOrPut(key) { mutableListOf() }.add(
                    WorkoutHistoryEntry(
                        id = e.id,
                        name = e.name ?: raw.str("name") ?: HISTORY_UNTITLED,
                        durationText = durationText,
                        doneSets = raw?.let { StatsCalc.setsDone(it) } ?: 0,
                    ),
                )
            }
            uiState = WorkoutHistoryUiState(
                loaded = true,
                groups = buckets.map { (k, v) -> WorkoutHistoryGroup(labels.getValue(k), v.toList()) },
                totalCount = entities.size,
            )
        }
    }

    /** 选中条目转编辑（批 6 跨屏 id 载体）：写 lastEditId → UI 随即 onOpen() nav(workout)。 */
    fun selectForEdit(id: String) {
        sessionPrefs.setLastEditId(id)
    }

    /** 组头标签：今天 / 昨天 / 当年 M月d日 / 跨年 yyyy年M月（d 解析失败回退原值展示） */
    private fun groupLabel(key: String, date: LocalDate?, today: LocalDate): String = when {
        date == null -> key
        date == today -> HISTORY_TODAY
        date == today.minusDays(1) -> HISTORY_YESTERDAY
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
        else -> "${date.year}年${date.monthValue}月"
    }
}

/**
 * 训练历史屏（批 6）：分组列表 + 条目卡转编辑。入口 =「我的」训练数据组「训练历史」行
 * （ProfileScreen onHistoryClick，NavHost 接线）。
 */
@Composable
fun WorkoutHistoryScreen(
    onBack: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutHistoryViewModel = hiltViewModel(),
) {
    // ON_RESUME 刷新（CheckInScreen 同款口径）：编辑完成 popBackStack 回本屏时 VM 存活
    // 不重建，列表停在旧值——前台恢复即 refresh（编辑返回 / 同步后回来都覆盖）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    WorkoutHistoryBody(
        state = viewModel.uiState,
        onBack = onBack,
        onOpen = onOpen,
        onSelect = viewModel::selectForEdit,
        modifier = modifier,
    )
}

/** 屏体（数据参数化：@Preview 不经 Hilt 直接给定状态；ProfileBody/LoginScreen 拆分同款纪律） */
@Composable
private fun WorkoutHistoryBody(
    state: WorkoutHistoryUiState?,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 头部行（CoachScreen 同款：back + 标题；副标题 = 条目总数）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = HISTORY_BACK_CD)
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = HISTORY_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state != null && state.totalCount > 0) {
                    Text(
                        text = "${state.totalCount}$HISTORY_COUNT_SUFFIX",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.muheExtended.textTertiary,
                    )
                }
            }
        }
        when {
            state == null -> Box(Modifier.weight(1f)) // 加载中：Room 冷查询 ms 级，不落骨架屏
            state.groups.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth()) { EmptyState() }
            else -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.groups.forEachIndexed { gi, group ->
                    item(key = "hdr_$gi") { GroupHeader(group.label) }
                    items(group.entries, key = { it.id }) { entry ->
                        HistoryCard(entry = entry, onClick = {
                            onSelect(entry.id) // 先写载体，训练屏 ON_RESUME load() 消费进编辑模式
                            onOpen()
                        })
                    }
                }
            }
        }
    }
}

/** 空态引导（居中，CoachScreen NotConfiguredBody 同款布局） */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = HISTORY_EMPTY,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = HISTORY_EMPTY_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.muheExtended.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 分组头（日期标签） */
@Composable
private fun GroupHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.muheExtended.textTertiary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 0.dp),
    )
}

/** 条目卡：会话名 + 时长 · 完成组数（整卡可点 → 编辑模式） */
@Composable
private fun HistoryCard(entry: WorkoutHistoryEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.durationText} · ${entry.doneSets}$HISTORY_SETS_SUFFIX",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.muheExtended.textTertiary,
            )
        }
    }
}

// ── Preview（Body 数据直喂，不经 Hilt；深色唯一主题包裹） ─────────────────

@Preview(name = "历史列表")
@Composable
private fun WorkoutHistoryBodyPreview() {
    MuHeTheme {
        WorkoutHistoryBody(
            state = WorkoutHistoryUiState(
                loaded = true,
                totalCount = 3,
                groups = listOf(
                    WorkoutHistoryGroup(
                        "今天",
                        listOf(WorkoutHistoryEntry("w1", "推 · 胸肩三头", "52 分钟", 32)),
                    ),
                    WorkoutHistoryGroup(
                        "昨天",
                        listOf(
                            WorkoutHistoryEntry("w2", "拉 · 背二头", "48 分钟", 28),
                            WorkoutHistoryEntry("w3", "腿 · 深蹲日", "61 分钟", 24),
                        ),
                    ),
                ),
            ),
            onBack = {},
            onOpen = {},
            onSelect = {},
        )
    }
}

@Preview(name = "空态")
@Composable
private fun WorkoutHistoryEmptyPreview() {
    MuHeTheme {
        WorkoutHistoryBody(
            state = WorkoutHistoryUiState(loaded = true, groups = emptyList(), totalCount = 0),
            onBack = {},
            onOpen = {},
            onSelect = {},
        )
    }
}
