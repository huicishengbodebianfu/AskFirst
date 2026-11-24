package com.askfirst.domain.prompt

import com.askfirst.domain.engine.Fixtures
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上下文组装（S4 验收，改动 #10：一次组装、六段固定顺序）。 */
class PromptBuilderTest {

    private val builder = PromptBuilder(
        engineSystemPrompt = "你是提问者。",
        sheetSystemPrompt = "你是出单人。",
    )

    private fun block(
        covered: Set<String> = emptySet(),
        turns: List<com.askfirst.domain.model.Turn> = emptyList(),
        intent: String = "把首屏从 4 秒降到 1 秒",
        draft: Map<Int, List<String>> = emptyMap(),
    ): String = builder.engineMessages(
        Fixtures.context(intent = intent, covered = covered, turns = turns, draft = draft),
    ).last().content

    @Test
    fun `两条消息 system 带 prompt 版本`() {
        val messages = builder.engineMessages(Fixtures.context())
        assertEquals(2, messages.size)
        assertTrue(messages[0].content.contains("prompt_version=1.0.0"))
        assertEquals("system", messages[0].role)
        assertEquals("user", messages[1].role)
    }

    @Test
    fun `六段顺序固定 缺内容写占位`() {
        val text = block()
        val order = listOf(
            "## 用户最初那句话",
            "## 当前场景",
            "## 已覆盖问题 id",
            "## 会话全文",
            "## 确认单草稿",
            "## 当前状态",
        )
        val positions = order.map { text.indexOf(it) }
        assertTrue("六段都应在: $positions", positions.all { it >= 0 })
        assertEquals("顺序不许变", positions.sorted(), positions)
        assertTrue(text.contains("（无）"))
        assertTrue(text.contains("（空）"))
    }

    @Test
    fun `已覆盖的问题不再出现在清单里`() {
        // 注意：covered 段本身会列出 id，所以这里断言"题目正文"不在清单里
        assertTrue(block(covered = emptySet()).contains("这次要解决什么问题"))
        assertFalse(block(covered = setOf("tech_q1")).contains("这次要解决什么问题"))
        assertTrue("已覆盖 id 仍应在 covered 段里", block(covered = setOf("tech_q1")).contains("tech_q1"))
    }

    @Test
    fun `条件题只在命中触发词时出现`() {
        assertFalse(block(intent = "把首屏从 4 秒降到 1 秒").contains("tech_q4"))
        assertTrue(block(intent = "这次是存量系统迁移，顺便把首屏弄快").contains("tech_q4"))
    }

    @Test
    fun `超长会话从最早处截断并标注`() {
        val longTurn = "这是一段很长的回答".repeat(60)
        val turns = (1..20).map { Fixtures.userTurn("m$it", longTurn) }
        val text = block(turns = turns)
        assertTrue(text.contains("（更早的对话已省略）"))
        val transcriptStart = text.indexOf("## 会话全文")
        val transcriptEnd = text.indexOf("## 确认单草稿")
        val transcript = text.substring(transcriptStart, transcriptEnd)
        assertTrue("会话段应被截到 3000 字左右，实际 ${transcript.length}", transcript.length < 3300)
    }

    @Test
    fun `草稿按五栏渲染 条目带短横线`() {
        val text = block(
            draft = mapOf(
                Cols.GOAL to listOf("首屏降到 1.2s"),
                Cols.FIRST_STEPS to listOf("拉火焰图", "定接口契约"),
            ),
        )
        assertTrue(text.contains("1 目标与成功标准："))
        assertTrue(text.contains(" - 首屏降到 1.2s"))
        assertTrue(text.contains("4 第一步动作清单："))
        assertTrue(text.contains(" - 拉火焰图"))
    }

    @Test
    fun `内部状态与精确度缺口会进上下文`() {
        val context = Fixtures.context().copy(
            state = EngineState.NEEDS_PRECISION,
            pendingIssues = listOf(
                com.askfirst.domain.model.PrecisionIssue(
                    kind = com.askfirst.domain.model.PrecisionKind.HIDDEN_DEFAULT,
                    target = "col2",
                    detail = "「保存」没说清是哪种",
                    quoted = "先保存下来",
                ),
            ),
        )
        val text = builder.engineMessages(context).last().content
        assertTrue(text.contains("state = needs_precision"))
        assertTrue(text.contains("hidden_default"))
        assertTrue(text.contains("先保存下来"))
    }

