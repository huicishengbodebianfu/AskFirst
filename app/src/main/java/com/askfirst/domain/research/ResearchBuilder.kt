package com.askfirst.domain.research

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.Verdict
import com.askfirst.domain.prompt.PromptBuilder
import com.askfirst.domain.prompt.ResearchQuestionContext
import com.askfirst.remote.ChatRequest
import com.askfirst.remote.LlmEvent
import com.askfirst.remote.LlmProvider
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 立题：让模型把这场问答变成 3~6 个可检索问题（方案 §3.6 第 1 步）。
 * 失败/超时都返回 Err，由门面决定要不要用 [QuestionDrafting.fallback] 兜底——
 * 兜底不在这里做，这样「模型出的题」和「规则凑的题」在数据上分得清。
 */
class ResearchQuestionBuilder(
    private val provider: LlmProvider,
    private val prompts: PromptBuilder,
    private val maxTokens: Int = 1200,
    private val timeoutMs: Long = 60_000,
) {

    suspend fun draft(context: ResearchQuestionContext): AppResult<List<ResearchQuestion>> {
        val messages = prompts.researchQuestionMessages(context)
        val full = StringBuilder()
        var failure: AppError? = null

        val finished = withTimeoutOrNull(timeoutMs) {
            provider.stream(
                ChatRequest(
                    messages = messages,
                    model = "",
                    temperature = 0.3,
                    maxTokens = maxTokens,
                    stream = true,
                    jsonObject = true,
                    callTimeoutMs = timeoutMs,
                ),
            ).collect { event ->
                when (event) {
                    is LlmEvent.Delta -> full.append(event.text)
                    is LlmEvent.Done -> Unit
                    is LlmEvent.Failed -> failure = event.error
                }
            }
            true
        }
        failure?.let { return AppResult.Err(it) }
        if (finished == null) return AppResult.Err(AppError.Timeout(timeoutMs))
        if (full.isBlank()) return AppResult.Err(AppError.BadJson("", "模型一个字都没回"))
        return QuestionDrafting.parse(full.toString())
    }
}

/**
 * 对照：拿证据撞方案，产出四判定（方案 §3.6 第 3 步）。
 * 温度 0.2——这一步比立题更不许发挥；token 压在 2000（判定是短文本）。
 */
class ResearchBuilder(
    private val provider: LlmProvider,
    private val prompts: PromptBuilder,
    private val maxTokens: Int = 2000,
    private val timeoutMs: Long = 90_000,
) : VerdictJudge {

    override suspend fun judge(input: JudgeInput): AppResult<List<Verdict>> {
        val messages = prompts.researchVerdictMessages(input)
        val full = StringBuilder()
        var failure: AppError? = null

        val finished = withTimeoutOrNull(timeoutMs) {
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
            ).collect { event ->
                when (event) {
                    is LlmEvent.Delta -> full.append(event.text)
                    is LlmEvent.Done -> Unit
                    is LlmEvent.Failed -> failure = event.error
                }
            }
            true
        }
        failure?.let { return AppResult.Err(it) }
        if (finished == null) return AppResult.Err(AppError.Timeout(timeoutMs))
        if (full.isBlank()) return AppResult.Err(AppError.BadJson("", "模型一个字都没回"))
        return VerdictParse.parse(full.toString(), input.questions, input.evidence)
    }
}
