package com.askfirst.domain.sheet

import com.askfirst.core.IdSource
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetCheck
import com.askfirst.domain.model.SheetDelta
import com.askfirst.domain.model.SheetItem
import com.askfirst.domain.model.SheetProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 确认单实时生长与出单门（S5/S6 验收，改动 #6 + M1-10）。 */
class SheetRulesTest {

    private val ids = object : IdSource {
        private var n = 0
        override fun newId(prefix: String): String = "${prefix}_${++n}"
    }

    private fun sheet(vararg items: SheetItem): Sheet = Sheet(
        id = "sh_1",
        sessionId = "s_1",
        summary = "",
        items = items.groupBy { it.col },
        createdAt = 0,
        updatedAt = 0,
    )

    private fun item(col: Int, order: Int, text: String, sources: List<String> = listOf("m1")) = SheetItem(
        id = "it_$col$order",
        sheetId = "sh_1",
        col = col,
        order = order,
        text = text,
        sourceMessageIds = sources,
        edited = false,
    )

    private fun delta(col: Int, text: String, sources: List<String> = listOf("m1")) =
        SheetDelta(col = col, text = text, sourceMessageIds = sources)

    @Test
    fun `没有出处的条目直接丢弃`() {
        val grower = SheetGrower(ids)
        val added = grower.newItems(sheet(), listOf(delta(1, "要快", sources = emptyList())))
        assertTrue(added.isEmpty())
    }

    @Test
    fun `同栏同文去重`() {
        val grower = SheetGrower(ids)
        val existing = sheet(item(1, 0, "首屏 4 秒"))
        val added = grower.newItems(
            existing,
            listOf(delta(1, "首屏   4 秒"), delta(1, "首屏 4 秒")),
        )
        assertTrue(added.isEmpty())
    }

    @Test
    fun `新条目接在栏尾 序号递增`() {
        val grower = SheetGrower(ids)
        val existing = sheet(item(4, 0, "拉火焰图"), item(4, 1, "定接口契约"))
        val added = grower.newItems(existing, listOf(delta(4, "今天先量一次首屏耗时")))
        assertEquals(1, added.size)
        assertEquals(2, added.single().order)
        assertEquals(4, added.single().col)
        assertEquals(listOf("m1"), added.single().sourceMessageIds)
    }

    @Test
    fun `每栏最多 8 条`() {
        val grower = SheetGrower(ids)
        val existing = sheet(*(0 until 8).map { item(1, it, "第 $it 条") }.toTypedArray())
        val added = grower.newItems(existing, listOf(delta(1, "第 9 条")))
        assertTrue(added.isEmpty())
    }

    @Test
    fun `非法栏号被丢弃`() {
        val grower = SheetGrower(ids)
        assertTrue(grower.newItems(sheet(), listOf(delta(9, "越界内容"))).isEmpty())
        assertTrue(grower.newItems(sheet(), listOf(delta(0, "越界内容"))).isEmpty())
    }

    @Test
    fun `grow 把新增条目合并进未落库的单子`() {
        val grower = SheetGrower(ids)
        val base = sheet(item(1, 0, "首屏 4 秒"))
        val grown = grower.grow(base, listOf(delta(1, "降到 1 秒"), delta(4, "拉火焰图")), now = 99L)
        assertEquals(2, grown.column(1).size)
        assertEquals(1, grown.column(4).size)
        assertEquals(99L, grown.updatedAt)
    }

    @Test
    fun `出单门 第4栏不足两条就是 blocking 且文案是人话`() {
        val validator = SheetValidator()
        val check = validator.check(
            sheet(
                item(1, 0, "首屏降到 1 秒"),
                item(4, 0, "优化性能"),
            ),
        )
        assertFalse(check.canStart)
        assertTrue(check.blocking.any { it is SheetProblem.NotEnoughFirstSteps })
        assertTrue(check.headline!!.contains("还不能直接开工"))
    }

    @Test
    fun `出单门 五栏齐全且动作够才可开工`() {
        val validator = SheetValidator()
        val check = validator.check(
            sheet(
                item(1, 0, "首屏 P90 从 4s 降到 1.2s"),
                item(2, 0, "只有一个人力，两周"),
                item(3, 0, "本地缓存 + 聚合层"),
                item(4, 0, "今天拉火焰图"),
                item(4, 1, "本周定接口契约"),
                item(5, 0, "缓存失效策略未定"),
            ),
        )
        assertTrue(check.canStart)
        assertEquals(null, check.headline)
        assertTrue(check.warnings.isEmpty())
    }

    @Test
    fun `出单门 空栏只警告不拦`() {
        val validator = SheetValidator()
        val check = validator.check(
            sheet(
                item(1, 0, "首屏降到 1 秒"),
                item(4, 0, "拉火焰图"),
                item(4, 1, "定接口契约"),
            ),
        )
        assertTrue(check.canStart)
        assertTrue(check.warningText!!.contains("约束与前提"))
    }

    @Test
    fun `第4栏动作带着没定的前提时给警告`() {
        val validator = SheetValidator()
        val check = validator.check(
            sheet(
                item(1, 0, "首屏降到 1 秒"),
                item(4, 0, "添加本地缓存（卡点未确认）"),
                item(4, 1, "实现聚合层（形态未定）"),
            ),
        )
        assertTrue("条数够，不拦", check.canStart)
        assertTrue(check.warningText!!.contains("还带着没定的前提"))
        assertTrue(check.warnings.any { it is SheetProblem.HedgedFirstSteps })
    }

    @Test
    fun `出单门 没有出处的条目是 blocking`() {
        val validator = SheetValidator()
        val check: SheetCheck = validator.check(
            sheet(
                item(1, 0, "首屏降到 1 秒"),
                item(4, 0, "拉火焰图", sources = emptyList()),
                item(4, 1, "定接口契约"),
            ),
        )
        assertFalse(check.canStart)
        assertTrue(check.blocking.any { it is SheetProblem.UnsourcedItem })
    }
}