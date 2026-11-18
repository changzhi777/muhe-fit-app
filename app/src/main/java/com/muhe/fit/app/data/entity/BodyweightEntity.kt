package com.muhe.fit.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

// ── stamps 第 4 类「bodyweight」— 体重称重条目（裁定 #17 八类透传） ─────────
// JS 真源：ck/frontend/src/store/useStore.js（DEF.bodyweight）；条目构造 sheets.jsx：
// { d: iso, w: kg, t: ms }（一天一条，当天已有则原条目更新）。读取见 lib/bodyweight.js weeklyWeights。
// 服务端合并语义：stamps.go — delLists[bodyweight] 键 = dayKeyOfD(d)，delTime = dayTime(t)。
// 协议合并：只增不减——条目删除走文档级 deleted.bodyweight 戳，协议防复活。
@Entity(tableName = "bodyweight")
data class BodyweightEntity(
    @PrimaryKey val d: String,   // 条目无 id 键：主键即 JS 键 d（ISO 日期，stamps.go dayKeyOfD 同源）
    val w: Double?,              // 体重（kg 存储恒定，UI 按 unit 换算）
    val t: Long?,                // 称重时刻 ms epoch（delTime 时间基线）
    // 客户端实现列（非协议键）：整条目 JSON 原文（透传重建源）
    val raw: String,
    @Embedded val stamp: Stamp,
)
