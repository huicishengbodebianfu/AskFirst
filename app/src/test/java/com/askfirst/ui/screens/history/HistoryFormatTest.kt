package com.askfirst.ui.screens.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HistoryFormatTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun ms(at: LocalDateTime): Long = at.atZone(zone).toInstant().toEpochMilli()

    private val now = ms(LocalDateTime.of(2026, 11, 24, 20, 0))

    @Test
    fun `今天显示到分钟`() {
        assertEquals("今天 09:05", HistoryFormat.relative(now, ms(LocalDateTime.of(2026, 11, 24, 9, 5)), zone))
    }

    @Test
    fun `昨天显示到分钟`() {
        assertEquals("昨天 23:59", HistoryFormat.relative(now, ms(LocalDateTime.of(2026, 11, 23, 23, 59)), zone))
    }

    @Test
    fun `同年显示月日`() {
        assertEquals("3月2日", HistoryFormat.relative(now, ms(LocalDateTime.of(2026, 3, 2, 10, 0)), zone))
    }

    @Test
    fun `跨年显示年月日`() {
        assertEquals("2025年12月31日", HistoryFormat.relative(now, ms(LocalDateTime.of(2025, 12, 31, 10, 0)), zone))
    }

    @Test
    fun `完整时间格式`() {
        assertEquals("2026年11月24日 09:05", HistoryFormat.full(ms(LocalDateTime.of(2026, 11, 24, 9, 5)), zone))
    }
}
