package com.askfirst.domain.engine

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型输出的容错解析（S4 验收）。 */
class TurnParserTest {

    private val parser = TurnParser()

    @Test
    fun `干净的 JSON 直接过`() {
        val result = parser.parse("""{"state":"gathering","clarify":"我先确认一下","covered":["tech_q1"]}""")
        assertTrue(result is AppResult.Ok)
        val payload = (result as AppResult.Ok).value
        assertEquals("gathering", payload.state)
        assertEquals(listOf("tech_q1"), payload.covered)
    }

    @Test
    fun `带代码围栏也能过`() {
        val raw = """
            ```json
            {"state":"ready","clarify":"懂了"}
            ```
        """.trimIndent()
        val payload = (parser.parse(raw) as AppResult.Ok).value
        assertEquals("ready", payload.state)
    }

    @Test
    fun `前后有解释文字时取第一个配平对象`() {
        val raw = "好的，我这样问：{\"clarify\":\"你说的是慢\",\"state\":\"gathering\"} 以上就是我的问题。"
        val payload = (parser.parse(raw) as AppResult.Ok).value
        assertEquals("你说的是慢", payload.clarify)
    }

    @Test
    fun `字符串里带右花括号不会把对象截断`() {
        val raw = """{"clarify":"如果写 {\"a\":1} 这种内容","state":"gathering"}"""
        val payload = (parser.parse(raw) as AppResult.Ok).value
        assertEquals("gathering", payload.state)
        assertTrue(payload.clarify!!.contains("\"a\""))
    }

    @Test
    fun `尾逗号被修掉`() {
        val raw = """{"state":"gathering","covered":["tech_q1",],"questions":[{"id":"tech_q2","text":"边界？",},]}"""
        val payload = (parser.parse(raw) as AppResult.Ok).value
        assertEquals(listOf("tech_q1"), payload.covered)
        assertEquals("tech_q2", payload.questions.single().id)
    }

    @Test
    fun `缺字段用默认值 不整轮失败`() {
        val payload = (parser.parse("""{"state":"gathering"}""") as AppResult.Ok).value
        assertTrue(payload.covered.isEmpty())
        assertTrue(payload.questions.isEmpty())
        assertTrue(payload.sheetDelta.isEmpty())
        assertEquals(null, payload.nudge)
    }

    @Test
    fun `未知字段被忽略 不炸`() {
        val raw = """{"state":"ready","extra_field":{"x":1},"another":"y"}"""
        assertTrue(parser.parse(raw) is AppResult.Ok)
    }

    @Test
    fun `不是 JSON 时返回 BadJson 并保留原文`() {
        val raw = "抱歉，我不能这样回答你。"
        val result = parser.parse(raw)
        assertTrue(result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.BadJson)
        assertEquals(raw, (error as AppError.BadJson).raw)
    }

    @Test
    fun `空输出返回 BadJson`() {
        assertTrue(parser.parse("   ") is AppResult.Err)
    }
}