package com.muhe.fit.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muhe.fit.app.data.CatalogExercise
import com.muhe.fit.app.data.ExercisesRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 动作库屏状态：搜索词 + 当前结果集 + 目录装载标记（首帧短暂 loading）。 */
data class LibraryUiState(
    val query: String = "",
    val results: List<CatalogExercise> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * 动作库屏 ViewModel：数据面 = ExercisesRepo（assets 静态目录，无网络/无 Room）。
 * 检索即时过滤（5,632 条内存 contains ms 级，防抖省略即裁定口径），在途检索 Job 取消
 * 保结果顺序 = 最后输入者胜，连打无乱序竞争。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(private val repo: ExercisesRepo) : ViewModel() {

    var uiState by mutableStateOf(LibraryUiState())
        private set

    private var searchJob: Job? = null

    init {
        runSearch("") // 首屏装载目录全量（首次访问触发 assets 解析，挪出主线程）
    }

    /** 搜索词变更 → 即时过滤。 */
    fun onQueryChange(query: String) {
        uiState = uiState.copy(query = query)
        runSearch(query)
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            // 赋值前确认未被取消：repo.search 是纯 CPU 过滤无挂起点，旧 Job 取消后仍会跑到
            // 这一行——不 ensureActive 则两个检索并发写 uiState，「最后输入者胜」只是碰运气
            ensureActive()
            uiState = uiState.copy(results = repo.search(query), loaded = true)
        }
    }
}
