package com.muhe.fit.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

// ── stamps 第 2 类「routines」— 计划条目（裁定 #17 八类透传） ───────────────
// JS 真源：ck/frontend/src/store/useStore.js（DEF.routines）；条目形状 { id, name, ex: [...] }，
// ex 槽位为开放形状（{ id, sets, reps, sg, sgName, sgRest, mode, ... }，见 lib/history.js defaultConfig、
// views/RoutineEdit.jsx），全部进 raw。
// 服务端合并语义：ck/backend-go/internal/data/stamps.go — delLists[routines]/delTime[routines] = entryTime(_ts)。
// 可变条目：整条编辑以 _ts 更晚者胜（mergeEdits）；删除走文档级 deleted.routines 戳（非只增不减类）。
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String?,
    // 客户端实现列（非协议键）：整条目 JSON 原文（ex 槽位等动态字段零丢失）
    val raw: String,
    @Embedded val stamp: Stamp,
)
