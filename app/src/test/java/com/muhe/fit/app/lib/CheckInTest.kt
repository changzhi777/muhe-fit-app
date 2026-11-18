package com.muhe.fit.app.lib

// ── 打卡域纯函数对拍（JS 测试用例即 golden，机制见 docs/golden-testing.md） ──
// normalizeFmt/canRenderFmt ← ck/frontend/src/lib/qr.test.js（describe ×3 断言逐条对拍）；
// moveGymCard ← ck/frontend/src/views/CheckIn.reorder.test.jsx（七例逐条对拍）。
// 用例值直接取自 JS 测试文件；fixture 化（进 export-golden-fixtures.mjs）留待打卡域扩块。
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInTest {

    // ── normalizeFmt（qr.test.js describe('normalizeFmt')） ─────────────────

    @Test
    fun normalize_folds_every_qr_spelling() {
        // mlkit 的 BarcodeFormat、enum 式大小写、口语 'qr' 都指同一个东西
        assertEquals("qrcode", normalizeFmt("QR_CODE"))
        assertEquals("qrcode", normalizeFmt("QrCode"))
        assertEquals("qrcode", normalizeFmt("qrcode"))
        assertEquals("qrcode", normalizeFmt("qr"))
        assertEquals("qrcode", normalizeFmt("QR"))
    }

    @Test
    fun normalize_lowercases_and_strips_separators_for_other_formats() {
        assertEquals("ean13", normalizeFmt("EAN_13"))
        assertEquals("code128", normalizeFmt("Code128"))
        assertEquals("code128", normalizeFmt("CODE_128"))
    }

    @Test
    fun normalize_treats_empty_nullish_as_empty_token() {
        assertEquals("", normalizeFmt(""))
        assertEquals("", normalizeFmt(null))
    }

    // ── canRenderFmt（qr.test.js describe('canRenderFmt')） ──────────────────

    @Test
    fun accepts_only_qr_in_any_spelling() {
        assertTrue(canRenderFmt("QR_CODE"))
        assertTrue(canRenderFmt("QrCode"))
        assertTrue(canRenderFmt("qr"))
    }

    @Test
    fun rejects_1d_and_other_2d_symbologies() {
        // 扫码读得出，但重绘不出来——存了就会在闸机前显示成错的条
        for (fmt in listOf("EAN_13", "EAN_8", "CODE_128", "CODE_39", "ITF", "UPC_A", "PDF_417", "AZTEC", "DATA_MATRIX")) {
            assertFalse(fmt, canRenderFmt(fmt))
        }
    }

    @Test
    fun rejects_empty_and_unknown_formats() {
        assertFalse(canRenderFmt(""))
        assertFalse(canRenderFmt(null))
        assertFalse(canRenderFmt("something-else"))
    }

    // ── moveGymCard（CheckIn.reorder.test.jsx describe('moveGymCard')） ──────

    @Test
    fun moves_a_card_forward_to_a_later_slot() {
        assertEquals(listOf("b", "c", "a", "d"), moveGymCard(listOf("a", "b", "c", "d"), 0, 2))
    }

    @Test
    fun moves_a_card_backward_to_an_earlier_slot() {
        assertEquals(listOf("a", "d", "b", "c"), moveGymCard(listOf("a", "b", "c", "d"), 3, 1))
    }

    @Test
    fun is_a_permutation_no_card_lost_or_duplicated() {
        val before = listOf("a", "b", "c", "d", "e")
        val after = moveGymCard(before, 4, 0)
        assertEquals(before.size, after.size)
        assertEquals(before.sorted(), after.sorted())
    }

    @Test
    fun leaves_the_list_unchanged_when_dropped_in_place() {
        assertEquals(listOf("a", "b", "c"), moveGymCard(listOf("a", "b", "c"), 1, 1))
    }

    @Test
    fun clamps_a_target_past_the_end_to_the_last_slot() {
        assertEquals(listOf("b", "c", "a"), moveGymCard(listOf("a", "b", "c"), 0, 99))
    }

    @Test
    fun ignores_an_out_of_range_source_without_throwing() {
        assertEquals(listOf("a", "b"), moveGymCard(listOf("a", "b"), 5, 0))
    }

    @Test
    fun does_not_mutate_the_input_list() {
        val before = listOf("a", "b", "c")
        val snapshot = before.toList()
        moveGymCard(before, 0, 2)
        assertEquals(snapshot, before)
    }
}
