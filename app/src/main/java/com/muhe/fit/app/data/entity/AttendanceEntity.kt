package com.muhe.fit.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ── stamps 第 8 类「attendance」— 到场打卡条目（裁定 #17 八类新增） ─────────
// 协议条目形状（decisions.md #17 / android-app.md §2.2）：{ id, gymCardId, ts, note }。
// M1 随协议落库（本表），M2 打卡屏开始消费（android-app.md §2.2 / §3.4）。
// 服务端半：api-go internal/data/stamps.go 扩展为八类时同步挂 delLists/entryTime 盖章。
// 协议合并：只增不减——误打卡撤销走文档级 deleted.attendance 戳，协议防复活。
@Entity(tableName = "attendance", indices = [Index("ts")])
data class AttendanceEntity(
    @PrimaryKey val id: String,
    val gymCardId: String?,      // 打卡所用会员卡（gymCards.id）
    @ColumnInfo(name = "ts") val at: Long,   // 到场时刻 ms epoch；列名 ts = 协议键（属性名 at 避让盖章 _ts）
    val note: String?,           // 备注（可空）
    // 客户端实现列（非协议键）：整条目 JSON 原文（透传重建源）
    val raw: String,
    @Embedded val stamp: Stamp,
)
