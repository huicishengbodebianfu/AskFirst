package com.askfirst.remote

import com.askfirst.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OpenAI 风格 chunk 的语义解析（S3 验收）。 */
class ChatChunkParserTest {

    private fun parse(json: String) = ChatChunkParser.parse(SseFrame(null, json))

    @Test
    fun `delta content 取出增量`() {
        val result = parse("""{"choices":[{"index":0,"delta":{"content":"先问"}}]}""")
        assertTrue(result is ChatChunkParser.Result.Delta)
        assertEquals("先问", (result as ChatChunkParser.Result.Delta).text)
    }

    @Test
    fun `只带 role 的第一帧被忽略而不是崩`() {
        val result = parse("""{"choices":[{"index":0,"delta":{"role":"assistant"}}]}""")
        assertTrue(result is ChatChunkParser.Result.Ignored)
    }

    @Test
    fun `finish_reason 触发收尾`() {
        val result = parse("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
        assertTrue(result is ChatChunkParser.Result.Finish)
        assertEquals("stop", (result as ChatChunkParser.Result.Finish).reason)
    }

    @Test
    fun `DONE sentinel 也算收尾`() {
        val result = ChatChunkParser.parse(SseFrame(null, "[DONE]"))
        assertTrue(result is ChatChunkParser.Result.Finish)
    }

    @Test
    fun `流里带的错误映射成可读错误`() {
        val result = parse("""{"error":{"message":"Invalid API key","code":401}}""")
        assertTrue(result is ChatChunkParser.Result.Failure)
        val error = (result as ChatChunkParser.Result.Failure).error
        assertTrue(error is AppError.Auth)
        assertTrue(error.message!!.contains("Invalid API key"))
    }

    @Test
    fun `脏数据被忽略 不打断整条流`() {
        assertTrue(parse("<html>bad</html>") is ChatChunkParser.Result.Ignored)
        assertTrue(parse("") is ChatChunkParser.Result.Ignored)
        assertTrue(parse("""{"choices":[]}""") is ChatChunkParser.Result.Ignored)
    }
}