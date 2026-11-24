package com.askfirst.domain.plan

import com.askfirst.core.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 方案 JSON 解析：容错、出处白名单、D4（无出处不产出）。 */
class PlanParserTest {

    private val parser = PlanParser()
    private val known = setOf("m1", "m2", "m3")

    private fun ok(raw: String) = (parser.parse(raw, known) as AppResult.Ok).value
    private fun err(raw: String) = (parser.parse(raw, known) as AppResult.Err).error

    @Test
    fun `正常解析 段落按数组顺序`() {
        val draft = ok(
            """
            {"title":"首屏缓存分层方案","sections":[
              {"heading":"背景与问题","body":"白屏四秒","sources":["m1"]},
              {"heading":"约束","body":"一个人力两周","sources":["m3","m1"]}
            ]}
            """.trimIndent(),
        )
        assertEquals("首屏缓存分层方案", draft.title)
        assertEquals(listOf("背景与问题", "约束"), draft.sections.map { it.heading })
        assertEquals(listOf("m3", "m1"), draft.sections[1].sourceMessageIds)
    }

    @Test
    fun `代码围栏与尾逗号都能吃`() {
        val draft = ok(
            """
            ```json
            {"title":"方案","sections":[{"heading":"结论","body":"先做本地缓存","sources":["m2"],},],}
            ```
            """.trimIndent(),
        )
        assertEquals("结论", draft.sections.single().heading)
    }

    @Test
    fun `编造的出处被丢掉_只剩编造出处的段落整段不要`() {
        val draft = ok(
            """
            {"title":"方案","sections":[
              {"heading":"背景与问题","body":"白屏四秒","sources":["m_ghost","m1"]},
              {"heading":"约束","body":"凭空写的约束","sources":["m_fake"]}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("背景与问题"), draft.sections.map { it.heading })
        assertEquals(listOf("m1"), draft.sections.single().sourceMessageIds)
    }

    @Test
    fun `每段都编造出处时直接报错`() {
        val error = err("""{"title":"方案","sections":[{"heading":"结论","body":"x","sources":["m_ghost"]}]}""")
        assertTrue(error.message.orEmpty().contains("原话"))
    }

    @Test
    fun `空正文或空标题的段落被丢`() {
        val draft = ok(
            """
            {"title":"方案","sections":[
              {"heading":"","body":"没有标题","sources":["m1"]},
              {"heading":"结论","body":"   ","sources":["m1"]},
              {"heading":"约束","body":"一个人力","sources":["m1"]}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("约束"), draft.sections.map { it.heading })
    }

    @Test
    fun `标题缺失时留空串_让上层回落`() {
        val draft = ok("""{"sections":[{"heading":"结论","body":"先做本地缓存","sources":["m1"]}]}""")
        assertEquals("", draft.title)
    }

    @Test
    fun `同一个标题只留第一次`() {
        val draft = ok(
            """
            {"title":"方案","sections":[
              {"heading":"约束","body":"一个人力","sources":["m1"]},
              {"heading":"目标与非目标","body":"首屏回到 1 秒","sources":["m2"]},
              {"heading":" 约束 ","body":"模型又说了一遍","sources":["m3"]}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("约束", "目标与非目标"), draft.sections.map { it.heading })
        assertEquals("一个人力", draft.sections.first().body)
    }

    @Test
    fun `非法 JSON 与空输出都报可读错误`() {
        assertTrue(err("这是一段散文，没有 JSON").message.orEmpty().isNotBlank())
        assertTrue(err("").message.orEmpty().contains("没有输出"))
        assertTrue(err("""{"title":"x","sections":[]}""").message.orEmpty().isNotBlank())
    }
}
