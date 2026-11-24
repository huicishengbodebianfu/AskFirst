package com.askfirst.domain.sheet

import com.askfirst.domain.model.AskSession
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.SessionStatus
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetItem
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** M2 导出 Markdown：模板逐字稳定、不泄内部词、文件名合法（§docs/19 后续）。 */
class MarkdownExporterTest {

    private val exporter = MarkdownExporter()

    private val session = AskSession(
        id = "s_abcdefgh12345678",
        sceneId = "sc_1",
        intent = "首屏 4 秒太慢，想加缓存",
        status = SessionStatus.DONE,
        state = EngineState.READY,
        summary = "缓存方案定了",
        createdAt = 0,
        updatedAt = 0,
    )

    private val scene = Scene(id = "sc_1", name = "技术方案 · 架构选型", focus = "", builtin = true, questions = emptyList())

    private fun item(col: Int, order: Int, text: String, edited: Boolean = false) = SheetItem(
        id = "it_$col$order",
        sheetId = "sh_1",
        col = col,
        order = order,
        text = text,
        sourceMessageIds = listOf("m1"),
        edited = edited,
    )

    private val sheet = Sheet(
        id = "sh_1",
        sessionId = session.id,
        summary = "",
        items = listOf(
            item(Cols.GOAL, 0, "首屏 P90 从 4s 降到 1.2s"),
            item(Cols.FIRST_STEPS, 0, "拉火焰图"),
            item(Cols.FIRST_STEPS, 1, "定聚合层契约", edited = true),
        ).groupBy { it.col },
        createdAt = 0,
        updatedAt = 0,
    )

    private val turns = listOf(
        Turn("m1", session.id, TurnRole.USER, "打开就白屏四秒", createdAt = 0),
        Turn("m2", session.id, TurnRole.AI, "方案定了。\n还有别的想法吗？", createdAt = 1),
    )

    @Test
    fun `标题场景摘要都在`() {
        val md = exporter.export(scene, session, sheet, turns)
        assertTrue(md.contains("# 思路确认单：首屏 4 秒太慢，想加缓存"))
        assertTrue(md.contains("- 场景：技术方案 · 架构选型"))
        assertTrue(md.contains("- 摘要：缓存方案定了"))
    }

    @Test
    fun `空栏写还没定_不省略标题`() {
        val md = exporter.export(scene, session, sheet, turns)
        assertTrue(md.contains("### 约束与前提"))
        assertTrue(md.contains("- （还没定）"))
    }

    @Test
    fun `手改条目带改标记`() {
        val md = exporter.export(scene, session, sheet, turns)
        assertTrue(md.contains("- 定聚合层契约（改）"))
        assertFalse(md.contains("- 拉火焰图（改）"))
    }

    @Test
    fun `问答区块你它交替_多行消息逐行引用`() {
        val md = exporter.export(scene, session, sheet, turns)
        assertTrue(md.contains("## 当时聊了什么"))
        assertTrue(md.contains("**你**："))
        assertTrue(md.contains("**它**："))
        assertTrue(md.contains("> 打开就白屏四秒"))
        assertTrue(md.contains("> 方案定了。"))
        assertTrue(md.contains("> 还有别的想法吗？"))
    }

    @Test
    fun `没有单子也有占位`() {
        val md = exporter.export(scene, session, null, emptyList())
        assertTrue(md.contains("（这次没有出单）"))
        assertTrue(md.contains("（没有问答记录）"))
    }

    @Test
    fun `不出现内部状态机词`() {
        val md = exporter.export(scene, session, sheet, turns)
        listOf("gathering", "needs_precision", "blocked", "ready", "done", "open", "draft", "sheetId").forEach { word ->
            assertFalse("导出不许出现内部词 $word", md.contains(word))
        }
    }

    @Test
    fun `文件名合法且带会话尾`() {
        val name = exporter.fileName(session)
        assertEquals("askfirst-缓存方案定了-12345678.md", name)
        val illegal = session.copy(summary = "a/b:c*d?e\"f<g>h|i")
        val bad = exporter.fileName(illegal)
        assertFalse(bad.contains(Regex("""[\\/:*?"<>|]""")))
        assertFalse(bad.contains(" "))
    }

    @Test
    fun `空摘要时文件名退回开题句`() {
        assertEquals("askfirst-首屏_4_秒太慢_想加缓存-12345678.md", exporter.fileName(session.copy(summary = null)))
    }
}
