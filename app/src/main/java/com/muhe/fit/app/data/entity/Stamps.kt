package com.muhe.fit.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded

// ── stamps 盖章列（八表公共） ─────────────────────────────────────────────
// 列名与 JS 条目盖章键一字不差（_ts/_wid/_wids），对照服务端半
// ck/backend-go/internal/data/stamps.go 零翻译成本：
//   _ts   服务器 StampPut 的时间基线（时钟回退不倒退，t = max(now, highest+1)）；
//         本地未同步条目先写本地变更时刻（ms epoch），上传后以服务端重盖为准
//   _wid  最后写入者设备 id
//   _wids 历史写入设备列表（JSON 数组，经 converter 序列化）
// 条目级嵌套字段时间戳 _f 不建列——客户端半不按它查询，保留在 raw 原文内透传。
// 文档级 _rev（states 整档版本）不落条目表，由同步元数据承载（M1 网络层批次）。
// ts 无默认值（2026-11-18 修复）：默认 0 配 REPLACE 整行覆盖 = 漏盖章把已同步行的 _ts
// 静默打成 0，entryTime/欠账判定/getSince 全部失真——去掉默认值让漏填变成编译错。
data class Stamp(
    @ColumnInfo(name = "_ts") val ts: Long,
    @ColumnInfo(name = "_wid") val wid: String? = null,
    @ColumnInfo(name = "_wids") val wids: List<String>? = null,
)
