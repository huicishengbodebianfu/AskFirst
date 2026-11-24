package com.askfirst.domain.sheet

import com.askfirst.core.IdSource
import com.askfirst.core.SystemClock
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.QuestionKind
import com.askfirst.domain.model.Question
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetItem
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 出单：解析、合并、导出模板（S6 验收，M1-07/08/09）。 */
class SheetBuildTest {

    private val ids = object : IdSource {
        private var n = 0
        override fun newId(prefix: String): String = "${prefix}_${++n}"
    }

    private fun scene() = Scene(
        id = "s",
        name = "技术方案",
        focus = "f",
        builtin = true,
        questions = listOf(
            Question("tech_q1", "s", 1, "痛点？", "why", true, QuestionKind.OPEN),
        ),
    )

    private fun user(id: String, text: String) = Turn(id, "sess", TurnRole.USER, text, createdAt = 1)
    private fun ai(id: String, text: String) = Turn(id, "sess", TurnRole.AI, text, createdAt = 2)

    private fun sheet(items: List<SheetItem> = emptyList()) = Sheet(
        id = "sh_1",
        sessionId = "sess",
        summary = "",
        items = items.groupBy { it.col },
        createdAt = 0,
        updatedAt = 0,
    )

    private fun item(col: Int, order: Int, text: String, edited: Boolean = false) = SheetItem(
        id = "it_$col$order",
        sheetId = "sh_1",
        col = col,
        order = order,
        text = text,
        sourceMessageIds = listOf("m_old"),
        edited = edited,
    )

    private val payload = SheetPayload(
        summary = "首屏缓存分层",
        columns = mapOf(
            "1" to listOf("首屏 P90 从 4s 降到 1.2s"),
            "2" to listOf("一个人力，两周内上线"),
            "3" to listOf("选定：本地缓存 + 聚合层", "放弃：只上 CDN"),
            "4" to listOf("今天：拉首屏火焰图", "本周：定接口契约"),
            "5" to listOf("还没定：失效策略"),
            "9" to listOf("越界栏必须丢掉"),
        ),
    )

    @Test
    fun `解析 把栏号规范化 丢掉越界与空条目`() {
        val parser = SheetParser()
        val result = parser.parse(
            """
            ```json
            {"summary":"x","columns":{"1":["a","  "],"6":["b"],"2":["c"]}}
            ```
            """.trimIndent(),
        )
        assertTrue(result is com.askfirst.core.AppResult.Ok)
        val cols = (result as com.askfirst.core.AppResult.Ok).value.normalizedColumns()
        assertEquals(setOf(1, 2), cols.keys)
        assertEquals(listOf("a"), cols[1])
    }

    @Test
    fun `解析 五栏全空要报错 而不是给一份空单`() {
        val result = SheetParser().parse("""{"summary":"x","columns":{}}""")
        assertTrue(result is com.askfirst.core.AppResult.Err)
    }

    @Test
    fun `合并 保留用户改过的条目 并替换模型生成的`() {
        val assembler = SheetAssembler(ids, SystemClock)
        val existing = sheet(
            listOf(
                item(1, 0, "我改过的目标", edited = true),
                item(2, 0, "旧的约束"),
            ),
        )
        val turns = listOf(user("m1", "首屏打开就要四秒"), ai("m2", "懂了"))
        val merged = assembler.merge(existing, payload, turns)

        assertEquals("用户改的要留在最前", "我改过的目标", merged.column(1).first().text)
        assertTrue("模型出的目标接在后面", merged.column(1).any { it.text.contains("P90") })
        assertTrue("旧的未编辑条目被替换掉", merged.column(2).none { it.text == "旧的约束" })
        assertEquals("首屏缓存分层", merged.summary)
    }

    @Test
    fun `合并 每条都要配到一条真实回答作为出处`() {
        val assembler = SheetAssembler(ids, SystemClock)
        val turns = listOf(
            user("m1", "首屏打开就要四秒，我等不及"),
            user("m2", "一个人力，两周内要能用上"),
        )
        val merged = assembler.merge(sheet(), payload, turns)
        val all = merged.allItems()
        assertTrue(all.isNotEmpty())
        assertTrue("不许出现无出处条目", all.all { it.sourceMessageIds.isNotEmpty() })
        assertTrue("出处必须是真实消息 id", all.all { it.sourceMessageIds.all { id -> id == "m1" || id == "m2" } })
        // 语义上最像的那句应该被配上
        val firstStep = merged.column(Cols.FIRST_STEPS).first()
        assertEquals(listOf("m1"), firstStep.sourceMessageIds)
    }

    @Test
    fun `出单卡住时按预算超时 并标记可降级`() = runTest {
        val builder = SheetBuilder(
            provider = com.askfirst.domain.engine.HangingProvider(),
            prompts = com.askfirst.domain.prompt.PromptBuilder(
                engineSystemPrompt = "s",
                sheetSystemPrompt = "s",
            ),
            parser = SheetParser(),
            assembler = SheetAssembler(ids, SystemClock),
            timeoutMs = 100,
        )
        val events = builder.build(
            com.askfirst.domain.prompt.SheetContext(
                scene = scene(),
                intent = "把首屏弄快",
                turns = emptyList(),
                draftColumns = emptyMap(),
            ),
            sheet(),
        ).toList()
        val failed = events.filterIsInstance<SheetEvent.Failed>().single()
        assertTrue(failed.error is com.askfirst.core.AppError.Timeout)
        assertTrue("超时要告诉用户可以用手里的内容先出单", failed.canFallback)
    }

    @Test
    fun `导出模板逐字比对 标题六行不许改`() {
        val exporter = PromptExporter()
        val sheet = sheet(
            listOf(
                item(1, 0, "首屏降到 1.2s"),
                item(2, 0, "一个人力，两周"),
                item(3, 0, "本地缓存 + 聚合层"),
                item(4, 0, "拉火焰图"),
                item(4, 1, "定接口契约"),
                item(5, 0, "失效策略未定"),
            ),
        )
        val text = exporter.export("首屏 4 秒太慢，想加缓存", sheet)
        val lines = text.trim().lines()

        assertEquals("我要做这件事：首屏 4 秒太慢，想加缓存", lines[0])
        assertTrue(lines[1].startsWith("目标与成功标准："))
        assertTrue(lines[2].startsWith("约束与前提："))
        assertTrue(lines[3].startsWith("已选定方案（放弃了 X，因为 Y）："))
        assertTrue(lines[4].startsWith("第一步："))
        assertTrue(lines[5].startsWith("这些我还没想清楚，别替我决定："))
        assertTrue("第一步多条用分号连", lines[4].contains("拉火焰图；定接口契约"))
        assertTrue(text.endsWith("不要重新问我已回答过的部分。"))
    }

    @Test
    fun `导出时条目自带句号也不出现双标点`() {
        val text = PromptExporter().export(
            "做个小工具",
            sheet(
                listOf(
                    item(2, 0, "只有一个人力。"),
                    item(2, 1, "两周内要能用上。"),
                ),
            ),
        )
        assertTrue(text.contains("约束与前提：只有一个人力；两周内要能用上"))
        assertTrue(!text.contains("。；"))
    }

    @Test
    fun `导出时空栏写还没定 而不是省略标题`() {
        val text = PromptExporter().export("做个小工具", sheet())
        assertTrue(text.contains("目标与成功标准：（还没定）"))
        assertTrue(text.contains("第一步：（还没定）"))
        assertEquals("六个标题都必须在", 6, text.trim().lines().count { it.contains("：") })
    }
}