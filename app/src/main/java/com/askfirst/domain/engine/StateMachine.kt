package com.askfirst.domain.engine

import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.model.PrecisionIssue
import com.askfirst.domain.model.PrecisionKind
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.Turn

/**
 * 四状态机（改动 #3）。模型给的 `state` 只作为**建议**，最终由这里裁决：
 *
 * ```
 * gathering        → gathering | needs_precision | blocked
 * needs_precision  → needs_precision | blocked | ready   （四项全过才允许 ready）
 * blocked          → blocked | gathering
 * ready            → ready | gathering                  （用户改已确认内容 → 回退）
 * ```
 *
 * 两条不许违反：
 * 1. 必问题没答完，不许进 ready；
 * 2. 精确度缺口没清，不许进 ready（`needs_precision` 不能跳过）。
 */
class StateMachine(private val gate: PrecisionGate = PrecisionGate()) {

    fun evaluate(input: StateInput): StateVerdict {
        val issues = gate.check(
            GateInput(
                scene = input.scene,
                intent = input.intent,
                turns = input.turns,
                draft = input.draft,
            ),
        )
        val requiredAnswered = input.scene.questions
            .filter { it.required }
            .all { it.id in input.covered }
        val hasContradiction = issues.any { it.kind == PrecisionKind.CONTRADICTION }

        val state = when {
            input.modelSuggested == EngineState.BLOCKED -> EngineState.BLOCKED
            hasContradiction -> EngineState.BLOCKED

            !requiredAnswered -> EngineState.GATHERING

            issues.isNotEmpty() -> EngineState.NEEDS_PRECISION

            // 模型自己觉得还差精确度：信它（它看得到措辞上的含糊）
            input.modelSuggested == EngineState.NEEDS_PRECISION -> EngineState.NEEDS_PRECISION

            else -> EngineState.READY
        }

        return StateVerdict(state = state, issues = issues)
    }
}

data class StateInput(
    val scene: Scene,
    val intent: String,
    val turns: List<Turn>,
    val draft: Map<Int, List<String>>,
    val covered: Set<String>,
    val modelSuggested: EngineState? = null,
)

data class StateVerdict(val state: EngineState, val issues: List<PrecisionIssue>) {
    val requiredMissing: Boolean get() = state == EngineState.GATHERING
    val blocked: Boolean get() = state == EngineState.BLOCKED
    val firstStepShort: Boolean get() = issues.any { it.kind == PrecisionKind.WALKTHROUGH_GAP }
    val goalNotLanded: Boolean get() = issues.any { it.kind == PrecisionKind.GOAL_DRIFT && it.target == "col1" }
}