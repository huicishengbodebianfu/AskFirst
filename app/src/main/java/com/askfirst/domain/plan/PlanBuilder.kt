package com.askfirst.domain.plan

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.domain.model.PlanDraft
import com.askfirst.domain.prompt.PlanContext
import com.askfirst.domain.prompt.PromptBuilder
import com.askfirst.remote.ChatRequest
import com.askfirst.remote.LlmEvent
import com.askfirst.remote.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 生成方案文档（方案 §3.5）：一次调用出整份，流式进度。
 * 温度 0.2（比出单 0.4 更不许发挥），3000 tokens，120s 预算。
 * 断了不重来——门面把已写好的章节交回来，「接着写」只补缺的那几节。
 */
class PlanBuilder(
    private val provider: LlmProvider,
    private val prompts: PromptBuilder,
    private val parser: PlanParser,
    private val maxTokens: Int = 3000,
    /** 生成预算（可注入，便于单测） */
    private val timeoutMs: Long = PLAN_TIMEOUT_MS,
) {

    fun build(context: PlanContext, knownSourceIds: Set<String>): Flow<PlanEvent> = flow {
        val messages = prompts.planMessages(context)
        val full = StringBuilder()
        var failure: AppError? = null

        provider.stream(
            ChatRequest(
                messages = messages,
                model = "",
                temperature = 0.2,
                maxTokens = maxTokens,
                stream = true,
                jsonObject = true,
                callTimeoutMs = timeoutMs,
            ),
        ).let { stream ->
            val finished = withTimeoutOrNull(timeoutMs) {
                stream.collect { event ->
                    when (event) {
                        is LlmEvent.Delta -> {
                            full.append(event.text)
                            emit(PlanEvent.Progress(full.length))
                        }

                        is LlmEvent.Done -> Unit
                        is LlmEvent.Failed -> failure = event.error
                    }
                }
                true
            }
            if (finished == null && failure == null) {
                failure = AppError.Timeout(timeoutMs)
            }
        }

        failure?.let { error ->
            emit(PlanEvent.Failed(error, canResume = context.existing.isNotEmpty()))
            return@flow
        }

        when (val parsed = parser.parse(full.toString(), knownSourceIds)) {
            is AppResult.Err -> emit(PlanEvent.Failed(parsed.error, canResume = true))
            is AppResult.Ok -> emit(PlanEvent.Drafted(parsed.value))
        }
    }.flowOn(Dispatchers.IO)
}

sealed interface PlanEvent {
    data class Progress(val receivedChars: Int) : PlanEvent

    data class Drafted(val draft: PlanDraft) : PlanEvent

    /** canResume：已经有章节落地时，断了还能「接着写剩下的」而不是整份重来 */
    data class Failed(val error: AppError, val canResume: Boolean) : PlanEvent
}

/** 一份方案 3~5k token，比提问流慢，但没出单那么慢；120s 是打算让它写完的预算。 */
private const val PLAN_TIMEOUT_MS = 120_000L