    @Test
    fun `出单上下文包含五栏与原文`() {
        val sheet = builder.sheetMessages(
            SheetContext(
                scene = Fixtures.techScene,
                intent = "把首屏从 4 秒降到 1 秒",
                turns = listOf(Fixtures.userTurn("m1", "打开就要四秒")),
                draftColumns = mapOf(Cols.GOAL to listOf("首屏降到 1.2s")),
            ),
        )
        val block = sheet.last().content
        assertTrue(block.contains("1 目标与成功标准："))
        assertTrue(block.contains("5 还没想清楚的问题："))
        assertTrue(block.contains("打开就要四秒"))
    }

    // ------------------------------------------------------------------ M4

    @Test
    fun `引用段只在带引用时出现 且只给摘要与背景说明`() {
        val noRef = builder.engineMessages(Fixtures.context()).last().content
        assertFalse("没引用不许出现该段", noRef.contains("## 引用的旧确认单摘要"))

        val context = Fixtures.context().copy(
            referencedSheets = listOf(
                com.askfirst.domain.model.SheetDigest(
                    sheetId = "sh_a",
                    sceneId = "scene_proj",
                    summary = "上个月立项：最小闭环是给 20 个内测用户",
                    createdAt = 1L,
                ),
            ),
        )
        val text = builder.engineMessages(context).last().content
        assertTrue(text.contains("## 引用的旧确认单摘要"))
        assertTrue(text.contains("上个月立项：最小闭环是给 20 个内测用户"))
        assertTrue("不许替他回答的约束要在", text.contains("只作背景"))
    }

    @Test
    fun `引用段在最后 不挤掉状态段`() {
        val context = Fixtures.context().copy(
            referencedSheets = listOf(
                com.askfirst.domain.model.SheetDigest("sh_b", "scene_retro", "复盘：缓存命中率 90%", 2L),
            ),
        )
        val text = builder.engineMessages(context).last().content
        assertTrue("引用段在状态段之后", text.indexOf("## 当前状态") < text.indexOf("## 引用的旧确认单摘要"))
    }

    // ------------------------------------------------------------------ M5

    private val skeleton = listOf("背景与问题", "约束", "待决问题")

    private fun planSheet(): com.askfirst.domain.model.Sheet = com.askfirst.domain.model.Sheet(
        id = "sh_1",
        sessionId = "s1",
        summary = "",
        items = mapOf(
            Cols.GOAL to listOf(
                com.askfirst.domain.model.SheetItem(
                    id = "it_1",
                    sheetId = "sh_1",
                    col = Cols.GOAL,
                    order = 0,
                    text = "首屏 P90 从 4s 降到 1.2s",
                    sourceMessageIds = listOf("m1"),
                    edited = false,
                ),
            ),
        ),
        createdAt = 0,
        updatedAt = 0,
    )

    private fun planBlock(
        sheet: com.askfirst.domain.model.Sheet? = planSheet(),
        existing: List<com.askfirst.domain.model.PlanSection> = emptyList(),
        missing: List<String> = emptyList(),
    ): String = builder.planMessages(
        PlanContext(
            scene = Fixtures.techScene,
            intent = "把首屏从 4 秒降到 1 秒",
            turns = listOf(Fixtures.userTurn("m1", "打开就要四秒")),
            sheet = sheet,
            skeleton = skeleton,
            existing = existing,
            missingHeadings = missing,
        ),
    ).last().content

    @Test
    fun `方案上下文按骨架顺序列小节 并带消息 id`() {
        val text = planBlock()
        val first = text.indexOf("1. 背景与问题")
        val second = text.indexOf("2. 约束")
        val third = text.indexOf("3. 待决问题")
        assertTrue("三节都要在: $first/$second/$third", first in 0 until second && second < third)
        assertTrue("要给出可引用的消息 id", text.contains("[m1]"))
        assertTrue("条目要带出处", text.contains("出处 m1"))
    }

    @Test
    fun `续写时只给已有章节标题和缺章清单`() {
        val existing = listOf(
            com.askfirst.domain.model.PlanSection(
                id = "ps_1",
                order = 1,
                heading = "背景与问题",
                body = "已经写好的一节。",
                sourceMessageIds = listOf("m1"),
            ),
        )
        val text = planBlock(existing = existing, missing = listOf("约束", "待决问题"))
        assertTrue(text.contains("## 已经写好的章节"))
        assertTrue(text.contains("- 背景与问题"))
        assertTrue(text.contains("## 这一次只补下面这几节"))
        assertTrue(text.contains("1. 约束"))
        assertTrue(text.contains("2. 待决问题"))
        assertFalse("已有章节的正文不该再塞回去让模型重写", text.contains("已经写好的一节。"))
    }

    @Test
    fun `没有单子时明说只能靠问答`() {
        val text = planBlock(sheet = null)
        assertTrue(text.contains("（还没出单，只能靠问答）"))
    }
}