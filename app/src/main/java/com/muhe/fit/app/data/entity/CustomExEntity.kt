package com.muhe.fit.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

// ── stamps 第 3 类「customEx」— 自建动作条目（裁定 #17 八类透传） ───────────
// JS 真源：ck/frontend/src/store/useStore.js（DEF.customEx）；条目构造 sheets.jsx（编辑保存）：
// { id, n(名称), bp, desc, tg, sm, muscleGroups, primaries, secondaries, eq, custom: true }，
// 与目录动作同形状（lib/exercises.js healCustomEx 补 custom/eq）。除 id/n/eq 外的键全部进 raw。
// 服务端合并语义：stamps.go — delLists[customEx]/delTime[customEx] = entryTime(_ts)。
// 可变条目：整条编辑以 _ts 更晚者胜（mergeEdits）；删除走 deleted.customEx 戳（非只增不减类）。
@Entity(tableName = "customEx")
data class CustomExEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "n") val name: String?,   // JS 键 n（动作名）
    val eq: String?,                             // 器材 tag（healCustomEx 兜底空串）
    // 客户端实现列（非协议键）：整条目 JSON 原文（bp/desc/tg/sm/muscleGroups/primaries/secondaries 等）
    val raw: String,
    @Embedded val stamp: Stamp,
)
