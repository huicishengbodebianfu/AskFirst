package com.askfirst.domain.engine

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.core.Text
import com.askfirst.domain.model.EngineDecision
import com.askfirst.domain.model.EngineQuestion
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.model.PrecisionIssue
import com.askfirst.domain.model.PrecisionKind
import com.askfirst.domain.model.QuestionKind
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import com.askfirst.domain.prompt.EngineContext
import com.askfirst.domain.prompt.PromptBuilder
import com.askfirst.remote.ChatMessage
import com.askfirst.remote.ChatRequest
import com.askfirst.remote.LlmEvent
import com.askfirst.remote.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 提问引擎（docs/04 §9 的七步）：
 * 组装 → 流式 → 解析 → （失败则一次低温柔性重试）→ 状态复核 → 归一化 → 决策。
 *
 * 纯 Kotlin（不碰 Android），用 FakeLlmProvider 就能在 JVM 单测里跑完整条链路。
 */
class QuestionEngine(
    private val provider: LlmProvider,
    private val prompts: PromptBuilder,
    private val parser: TurnParser,
    private val stateMachine: StateMachine = StateMachine(),
    private val temperature: Double = 0.6,
    private val maxTokens: Int = 1200,
    /** 单轮预算（可注入，便于单测把 75s 压成 100ms） */
    private val turnTimeoutMs: Long = TURN_TIMEOUT_MS,
    private val retryTimeoutMs: Long = RETRY_TIMEOUT_MS,
) {

    fun nextTurn(context: EngineContext): Flow<EngineStreamEvent> = flow {
        val messages = prompts.engineMessages(context)
        val full = StringBuilder()
        var failure: AppError? = null

        provider.stream(request(messages, temperature, turnTimeoutMs)).let { stream ->
            // 一轮提问的硬预算：模型卡住/网关半死不活时，不能让用户盯着转圈无限等
            val finished = withTimeoutOrNull(turnTimeoutMs) {
                stream.collect { event ->
                    when (event) {
                        is LlmEvent.Delta -> {
                            full.append(event.text)
                            // 给 UI 的只是"还在动"的进度，**绝不把 JSON 原文吐到界面上**
                            emit(EngineStreamEvent.Progress(full.length))
                        }

                        is LlmEvent.Done -> Unit
                        is LlmEvent.Failed -> failure = event.error
                    }
                }
                true
            }
            if (finished == null && failure == null) {
                failure = AppError.Timeout(turnTimeoutMs)
            }
        }

        failure?.let { error ->
            emit(EngineStreamEvent.Failed(error, full.toString().ifBlank { null }))
            return@flow
        }

        var raw = full.toString()
        var parsed = parser.parse(raw)

        if (parsed is AppResult.Err) {
            // 一次低温柔性重试：不重复吐字给 UI（避免同一段话说两遍），只重新拿结构化输出
            val retryMessages = messages + ChatMessage.user(RETRY_HINT)
            val retryFull = StringBuilder()
            var retryFailure: AppError? = null
            provider.stream(request(retryMessages, 0.2, retryTimeoutMs)).let { retryStream ->
                val finished = withTimeoutOrNull(retryTimeoutMs) {
                    retryStream.collect { event ->
                        when (event) {
                            is LlmEvent.Delta -> retryFull.append(event.text)
                            is LlmEvent.Done -> Unit
                            is LlmEvent.Failed -> retryFailure = event.error
                        }
                    }
                    true
                }
                if (finished == null && retryFailure == null) {
                    retryFailure = AppError.Timeout(retryTimeoutMs)
                }
            }
            retryFailure?.let { error ->
                emit(EngineStreamEvent.Failed(error, raw))
                return@flow
            }
            raw = retryFull.toString()
            parsed = parser.parse(raw)
        }

        when (parsed) {
            is AppResult.Err -> emit(EngineStreamEvent.Failed(parsed.error, raw))
            is AppResult.Ok -> {
                val decision = resolve(context, parsed.value)
                emit(EngineStreamEvent.Decided(decision, raw, decision.toAssistantText()))
            }
        }
    }

    private fun request(
        messages: List<ChatMessage>,
        temperature: Double,
        callTimeoutMs: Long,
    ): ChatRequest = ChatRequest(
        messages = messages,
        model = "", // 交给 provider 用配置里的 model 填
        temperature = temperature,
        maxTokens = maxTokens,
        stream = true,
        jsonObject = true,
        callTimeoutMs = callTimeoutMs,
    )

    /** 模型输出 → 领域决策：过滤编造的 id、补出处、复核状态、必要时代问。 */
    internal fun resolve(context: EngineContext, payload: EngineTurnPayload): EngineDecision {
        val sceneIds = context.scene.questions.map { it.id }.toSet()
        val lastUser = context.turns.lastOrNull { it.role == TurnRole.USER }

        val covered = payload.covered
            .map { it.trim() }
            .filter { it in sceneIds }
            .toSet()

        val questions = payload.questions
            .mapNotNull { raw -> toEngineQuestion(raw, context, sceneIds) }
            .take(3)

        // 出处白名单：引擎上下文里只印 `[U]`/`[A]`（PromptBuilder.transcript），
        // 模型根本拿不到真实消息 id，`source_message_ids` 只能是编的。
        // 编出来的 id 曾经一路进库，然后在「点条目看出处」处变成一片空白（docs/28 P0-2）。
        val knownTurnIds = context.turns.mapTo(HashSet()) { it.id }
        val deltas = payload.sheetDelta.mapNotNull { delta ->
            val text = Text.normalize(delta.text)
            if (text.isBlank() || delta.col !in 1..5) return@mapNotNull null
            val claimed = delta.sourceMessageIds
                .map { it.trim() }
                .filter { it in knownTurnIds }
                .distinct()
            val sources = if (claimed.isNotEmpty()) {
                claimed
            } else {
                // 兜底只允许"这条确实来自他最后一句"的情况，否则宁可丢（D4）
                val probe = text.take(minOf(8, text.length))
                if (lastUser != null && Text.normalize(lastUser.text).contains(probe)) {
                    listOf(lastUser.id)
                } else {
                    emptyList()
                }
            }
            if (sources.isEmpty()) return@mapNotNull null
            com.askfirst.domain.model.SheetDelta(col = delta.col, text = text, sourceMessageIds = sources)
        }

        val modelIssues = payload.precisionIssues.mapNotNull { issue ->
            val kind = PrecisionKind.fromApi(issue.kind) ?: return@mapNotNull null
            if (issue.detail.isBlank()) return@mapNotNull null
            PrecisionIssue(kind = kind, target = issue.target, detail = issue.detail, quoted = issue.quoted)
        }

        val suggested = EngineState.fromApi(payload.state) ?: context.state
        val base = EngineDecision(
            state = suggested,
            clarify = payload.clarify?.trim()?.takeIf { it.isNotBlank() },
            covered = covered,
            questions = questions,
            sheetDeltas = deltas,
            issues = modelIssues,
            nudge = payload.nudge?.trim()?.takeIf { it.isNotBlank() },
            blockReason = payload.blockReason?.trim()?.takeIf { it.isNotBlank() },
        )

        val verdict = stateMachine.evaluate(
            StateInput(
                scene = context.scene,
                intent = context.intent,
                turns = context.turns,
                draft = context.draftColumns,
                covered = covered,
                modelSuggested = suggested,
            ),
        )

        val questionsOrFallback = if (verdict.state != EngineState.READY && base.questions.isEmpty()) {
            fallbackQuestion(context, covered)?.let { listOf(it) } ?: emptyList()
        } else {
            base.questions
        }

        val blockReason = base.blockReason
            ?: verdict.issues.firstOrNull { it.kind == PrecisionKind.CONTRADICTION }?.detail

        return base.copy(
            state = verdict.state,
            questions = questionsOrFallback,
            issues = (base.issues + verdict.issues).distinctBy { it.kind to it.detail },
            blockReason = blockReason,
        )
    }

    private fun toEngineQuestion(
        raw: QuestionPayload,
        context: EngineContext,
        sceneIds: Set<String>,
    ): EngineQuestion? {
        val text = raw.text.trim()
        if (text.isBlank()) return null
        val id = raw.id.trim().takeIf { it in sceneIds }
            ?: context.scene.questions.firstOrNull { it.text.trim() == text }?.id
            ?: ""
        val options = raw.options.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4)
        val kind = if (raw.kind?.trim()?.lowercase() == "choice" && options.isNotEmpty()) {
            QuestionKind.CHOICE
        } else {
            QuestionKind.OPEN
        }
        return EngineQuestion(
            id = id,
            text = text,
            why = raw.why.trim(),
            kind = kind,
            recommended = raw.recommended?.trim()?.takeIf { it.isNotBlank() },
            options = if (kind == QuestionKind.CHOICE) options else emptyList(),
        )
    }

    private fun fallbackQuestion(context: EngineContext, covered: Set<String>): EngineQuestion? {
        val next = context.scene.questions
            .asSequence()
            .filterNot { it.id in covered }
            .sortedWith(compareByDescending<com.askfirst.domain.model.Question> { it.required }.thenBy { it.order })
            .firstOrNull() ?: return null
        return EngineQuestion(id = next.id, text = next.text, why = next.why, kind = next.kind)
    }

    companion object {
        /** 单轮预算：真实模型见过最慢的一次 5.4s，75s 是"明显不正常"的线 */
        const val TURN_TIMEOUT_MS = 75_000L
        private const val RETRY_TIMEOUT_MS = 40_000L
        private const val RETRY_HINT =
            "上一次的输出不是合法 JSON。只输出那个 JSON 对象，不要任何解释、不要代码围栏。"
    }
}

/** 引擎对外事件：进度 → 决策；失败也带原文（可回看、可重试）。 */
sealed interface EngineStreamEvent {
    /**
     * 已收到的字符数。**这不是要显示的正文**：模型这一轮输出的是 JSON，
     * 界面上只该显示"正在想…"这类进度，绝不把 JSON 原文吐给用户。
     */
    data class Progress(val receivedChars: Int) : EngineStreamEvent

    data class Decided(
        val decision: EngineDecision,
        val rawJson: String,
        val assistantText: String,
    ) : EngineStreamEvent

    data class Failed(val error: AppError, val rawText: String?) : EngineStreamEvent
}


