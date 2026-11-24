package com.askfirst.domain.model

import kotlinx.serialization.Serializable

/** 一次会话（对应 session 表）。 */
data class AskSession(
    val id: String,
    val sceneId: String,
    val intent: String,
    val status: SessionStatus,
    val state: EngineState,
    val summary: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val resumable: Boolean get() = status == SessionStatus.OPEN || status == SessionStatus.DRAFT
}

/** 一条消息（对应 message 表）。问答必须一起存，否则确认单失去可追溯性。 */
data class Turn(
    val id: String,
    val sessionId: String,
    val role: TurnRole,
    val text: String,
    val questions: List<EngineQuestion> = emptyList(),
    val covered: Set<String> = emptySet(),
    val stateAfter: EngineState? = null,
    val createdAt: Long,
)

/** 引擎这一轮问的问题。why 必须显示出来（方案 §3.1）。 */
@Serializable
data class EngineQuestion(
    val id: String,
    val text: String,
    val why: String,
    val kind: QuestionKind = QuestionKind.OPEN,
    /** grill-me：每个问题都附推荐答案 */
    val recommended: String? = null,
    /** 默认空 = 开放式；非空时 UI 自动追加「我自己写」 */
    val options: List<String> = emptyList(),
)

/** 精确度缺口。只进引擎上下文与第 5 栏，不直接暴露给用户。 */
data class PrecisionIssue(
    val kind: PrecisionKind,
    val target: String,
    val detail: String,
    /** 用户原话片段，冲突类证据必须引用 */
    val quoted: String,
)

/** 引擎一轮的结构化产出。 */
data class EngineDecision(
    val state: EngineState,
    val clarify: String?,
    val covered: Set<String>,
    val questions: List<EngineQuestion>,
    val sheetDeltas: List<SheetDelta>,
    val issues: List<PrecisionIssue>,
    val nudge: String?,
    val blockReason: String?,
) {
    /** 这一轮 AI 的消息正文：复述 + 问题（问题正文在 Message 里也要看得见，便于回看）。 */
    fun toAssistantText(): String = buildString {
        if (!clarify.isNullOrBlank()) {
            appendLine(clarify)
            appendLine()
        }
        questions.forEachIndexed { index, q ->
            if (index > 0) appendLine()
            appendLine(q.text)
            if (q.why.isNotBlank()) appendLine("（为什么问这个：${q.why}）")
        }
        when {
            // 阻断原因必须进正文：只把它放在卡片的另一个字段里，
            // 「状态 blocked + 没有新问题 + clarify 为空」时正文会是空串，
            // 聊天里就多出一条空气泡（docs/28 P2-6）
            !blockReason.isNullOrBlank() -> {
                if (questions.isNotEmpty()) appendLine()
                appendLine(blockReason)
            }

            questions.isEmpty() -> {
                appendLine("我这边没有新问题要问了。你要是觉得够了，可以点「够了，出确认单」。")
            }
        }
    }
}