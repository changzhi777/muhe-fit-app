package com.muhe.fit.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

// ── stamps 第 6 类「gymCards」— 健身房会员卡条目（裁定 #17 八类透传） ───────
// JS 真源：ck/frontend/src/store/useStore.js（DEF.gymCards，注释即形状）：
// { id, label, value, fmt } — 只存码的 VALUE 与条码制式 fmt（'qrcode'|'ean13'|'code128'|...），
// 不存照片，亮码由 value 现生成（lib/qr.js；canRenderFmt 只重绘 QR，非 QR 明确拒绝）。
// 服务端合并语义：stamps.go — delLists[gymCards]/delTime[gymCards] = entryTime(_ts)。
// 协议合并：只增不减——删卡走文档级 deleted.gymCards 戳，协议防复活。
@Entity(tableName = "gymCards")
data class GymCardEntity(
    @PrimaryKey val id: String,
    val label: String?,          // 卡名（用户起的）
    val value: String?,          // 会员码值（唯一被存储的内容）
    val fmt: String?,            // 条码制式（小写 BarcodeFormat）
    // 客户端实现列（非协议键）：整条目 JSON 原文（透传重建源）
    val raw: String,
    @Embedded val stamp: Stamp,
)
