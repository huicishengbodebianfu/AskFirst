package com.askfirst.domain.engine

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.prompt.PromptBuilder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引擎整链路（S4 验收）：假 provider → 流式 → 解析 → 状态复核 → 决策。 */
class QuestionEngineTest {

    private val prompts = PromptBuilder(
        engineSystemPrompt = "你是提问者。",
        sheetSystemPrompt = "你是出单人。",
    )

    private fun engine(provider: FakeLlmProvider) =
        QuestionEngine(provider = provider, prompts = prompts, parser = TurnParser())

    private val goodJson = """
        {"state":"gathering","clarify":"你说的是首屏打开慢",
         "covered":["tech_q1","fake_q"],
         "questions":[{"id":"tech_q2","text":"时间、人力、预算，哪条是硬的？","why":"边界决定候选集","recommended":"我猜是时间最硬"}],
         "sheet_delta":[{"col":1,"text":"首屏打开四秒","source_message_ids":["m1"]}],
         "nudge":"再聊一个就够了"}
    """.trimIndent()

    @Test
    fun `正常一轮：进度事件 + 结构化决策`() = runTest {
        val provider = FakeLlmProvider(listOf(FakeResponse(goodJson)))
        val events = engine(provider).nextTurn(
            Fixtures.context(turns = listOf(Fixtures.userTurn("m1", "首屏打开四秒"), Fixtures.aiTurn("m2", "问题…"))),
        ).toList()

        assertTrue(events.any { it is EngineStreamEvent.Progress })
        val decided = events.filterIsInstance<EngineStreamEvent.Decided>().single()
        assertTrue(decided.assistantText.contains("首屏打开慢"))
        assertTrue(decided.assistantText.contains("哪条是硬的"))

        val decision = decided.decision
        assertEquals("模型编造的 covered 被过滤", setOf("tech_q1"), decision.covered)
        assertEquals(1, decision.questions.size)
        assertEquals("tech_q2", decision.questions.single().id)
        assertEquals("我猜是时间最硬", decision.questions.single().recommended)
        assertEquals(EngineState.GATHERING, decision.state)
        assertEquals(1, decision.sheetDeltas.size)
        assertEquals(listOf("m1"), decision.sheetDeltas.single().sourceMessageIds)
    }

    @Test
    fun `第一次坏 JSON 会自动低温柔性重试一次`() = runTest {
        val provider = FakeLlmProvider(
            listOf(
                FakeResponse("抱歉，我先问你一个问题：你觉得慢在哪里？"),
                FakeResponse(goodJson),
            ),
        )
        val events = engine(provider).nextTurn(Fixtures.context()).toList()

        assertEquals("应该正好调用两次", 2, provider.calls)
        assertTrue(events.any { it is EngineStreamEvent.Decided })
        assertTrue(events.none { it is EngineStreamEvent.Failed })
    }

    @Test
    fun `两次都坏就明确失败 并带上原文`() = runTest {
        val provider = FakeLlmProvider(listOf(FakeResponse("我不是 JSON"), FakeResponse("还不是")))
        val events = engine(provider).nextTurn(Fixtures.context()).toList()

        val failed = events.filterIsInstance<EngineStreamEvent.Failed>().single()
        assertTrue(failed.error is AppError.BadJson)
        assertEquals("还不是", failed.rawText)
        assertTrue(events.none { it is EngineStreamEvent.Decided })
    }

    @Test
    fun `模型腾空的问题用必问题兜底`() = runTest {
        val json = """{"state":"gathering","clarify":"嗯","questions":[]}"""
        val provider = FakeLlmProvider(listOf(FakeResponse(json)))
        val decision = engine(provider)
            .nextTurn(Fixtures.context())
            .toList()
            .filterIsInstance<EngineStreamEvent.Decided>()
            .single()
            .decision

        assertEquals(1, decision.questions.size)
        assertEquals("tech_q1", decision.questions.single().id)
        assertTrue("兜底问题也要带 why", decision.questions.single().why.isNotBlank())
    }

    @Test
    fun `没有出处的条目：能找到原话才补 找不到就丢`() = runTest {
        val json = """
            {"state":"gathering","clarify":"嗯",
             "sheet_delta":[
               {"col":1,"text":"打开就要四秒","source_message_ids":[]},
               {"col":5,"text":"要不要上云","source_message_ids":[]}
             ]}
        """.trimIndent()
        val provider = FakeLlmProvider(listOf(FakeResponse(json)))
        val decision = engine(provider)
            .nextTurn(Fixtures.context(turns = listOf(Fixtures.userTurn("m9", "打开就要四秒，太久了"))))
            .toList()
            .filterIsInstance<EngineStreamEvent.Decided>()
            .single()
            .decision

        assertEquals("只保留能追到原话的那条", 1, decision.sheetDeltas.size)
        assertEquals(listOf("m9"), decision.sheetDeltas.single().sourceMessageIds)
    }

