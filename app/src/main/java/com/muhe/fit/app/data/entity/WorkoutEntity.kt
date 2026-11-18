package com.muhe.fit.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ── stamps 第 1 类「workouts」— 训练会话条目（裁定 #17 八类透传） ──────────
// JS 真源：ck/frontend/src/store/useStore.js（DEF.workouts）；条目构造见
// ck/frontend/src/lib/demoSeed.js（{ id, d, start, end, routineId, name, bw, entries, prs, vol }）
// 与 ck/frontend/src/sheets.jsx（完成/补录落库）。entries 内 set 行为开放形状
// （{ w, r, phase, type, drops, clusters, failure, sides, ... }，见 lib/workout-model.js），全部进 raw。
// 服务端合并语义：ck/backend-go/internal/data/stamps.go — delLists[workouts] 键 = id（缺失兜底 d|start），
// delTime = workoutTime 即 max(_ts, end, start)。
// 协议合并：只增不减（mergeDeletions/mergeEdits）——条目删除走文档级 deleted.workouts 戳，协议防复活。
@Entity(tableName = "workouts", indices = [Index("d")])
data class WorkoutEntity(
    @PrimaryKey val id: String,          // JS 侧 uid() 生成；沿 JS id 语义，客户端不再做 d|start 兜底键
    @ColumnInfo(name = "d") val d: String,        // 会话日期，ISO yyyy-MM-dd（历史列表主查询键）
    @ColumnInfo(name = "start") val start: Long,  // 开始时刻 ms epoch
    @ColumnInfo(name = "end") val end: Long,      // 结束时刻 ms epoch
    val name: String?,                   // 会话名（多来自 routine.name 快照）
    val routineId: String?,              // 来源计划 id
    val vol: Double?,                    // 训练量（JS 键 vol）
    // 客户端实现列（非协议键）：整条目 JSON 原文，八类全透传的重建源；
    // bw / entries / prs / note / media 等动态字段零丢失都在这里
    val raw: String,
    @Embedded val stamp: Stamp,
)
