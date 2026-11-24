package com.askfirst.domain.engine

import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.model.PrecisionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 四状态机（S4 验收）：模型只能建议，最终由规则裁决。 */
class StateMachineTest {

    private val machine = StateMachine()

    private val cleanDraft = mapOf(
        Cols.GOAL to listOf("首屏 P90 从 4s 降到 1.2s"),
        Cols.FIRST_STEPS to listOf("今天拉首屏接口耗时火焰图", "本周定聚合层接口契约"),
    )

    private fun evaluate(
        covered: Set<String> = emptySet(),
        draft: Map<Int, List<String>> = cleanDraft,
        modelSuggested: EngineState? = null,
        turns: List<com.askfirst.domain.model.Turn> = emptyList(),
    ) = machine.evaluate(
        StateInput(
            scene = Fixtures.techScene,
            intent = "把首屏从 4 秒降到 1 秒",
            turns = turns,
            draft = draft,
            covered = covered,
            modelSuggested = modelSuggested,
        ),
    )

    @Test
    fun `必问题没答完 模型说 ready 也不算`() {
        val verdict = evaluate(covered = setOf("tech_q1"), modelSuggested = EngineState.READY)
        assertEquals(EngineState.GATHERING, verdict.state)
    }

    @Test
    fun `必问题答完但第4栏动作不够 进精确度状态`() {
        val verdict = evaluate(
            covered = setOf("tech_q1", "tech_q2"),
            draft = cleanDraft + (Cols.FIRST_STEPS to listOf("优化性能")),
        )
        assertEquals(EngineState.NEEDS_PRECISION, verdict.state)
        assertTrue(verdict.firstStepShort)
    }

    @Test
    fun `四项全过才 ready`() {
        val verdict = evaluate(
            covered = setOf("tech_q1", "tech_q2"),
            modelSuggested = EngineState.READY,
        )
        assertEquals(EngineState.READY, verdict.state)
        assertTrue(verdict.issues.isEmpty())
    }

    @Test
    fun `矛盾必然阻断 即使模型说 ready`() {
        val verdict = evaluate(
            covered = setOf("tech_q1", "tech_q2"),
            modelSuggested = EngineState.READY,
            turns = listOf(
                Fixtures.userTurn("m1", "不用登录，省事"),
                Fixtures.userTurn("m2", "每个人看自己的内容"),
            ),
        )
        assertEquals(EngineState.BLOCKED, verdict.state)
        assertTrue(verdict.issues.any { it.kind == PrecisionKind.CONTRADICTION })
    }

    @Test
    fun `模型自己觉得还差精确度时 信它`() {
        val verdict = evaluate(
            covered = setOf("tech_q1", "tech_q2"),
            modelSuggested = EngineState.NEEDS_PRECISION,
        )
        assertEquals(EngineState.NEEDS_PRECISION, verdict.state)
    }

    // 注：「用户回改已确认内容 → 退回 gathering」原来测的是 StateInput.userEditedConfirmed，
    // 但生产代码从来没传过这个字段（等于规则不存在，docs/28 P2-8）。
    // 现在这条规则由 AskFirstFacade.editItem 执行（编辑发生时直接把 READY 会话退回 GATHERING），
    // 状态机本身不再需要这个输入，所以对应测试一并移除。

    // ---- docs/28 P1-1：缺口必须能被消解，否则 READY 永不可达 ----

    @Test
    fun `他回答完那个矛盾之后 阻断要能解除`() {
        // 前两轮说了会打架的两句话 → 这一轮阻断（正确）
        val blocked = evaluate(
            covered = setOf("tech_q1", "tech_q2"),
            turns = listOf(
                Fixtures.userTurn("m1", "不用登录，省事"),
                Fixtures.userTurn("m2", "每个人看自己的内容"),
            ),
        )
        assertEquals(EngineState.BLOCKED, blocked.state)

        // 他又答了两轮，明确表态 → 旧矛盾推出观察窗口，不该再钉在阻断上
        val after = evaluate(
            covered = setOf("tech_q1", "tech_q2"),
            turns = listOf(
                Fixtures.userTurn("m1", "不用登录，省事"),
                Fixtures.userTurn("m2", "每个人看自己的内容"),
                Fixtures.userTurn("m3", "那就加个登录吧，我要分账号"),
                Fixtures.userTurn("m4", "登录用手机号就够了"),
            ),
        )
        assertEquals("他回应过之后不该还阻断", EngineState.READY, after.state)
        assertTrue(after.issues.none { it.kind == PrecisionKind.CONTRADICTION })
    }

    @Test
    fun `早期说过一个歧义词 不该让后面每一轮都卡在精确度`() {
        val turns = listOf(
            Fixtures.userTurn("m1", "先把数据保存下来"),
            Fixtures.userTurn("m2", "只有我一个人力，两周内要能用上"),
            Fixtures.userTurn("m3", "就先做本地缓存，别的不动"),
        )
        val verdict = evaluate(covered = setOf("tech_q1", "tech_q2"), turns = turns)
        assertEquals("追问过一次就算了，不能永远到不了 ready", EngineState.READY, verdict.state)
    }
}