    // ---- docs/28 P0-2：出处白名单 ----

    @Test
    fun `模型编的出处 id 一个都不许进决策`() = runTest {
        val json = """
            {"state":"gathering","clarify":"嗯",
             "sheet_delta":[
               {"col":1,"text":"打开就要四秒","source_message_ids":["msg_1"]},
               {"col":5,"text":"要不要上云","source_message_ids":["bogus-id"]}
             ]}
        """.trimIndent()
        val provider = FakeLlmProvider(listOf(FakeResponse(json)))
        val context = Fixtures.context(turns = listOf(Fixtures.userTurn("m9", "打开就要四秒，太久了")))
        val decision = engine(provider).nextTurn(context).toList()
            .filterIsInstance<EngineStreamEvent.Decided>()
            .single()
            .decision

        val known = context.turns.map { it.id }.toSet()
        assertTrue(
            "编造的 id 必须一个都不留：${decision.sheetDeltas.map { it.sourceMessageIds }}",
            decision.sheetDeltas.none { delta -> delta.sourceMessageIds.any { it !in known } },
        )
        // 第一条能追到他那句话 → 补成真实 id；第二条追不到 → 整条丢掉（D4）
        assertEquals(1, decision.sheetDeltas.size)
        assertEquals(listOf("m9"), decision.sheetDeltas.single().sourceMessageIds)
    }

    @Test
    fun `真假混着的出处只留真的那个`() = runTest {
        val json = """
            {"state":"gathering","clarify":"嗯",
             "sheet_delta":[{"col":2,"text":"只要一个人力","source_message_ids":["m9","made-up"]}]}
        """.trimIndent()
        val provider = FakeLlmProvider(listOf(FakeResponse(json)))
        val context = Fixtures.context(turns = listOf(Fixtures.userTurn("m9", "只有我一个人力")))
        val decision = engine(provider).nextTurn(context).toList()
            .filterIsInstance<EngineStreamEvent.Decided>()
            .single()
            .decision

        assertEquals(listOf("m9"), decision.sheetDeltas.single().sourceMessageIds)
    }

    @Test
    fun `矛盾会让这一轮变成阻断 并给人话原因`() = runTest {

        val json = """{"state":"ready","clarify":"懂了","questions":[]}"""
        val provider = FakeLlmProvider(listOf(FakeResponse(json)))
        val decision = engine(provider)
            .nextTurn(
                Fixtures.context(
                    covered = setOf("tech_q1", "tech_q2"),
                    turns = listOf(
                        Fixtures.userTurn("m1", "不用登录就行"),
                        Fixtures.userTurn("m2", "但每个人看自己的内容"),
                    ),
                    draft = mapOf(
                        Cols.GOAL to listOf("首屏 P90 从 4s 降到 1.2s"),
                        Cols.FIRST_STEPS to listOf("拉火焰图", "定接口契约"),
                    ),
                ),
            )
            .toList()
            .filterIsInstance<EngineStreamEvent.Decided>()
            .single()
            .decision

        assertEquals(EngineState.BLOCKED, decision.state)
        assertTrue("要把冲突说成人话", decision.blockReason!!.contains("哪条是你真正要的"))
    }

    @Test
    fun `模型卡住时按单轮预算超时 而不是无限等`() = runTest {
        val engine = QuestionEngine(
            provider = HangingProvider(),
            prompts = prompts,
            parser = TurnParser(),
            turnTimeoutMs = 100,
            retryTimeoutMs = 100,
        )
        val events = engine.nextTurn(Fixtures.context()).toList()
        val failed = events.filterIsInstance<EngineStreamEvent.Failed>().single()
        assertTrue("应是超时错误，实际 ${failed.error::class.simpleName}", failed.error is AppError.Timeout)
        assertTrue(events.none { it is EngineStreamEvent.Decided })
    }

    @Test
    fun `provider 失败时直接失败 不产生决策`() = runTest {
        val provider = FakeLlmProvider(listOf(FakeResponse(goodJson)), failOnCall = AppError.Offline())
        val events = engine(provider).nextTurn(Fixtures.context()).toList()
        val failed = events.filterIsInstance<EngineStreamEvent.Failed>().single()
        assertTrue(failed.error is AppError.Offline)
        assertFalse(events.any { it is EngineStreamEvent.Decided })
    }
}