package com.muhe.fit.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

// ── stamps 第 5 类「measurements」— 身体围度条目（裁定 #17 八类透传） ───────
// JS 真源：ck/frontend/src/store/useStore.js（DEF.measurements）；条目形状见
// ck/frontend/src/lib/measurements.js emptyMeasurement：{ d, t, neck, shoulders, chest, waist, hips,
// abdomen, upperArmLeft/Right, forearmLeft/Right, wristLeft/Right, thighLeft/Right, calfLeft/Right,
// ankleLeft/Right, bodyFat, other: [{ id, name, value }] } — 一天一次 check-in，cm 恒定存储。
// 19 个固定测量键与自定义 other 动态列表全部进 raw（列只展开查询面 d/t）。
// 服务端合并语义：stamps.go — delLists[measurements] 键 = dayKeyOfD(d)，delTime = dayTime(t)。
// 协议合并：只增不减——条目删除走文档级 deleted.measurements 戳，协议防复活。
@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey val d: String,   // 条目无 id 键：主键即 JS 键 d（一天一条，emptyMeasurement 同构）
    val t: Long?,                // 记录时刻 ms epoch（delTime 时间基线）
    // 客户端实现列（非协议键）：整条目 JSON 原文（19 固定键 + other 自定义项零丢失）
    val raw: String,
    @Embedded val stamp: Stamp,
)
