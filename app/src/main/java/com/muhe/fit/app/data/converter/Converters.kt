package com.muhe.fit.app.data.converter

import androidx.room.TypeConverter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Room TypeConverters：时间戳一律 ms epoch Long 直存（JS 侧即 number，无需 Instant 往返），
// 此处只负责 _wids 的 List<String> <-> JSON 数组编解码（kotlinx-serialization 单点序列化）。
// 注意：reified 扩展 encodeToString/decodeFromString 需显式 import 且用 lambda 调用——
// Json::encodeToString 方法引用只匹配双参成员函数重载（Inapplicable candidate 编译错）。
class Converters {

    @TypeConverter
    fun widsToJson(v: List<String>?): String? = v?.let { Json.encodeToString(it) }

    @TypeConverter
    fun jsonToWids(s: String?): List<String>? = s?.let {
        // DB 读侧 fail-soft（与 ExercisesRepo「让它炸」不同面）：一条坏 wids 不得毒化整表
        // getAll（迁移残留/旧版 bug 写入的脏列会让八表重建、统计聚合全部崩）——降级 null + 丢弃
        try { Json.decodeFromString<List<String>>(it) } catch (_: Exception) { null }
    }
}
