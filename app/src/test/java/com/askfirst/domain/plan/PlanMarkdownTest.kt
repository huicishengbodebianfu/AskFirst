package com.askfirst.domain.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 落盘 Markdown：脚注按首次出现编号、文末引原话、文件名按方案 §3.5 的样式。 */
class PlanMarkdownTest {

    private val markdown = PlanMarkdown()
    private val now = PlanFixtures.fixedNow()

    private val sections = listOf(
        PlanFixtures.section(
            id = "ps_1",
            order = 1,
            heading = "背景与问题",
            body = "首屏白屏 4 秒，第一屏内容一个都没出来。",
            sources = listOf("m1", "m2"),
        ),
        PlanFixtures.section(
            id = "ps_2",
            order = 2,
            heading = "约束",
            body = "一个人力，两周内要能用上。",
            sources = listOf("m3"),
        ),
        PlanFixtures.section(
            id = "ps_3",
            order = 3,
            heading = "待决问题",
            body = "缓存上限怎么定，还没想清楚。",
            sources = listOf("m1"),
        ),
    )

    @Test
    fun `标题场景日期都在`() {
        val md = markdown.export(PlanFixtures.plan(sections), "技术方案 · 架构选型", PlanFixtures.turns(), now)
        assertTrue(md.startsWith("# 首屏缓存分层方案"))
        assertTrue(md.contains("- 场景：技术方案 · 架构选型"))
        assertTrue(md.contains("- 日期：2026年11月24日"))
    }

    @Test
    fun `每节带序号_脚注按首次出现编号`() {
        val md = markdown.export(PlanFixtures.plan(sections), "技术方案", PlanFixtures.turns(), now)
        assertTrue(md.contains("## 1. 背景与问题"))
        assertTrue(md.contains("## 3. 待决问题"))
        // m1 第一次出现在第 1 节 → [^1]；m3 第一次出现在第 2 节 → [^3]
        assertTrue(md.contains("[^1][^2]"))
        assertTrue(md.contains("[^3]"))
        assertTrue(md.contains("## 出处"))
        assertTrue(md.contains("[^1] 你：打开首屏白屏四秒，第一屏内容一个都没出来"))
        assertTrue(md.contains("[^2] 它：你说的「慢」是首屏打开就慢，不是滚动卡。"))
    }

    @Test
    fun `待决问题原话不被软化`() {
        val md = markdown.export(PlanFixtures.plan(sections), "技术方案", PlanFixtures.turns(), now)
        assertTrue(md.contains("缓存上限怎么定，还没想清楚。"))
        assertFalse(md.contains("持续优化"))
    }

    @Test
    fun `找不到原话时给出占位而不是空白`() {
        val plan = PlanFixtures.plan(listOf(PlanFixtures.section(sources = listOf("m_ghost"))))
        val md = markdown.export(plan, null, emptyList(), now)
        assertTrue(md.contains("（当时的原话已经找不到了）"))
        assertTrue(md.contains("- 场景：（场景已删除）"))
    }

    @Test
    fun `文件名按日期加标题`() {
        assertEquals("2026-11-24-首屏缓存分层方案.md", markdown.fileName(PlanFixtures.plan(), now))
    }

    @Test
    fun `文件名替换非法字符并截断`() {
        val plan = PlanFixtures.plan(title = "a/b:c*d?e\"f<g>h|i 方案（第二版）")
        val name = markdown.fileName(plan, now)
        assertFalse(name.contains(Regex("""[\\/:*?"<>|]""")))
        assertFalse(name.contains(" "))
        assertTrue(name.endsWith(".md"))
        assertTrue(name.startsWith("2026-11-24-"))

        val long = markdown.fileName(PlanFixtures.plan(title = "标".repeat(60)), now)
        assertTrue(long.length <= "2026-11-24-".length + 24 + ".md".length)
    }

    @Test
    fun `不出现内部 id 与字段名`() {
        val md = markdown.export(PlanFixtures.plan(sections), "技术方案", PlanFixtures.turns(), now)
        listOf("ps_1", "s_1", "sheetId", "sessionId", "sections", "sources").forEach { word ->
            assertFalse("导出不许出现内部词 $word", md.contains(word))
        }
    }
}
