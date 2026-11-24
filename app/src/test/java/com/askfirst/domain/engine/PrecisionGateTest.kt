package com.askfirst.domain.engine

import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.PrecisionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 精确度四项检查（S4 验收）：离线规则，不依赖模型。 */
class PrecisionGateTest {

    private val gate = PrecisionGate()

    private fun check(
        turns: List<com.askfirst.domain.model.Turn> = emptyList(),
        draft: Map<Int, List<String>> = emptyMap(),
        intent: String = "把首屏从 4 秒降到 1 秒",
    ) = gate.check(GateInput(scene = Fixtures.techScene, intent = intent, turns = turns, draft = draft))

    @Test
    fun `隐藏默认值 命中保存就追问存哪儿`() {
        val issues = check(turns = listOf(Fixtures.userTurn("m1", "先把数据保存下来再说")))
        val hidden = issues.filter { it.kind == PrecisionKind.HIDDEN_DEFAULT }
        assertEquals(1, hidden.size)
        assertTrue(hidden.single().detail.contains("保存"))
        assertTrue(hidden.single().detail.contains("存本地文件") || hidden.single().detail.contains("存数据库"))
        assertTrue(hidden.single().quoted.isNotBlank())
    }

    @Test
    fun `矛盾扫描 不登录与每人看自己不能同时成立`() {
        val issues = check(
            turns = listOf(
                Fixtures.userTurn("m1", "不用登录就行，省事"),
                Fixtures.userTurn("m2", "但每个人要能看到自己的记录"),
            ),
        )
        val contradiction = issues.filter { it.kind == PrecisionKind.CONTRADICTION }
        assertEquals(1, contradiction.size)
        assertTrue(contradiction.single().detail.contains("哪条是你真正要的"))
        assertTrue(contradiction.single().quoted.contains("不"))
    }

    @Test
    fun `矛盾规则对不命中时不出矛盾`() {
        val issues = check(turns = listOf(Fixtures.userTurn("m1", "不用登录，就我一个人用")))
        assertFalse(issues.any { it.kind == PrecisionKind.CONTRADICTION })
    }

    @Test
    fun `场景走查 第4栏不足两条动作就算缺口`() {
        val short = check(draft = mapOf(Cols.FIRST_STEPS to listOf("优化性能")))
        assertTrue(short.any { it.kind == PrecisionKind.WALKTHROUGH_GAP && it.detail.contains("1 条") })

        val enough = check(
            draft = mapOf(
                Cols.FIRST_STEPS to listOf("今天拉首屏火焰图", "本周定聚合层接口契约"),
            ),
        )
        assertFalse(enough.any { it.kind == PrecisionKind.WALKTHROUGH_GAP })
    }

    @Test
    fun `目标一致性 第1栏还是空的就提示`() {
        val issues = check(draft = emptyMap())
        assertTrue(issues.any { it.kind == PrecisionKind.GOAL_DRIFT && it.target == "col1" })
    }

    @Test
    fun `目标一致性 说 A 写 B 要指出`() {
        val issues = check(draft = mapOf(Cols.GOAL to listOf("数据库表结构改造完成")))
        val drift = issues.filter { it.kind == PrecisionKind.GOAL_DRIFT }
        assertEquals(1, drift.size)
        assertTrue(drift.single().detail.contains("同一件事"))
    }

    @Test
    fun `目标一致性 同一件事换个说法不算漂移`() {
        val issues = check(draft = mapOf(Cols.GOAL to listOf("首屏 P90 从 4s 降到 1.2s")))
        assertFalse(issues.any { it.kind == PrecisionKind.GOAL_DRIFT })
    }

    @Test
    fun `重复命中同一歧义词只报一次`() {
        val issues = check(
            turns = listOf(
                Fixtures.userTurn("m1", "先保存下来"),
                Fixtures.userTurn("m2", "保存以后再说"),
            ),
        )
        assertEquals(1, issues.count { it.kind == PrecisionKind.HIDDEN_DEFAULT })
    }
}