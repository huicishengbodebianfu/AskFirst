package com.askfirst.domain.citation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4 引用硬规则：上限 3 份（UI/门面双拦）、LIKE 关键词清洗。 */
class CitationPolicyTest {

    @Test
    fun `上限常量与方案一致`() {
        assertEquals(3, CitationPolicy.MAX_CITATIONS)
        assertEquals(30, CitationPolicy.RECENT_LIMIT)
    }

    @Test
    fun `cap 截到 3 份 保持顺序`() {
        val five = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "b", "c"), CitationPolicy.cap(five))
        assertEquals(listOf("a", "b"), CitationPolicy.cap(listOf("a", "b")))
        assertEquals(emptyList<String>(), CitationPolicy.cap(emptyList<String>()))
        assertEquals(0, CitationPolicy.cap(listOf<String>()).size)    }

    @Test
    fun `canSelectMore 满 3 不给再勾`() {
        assertTrue(CitationPolicy.canSelectMore(0))
        assertTrue(CitationPolicy.canSelectMore(2))
        assertFalse(CitationPolicy.canSelectMore(3))
        assertFalse(CitationPolicy.canSelectMore(5))
    }

    @Test
    fun `关键词删掉 LIKE 通配符`() {
        assertEquals("缓存", CitationPolicy.sanitizeKeyword("%缓存%"))
        assertEquals("缓存", CitationPolicy.sanitizeKeyword("缓_存"))
        assertEquals("首屏4秒", CitationPolicy.sanitizeKeyword("首_屏%4秒_"))
    }

    @Test
    fun `关键词 trim 空白 空串表示走最近`() {
        assertEquals("", CitationPolicy.sanitizeKeyword("   "))
        assertEquals("立项", CitationPolicy.sanitizeKeyword(" 立项 "))
        assertEquals("", CitationPolicy.sanitizeKeyword(""))
    }

    // M4 核查补：库里 sheet.summary 可能是空串（COALESCE 只在 NULL 时往下取），
    // 空串会让「这份旧结论」在候选卡/溯源行/引擎注入里变成一行空白。
    @Test
    fun `摘要兜底 空白一律给可读占位`() {
        assertEquals("（占位）", CitationPolicy.summaryText(null, "（占位）"))
        assertEquals("（占位）", CitationPolicy.summaryText("", "（占位）"))
        assertEquals("（占位）", CitationPolicy.summaryText("   ", "（占位）"))
        assertEquals("首屏缓存分层", CitationPolicy.summaryText(" 首屏缓存分层 ", "（占位）"))
    }

    @Test
    fun `摘要兜底 默认占位文案`() {
        assertEquals("（这份旧结论没留下摘要）", CitationPolicy.summaryText(null))
        assertEquals("（这份旧结论没留下摘要）", CitationPolicy.summaryText(""))
    }
}
