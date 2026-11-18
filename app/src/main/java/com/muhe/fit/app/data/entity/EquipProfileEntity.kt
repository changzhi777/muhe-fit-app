package com.muhe.fit.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

// ── stamps 第 7 类「equipProfiles」— 器材清单条目（裁定 #17 八类透传） ──────
// JS 真源：ck/frontend/src/store/useStore.js（DEF.equipProfiles）；条目构造 lib/equipment.js
// newProfile：{ id: 'eq...', name, equipment: [器材 tag], accV }（accV 是配件清单版本号，
// lib/equipment.js ACC_V）。equipment 数组动态，进 raw。
// 服务端合并语义：stamps.go — delLists[equipProfiles]/delTime[equipProfiles] = entryTime(_ts)。
// 可变条目：整条编辑以 _ts 更晚者胜（mergeEdits）；删除走 deleted.equipProfiles 戳（非只增不减类）。
@Entity(tableName = "equipProfiles")
data class EquipProfileEntity(
    @PrimaryKey val id: String,  // JS 侧 'eq' 前缀 id（newProfile 生成语义）
    val name: String?,           // 清单名（如「家里」「健身房」）
    val accV: Int?,              // 配件清单版本（缺省按未勾过 accessories 读，lib/equipment.js）
    // 客户端实现列（非协议键）：整条目 JSON 原文（equipment 列表零丢失）
    val raw: String,
    @Embedded val stamp: Stamp,
)
