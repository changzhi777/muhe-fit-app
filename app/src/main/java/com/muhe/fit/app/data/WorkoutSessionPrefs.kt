package com.muhe.fit.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进行中训练会话的本地持久化（批 2 · Workout 屏）。
 *
 * 对位 PWA 的 S.active：整个 useStore 持久化进 localStorage，会话中断（杀进程/崩溃）后
 * 重开恢复「进行中」状态。Android 侧把它从文档档位拆出——半截会话不能进 workouts 表
 * （统计/同步会把未完成条目当已完成消费），故走独立 SharedPreferences（ServerConfig /
 * CheckInPrefs 同款载体，裁定：DataStore 不在依赖白名单）。
 *
 * 键 = "muhe_active"；值 = S.active 整条 JSON（{id,d,start,name,cur,entries[],routineIds}）。
 * 完成会话时 clear() → 转 WorkoutEntity 落 workouts 表（真正的协议档）。
 */
@Singleton
class WorkoutSessionPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("muhe_active", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** 读进行中会话；无/损坏 → null（损坏视为无 + 清残骸，防坏档锁死开场）。 */
    fun load(): JsonObject? = try {
        prefs.getString(KEY, null)?.let { json.parseToJsonElement(it) as? JsonObject }
            ?.also { if (it == null) clear() }
    } catch (_: Exception) {
        clear()
        null
    }

    fun save(active: JsonObject) {
        prefs.edit().putString(KEY, active.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    // ── 批 6 · 编辑模式跨屏 id 载体 ────────────────────────────────────
    // 历史屏（WorkoutHistoryScreen）选中已存会话 → setLastEditId 暂存 → nav(workout) →
    // WorkoutViewModel.load()（WorkoutScreen ON_RESUME 触发）优先消费：consumeLastEditId
    // 读后即清（一次性信号）→ raw 重建 active 档 + editingWorkoutId 标记（完成时同 id
    // REPLACE 覆盖原条目）+ 编辑档即刻 prefs.save（后续 load 恢复不丢，不会打回 Idle）。
    // 走载体而非 VM 直调的原因：@HiltViewModel 每目的地新实例，历史屏拿不到训练屏的 VM。
    fun setLastEditId(id: String) {
        prefs.edit().putString(KEY_EDIT, id).apply()
    }

    /** 取待编辑会话 id（读后即清）；无 → null。 */
    fun consumeLastEditId(): String? {
        val id = prefs.getString(KEY_EDIT, null) ?: return null
        prefs.edit().remove(KEY_EDIT).apply()
        return id
    }

    private companion object {
        const val KEY = "active"

        /** 批 6：历史屏转编辑的待消费 workoutId（同文件独立键，与 active 会话档互不干扰） */
        const val KEY_EDIT = "editId"
    }
}
