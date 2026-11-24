package com.askfirst.domain.sheet

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.prompt.PromptBuilder
import com.askfirst.domain.prompt.SheetContext
import com.askfirst.remote.ChatRequest
import com.askfirst.remote.LlmEvent
import com.askfirst.remote.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 出单：一次调用生成整份五栏（docs/04 §3），失败可本地降级（facade.fallbackSheet）。
 * 出单温度 0.4：要比提问更稳，不许"发挥"。
 */
class SheetBuilder(
    private val provider: LlmProvider,
    private val prompts: PromptBuilder,
    private val parser: SheetParser,
    private val assembler: SheetAssembler,
    private val maxTokens: Int = 2000,
    /** 出单预算（可注入，便于单测） */
    private val timeoutMs: Long = SHEET_TIMEOUT_MS,
) {

    fun build(context: SheetContext, existing: Sheet): Flow<SheetEvent> = flow {
        val messages = prompts.sheetMessages(context)
        val full = StringBuilder()
        var failure: AppError? = null

        provider.stream(
            ChatRequest(
                messages = messages,
                model = "",
                temperature = 0.4,
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
                            emit(SheetEvent.Progress(full.length))
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
            emit(SheetEvent.Failed(error, canFallback = true))
            return@flow
        }

        when (val parsed = parser.parse(full.toString())) {
            is AppResult.Err -> emit(SheetEvent.Failed(parsed.error, canFallback = true))
            is AppResult.Ok -> {
                val merged = assembler.merge(existing, parsed.value, context.turns)
                emit(SheetEvent.Built(merged, parsed.value.summary.trim()))
            }
        }
    }.flowOn(Dispatchers.IO)
}

sealed interface SheetEvent {
    data class Progress(val receivedChars: Int) : SheetEvent
    data class Built(val sheet: Sheet, val summary: String) : SheetEvent
    data class Failed(val error: AppError, val canFallback: Boolean) : SheetEvent
}

/** 出单比提问重（一次要吐 1~2k token），但也不能无限等。 */
private const val SHEET_TIMEOUT_MS = 100_000L

