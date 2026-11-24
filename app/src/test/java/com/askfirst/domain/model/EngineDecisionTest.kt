package com.askfirst.domain.model

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 消息正文的组装（docs/28 P2-6）。
 * 关键约束：**正文不许是空串**——空正文会落库成一条空气泡，下一轮还会当成空 `[A]` 喂回模型。
 */
class EngineDecisionTest {

    private fun decision(
        clarify: String? = null,
        questions: List<EngineQuestion> = emptyList(),
        blockReason: String? = null,
    ) = EngineDecision(
        state = EngineState.BLOCKED,
        clarify = clarify,
        covered = emptySet(),
        questions = questions,
        sheetDeltas = emptyList(),
        issues = emptyList(),
        nudge = null,
        blockReason = blockReason,
    )

    @Test
    fun `有阻断原因时正文不许是空的`() {
        val text = decision(blockReason = "你说不用登录，又说每人看自己的内容——哪条是你真正要的？")
            .toAssistantText()
        assertTrue("正文不能是空串，否则聊天里多一条空气泡", text.isNotBlank())
        assertTrue(text.contains("哪条是你真正要的"))
    }

    @Test
    fun `没问题也没阻断时给一句软提示`() {
        assertTrue(decision().toAssistantText().contains("没有新问题"))
    }

    @Test
    fun `复述与问题都在正文里`() {
        val question = EngineQuestion(id = "q1", text = "时间还是预算硬？", why = "边界决定候选集")
        val text = decision(clarify = "你说的是首屏慢", questions = listOf(question)).toAssistantText()
        assertTrue(text.contains("你说的是首屏慢"))
        assertTrue(text.contains("时间还是预算硬？"))
        assertTrue(text.contains("为什么问这个"))
    }

    @Test
    fun `阻断原因和问题同时存在时两样都留着`() {
        val question = EngineQuestion(id = "q1", text = "哪个是你真正要的？", why = "两条答案打架")
        val text = decision(questions = listOf(question), blockReason = "不用登录 vs 每人看自己的")
            .toAssistantText()
        assertTrue(text.contains("哪个是你真正要的"))
        assertTrue(text.contains("每人看自己的"))
    }
}
