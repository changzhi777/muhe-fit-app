package com.muhe.fit.app.lib

// ── 打卡域纯函数（M2 打卡屏；移植源 ck/frontend/src/lib/qr.js + views/CheckIn.jsx） ──
// JS 语义逐字对拍，golden = qr.test.js / CheckIn.reorder.test.jsx 的用例
// （test/…/lib/CheckInTest.kt 逐条对拍；fixture 化留待打卡域扩块，机制见 docs/golden-testing.md）。
// QR 位图生成（qr.js renderQrToCanvas 的对应面）在 screens/CheckInScreen.kt——含
// android.graphics 依赖，非纯函数不进本文件。

/**
 * 条码制式名折叠为统一小写 token（qr.js normalizeFmt:33-37）：
 * 'QR_CODE' | 'QrCode' | 'qr' → 'qrcode'；'EAN_13' → 'ean13'；空/未知去符号后小写。
 */
fun normalizeFmt(fmt: String?): String {
    val norm = (fmt ?: "").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    // 口语别名折叠：'qr' 与 'qrcode' 指同一条码（BarcodeFormat.QR_CODE 的枚举/口语拼写归一）
    return if (norm == "qr") "qrcode" else norm
}

/**
 * 格式闸（qr.js canRenderFmt:27-29）：扫码读得了多种条码，但只有 QR 能忠实重绘——
 * 非 QR 明确拒绝而非静默存储（存了就是闸机前看着能扫、内容却是错的条）。
 */
fun canRenderFmt(fmt: String?): Boolean = normalizeFmt(fmt) == "qrcode"

/**
 * 卡轨重排（CheckIn.jsx moveGymCard:19-27）：把 from 位卡挪到 to 位，返回新数组、不改输入。
 * 越界/原位一律无害 no-op——两端按钮失效即可，不抛错。
 */
fun <T> moveGymCard(cards: List<T>, from: Int, to: Int): List<T> {
    val next = cards.toMutableList()
    if (from < 0 || from >= next.size) return next
    val target = maxOf(0, minOf(to, next.size - 1))
    if (target == from) return next
    val moved = next.removeAt(from)
    next.add(target, moved)
    return next
}
