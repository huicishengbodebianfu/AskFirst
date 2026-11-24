package com.askfirst.remote

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis

/**
 * 模型测速（设置页「测一下这个模型有多快」）。
 * 用一次 64 token 的流式请求量三件事：首字延迟、总耗时、输出速度。
 * 30 秒内没结果就按超时算——这比让用户盯着转圈强。
 */
class SpeedTester(private val provider: LlmProvider, private val timeoutMs: Long = TIMEOUT_MS) {

    suspend fun measure(): AppResult<SpeedResult> {
        var firstMs = -1L
        var chars = 0
        var failure: AppError? = null
        val startedAt = System.nanoTime()
        val elapsed = { (System.nanoTime() - startedAt) / 1_000_000 }

        try {
            withTimeout(timeoutMs) {
                provider.stream(
                    ChatRequest(
                        messages = listOf(ChatMessage.user("用一句话说明缓存的作用，不要超过 30 字。")),
                        model = "",
                        temperature = 0.2,
                        maxTokens = 64,
                        stream = true,
                        jsonObject = false,
                        callTimeoutMs = timeoutMs,
                    ),
                ).collect { event ->
                    when (event) {
                        is LlmEvent.Delta -> {
                            if (firstMs < 0) firstMs = elapsed()
                            chars += event.text.length
                        }

                        is LlmEvent.Done -> Unit
                        is LlmEvent.Failed -> failure = event.error
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            failure = AppError.Timeout(timeoutMs)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            failure = AppError.Network(t.message ?: t::class.java.simpleName, t)
        }

        failure?.let { return AppResult.Err(it) }
        if (chars == 0) {
            return AppResult.Err(AppError.BadJson("", "模型没吐出任何正文（可能只输出了思考过程）"))
        }
        val total = elapsed()
        return AppResult.Ok(
            SpeedResult(
                firstTokenMs = firstMs.coerceAtLeast(0),
                totalMs = total,
                chars = chars,
            ),
        )
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
    }
}

data class SpeedResult(val firstTokenMs: Long, val totalMs: Long, val chars: Int) {
    val charsPerSecond: Double get() = if (totalMs <= 0) 0.0 else chars * 1000.0 / totalMs

    /** 给用户看的一句话（不出现内部术语）。 */
    fun readable(): String =
        "首字 ${firstTokenMs}ms · 总共 ${totalMs}ms · 约 ${"%.1f".format(charsPerSecond)} 字/秒（${chars} 字）"
}
