package com.muhe.fit.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 精简动作条目（catalog.json 单条；生成面 = ck/scripts/export-exercises-assets.mjs）。 */
@Serializable
data class CatalogExercise(
    val id: String,
    /** 中文名（catalogue i18n zh 覆盖层优先，缺省英文原名）。 */
    val name: String,
    val nameEn: String,
    /** 肌群：目标肌群首位 + 协同肌群（canonical 英文词，屏面经词表映射出中文）。 */
    val muscles: List<String> = emptyList(),
    val equipment: String? = null,
    val category: String? = null,
)

/** catalog.json 文档形状：exercises + alias（变体 id→正身，语义对齐 web EXDB/EXALIAS）。 */
@Serializable
internal data class ExerciseCatalog(
    val generatedAt: String? = null,
    val count: Int = 0,
    val sourceCommit: String? = null,
    val exercises: List<CatalogExercise> = emptyList(),
    val alias: Map<String, String> = emptyMap(),
)

/**
 * 动作目录数据源（D2 裁定：assets 静态打包，不进 Room——目录是随版本发的静态数据）。
 * 数据真源 ck/catalogue/，经 ck/scripts/export-exercises-assets.mjs 生成
 * app/src/main/assets/exercises/catalog.json（5,632 条 + alias 922，约 1MB；
 * 说明/指令/媒体字段 M2+ 再进精简包）。5,632 条一次性解析 <100ms 量级，
 * 首次访问 lazy 触发——调用方在协程默认派发上取数（LibraryViewModel 口径），勿在主线程冷触发。
 * Hilt 装配：@Inject 构造注入即 SingletonComponent 单例，无需 @Module。
 */
@Singleton
class ExercisesRepo @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val file: ExerciseCatalog by lazy { load() }

    private val index: Map<String, CatalogExercise> by lazy { file.exercises.associateBy { it.id } }

    fun all(): List<CatalogExercise> = file.exercises

    /**
     * byId：变体 id（femaleVariant 占位条目，alias 表）解析到正身——web EXALIAS 同语义
     * （计划文件/导入可能携带变体 id，绝不落到「未知动作」）。
     */
    fun byId(id: String): CatalogExercise? = index[id] ?: file.alias[id]?.let { index[it] }

    /** 名称中英 contains 检索（大小写不敏感；空白返回全量）。 */
    fun search(query: String): List<CatalogExercise> {
        val q = query.trim()
        if (q.isEmpty()) return file.exercises
        return file.exercises.filter {
            it.name.contains(q, ignoreCase = true) || it.nameEn.contains(q, ignoreCase = true)
        }
    }

    /** 按肌群筛（canonical 英文词，含目标与协同；大小写不敏感）。 */
    fun byMuscle(muscle: String): List<CatalogExercise> =
        file.exercises.filter { ex -> ex.muscles.any { it.equals(muscle, ignoreCase = true) } }

    /** 按器材筛（canonical 英文词，大小写不敏感）。 */
    fun byEquipment(equipment: String): List<CatalogExercise> =
        file.exercises.filter { it.equipment.equals(equipment, ignoreCase = true) }

    /** assets 缺失/不可读回落空目录（屏面呈空态而非崩溃）；字段形状不符属打包期 bug，让它炸（构建期即暴露）。 */
    private fun load(): ExerciseCatalog = try {
        context.assets.open(ASSET_PATH).use { stream ->
            json.decodeFromString(ExerciseCatalog.serializer(), stream.readBytes().decodeToString())
        }
    } catch (_: IOException) {
        ExerciseCatalog()
    }

    companion object {
        private const val ASSET_PATH = "exercises/catalog.json"
    }
}
