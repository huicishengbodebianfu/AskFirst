package com.askfirst.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SSE 分帧（S3 验收）：只测分帧，不测 JSON 语义。 */
class SseParserTest {

    @Test
    fun `单帧 data 在空行处 dispatch`() {
        val parser = SseParser()
        assertNull(parser.feed("data: hello"))
        val frame = parser.feed("")
        assertEquals("hello", frame?.data)
    }

    @Test
    fun `多行 data 用换行拼起来`() {
        val parser = SseParser()
        parser.feed("data: line1")
        parser.feed("data: line2")
        val frame = parser.feed("")
        assertEquals("line1\nline2", frame?.data)
    }

    @Test
    fun `注释行被忽略 event 字段保留`() {
        val parser = SseParser()
        parser.feed(": keep-alive")
        parser.feed("event: message")
        parser.feed("data: {\"a\":1}")
        val frame = parser.feed("")
        assertEquals("message", frame?.event)
        assertEquals("{\"a\":1}", frame?.data)
    }

    @Test
    fun `DONE 帧被识别为 sentinel`() {
        val frame = SseParser().feedAll("data: [DONE]\n\n").single()
        assertTrue(frame.isDoneSentinel)
    }

    @Test
    fun `CRLF 与末尾缺空行都能正常收尾`() {
        val frames = SseParser().feedAll("data: a\r\ndata: b\r\n")
        assertEquals(1, frames.size)
        assertEquals("a\nb", frames[0].data)
    }

    @Test
    fun `空帧不产生 frame 也不带脏状态`() {
        val parser = SseParser()
        assertNull(parser.feed(""))
        assertNull(parser.feed("data: x"))
        assertEquals("x", parser.feed("")?.data)
        assertNull(parser.feed(""))
    }
}