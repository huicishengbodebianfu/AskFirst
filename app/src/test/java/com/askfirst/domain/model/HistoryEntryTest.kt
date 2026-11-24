package com.askfirst.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryEntryTest {

    private fun entry(
        summary: String? = null,
        status: SessionStatus = SessionStatus.OPEN,
        state: EngineState = EngineState.GATHERING,
    ) = HistoryEntry(
        id = "s1",
        sceneId = "sc1",
        sceneName = "写代码",
        intent = "首屏太慢想加缓存",
        summary = summary,
        status = status,
        state = state,
        updatedAt = 0L,
        turnCount = 7,
        itemCount = 12,
    )

    @Test
    fun `标题优先用摘要`() {
        assertEquals("缓存方案定了", entry(summary = "缓存方案定了").title)
    }

    @Test
    fun `没有摘要退回开题一句话`() {
        assertEquals("首屏太慢想加缓存", entry(summary = null).title)
    }

    @Test
    fun `空摘要退回开题一句话`() {
        assertEquals("首屏太慢想加缓存", entry(summary = "  ").title)
    }

    @Test
    fun `副文案带场景轮数与单子条数`() {
        val s = entry().subtitle
        assertTrue(s.contains("写代码"))
        assertTrue(s.contains("7 条消息"))
        assertTrue(s.contains("单子 12 条"))
    }

    @Test
    fun `状态文案不出现状态机英文词`() {
        val all = listOf(
            entry(status = SessionStatus.OPEN, state = EngineState.GATHERING),
            entry(status = SessionStatus.OPEN, state = EngineState.NEEDS_PRECISION),
            entry(status = SessionStatus.OPEN, state = EngineState.BLOCKED),
            entry(status = SessionStatus.OPEN, state = EngineState.READY),
            entry(status = SessionStatus.DONE),
            entry(status = SessionStatus.DRAFT),
        ).joinToString { it.subtitle }
        listOf("gathering", "needs_precision", "blocked", "ready", "open", "done", "draft").forEach { word ->
            assertTrue("副文案不许出现状态机英文词 $word: $all", !all.contains(word))
        }
    }

    @Test
    fun `场景被删时兜底`() {
        val s = entry().copy(sceneName = null).subtitle
        assertTrue(s.contains("场景已删除"))
    }
}
