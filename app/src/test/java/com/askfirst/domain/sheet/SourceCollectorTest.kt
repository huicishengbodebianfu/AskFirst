package com.askfirst.domain.sheet

import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「点条目看出处」的取数逻辑（M1-07 的最后一条判据）。 */
class SourceCollectorTest {

    private fun turn(id: String, text: String, role: TurnRole = TurnRole.USER) =
        Turn(id = id, sessionId = "s", role = role, text = text, createdAt = 1)

    @Test
    fun `按条目记录的 id 顺序还原原话`() {
        val turns = listOf(
            turn("m1", "首屏打开就要四秒"),
            turn("m2", "只有我一个人力"),
            turn("m3", "先做本地缓存"),
        )
        val picked = SourceCollector.collect(turns, listOf("m3", "m1"))
        assertEquals(listOf("m3", "m1"), picked.map { it.id })
        assertEquals("先做本地缓存", picked.first().text)
    }

    @Test
    fun `找不到的 id 跳过 不许崩`() {
        val turns = listOf(turn("m1", "a"))
        val picked = SourceCollector.collect(turns, listOf("m1", "missing_1", "missing_2"))
        assertEquals(1, picked.size)
        assertEquals("m1", picked.single().id)
    }

    @Test
    fun `空出处返回空列表`() {
        assertTrue(SourceCollector.collect(listOf(turn("m1", "a")), emptyList()).isEmpty())
    }
}