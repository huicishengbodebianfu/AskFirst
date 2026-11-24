package com.askfirst.remote

import com.askfirst.core.AppError
import com.askfirst.core.AppJson
import com.askfirst.core.AppResult
import com.askfirst.core.Logx
import com.askfirst.data.prefs.SettingsStore
import com.askfirst.data.secret.SecretStore
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI 兼容通道（DeepSeek / Kimi / 智谱 / 自建网关都填这套三元组）。
 *
 * 超时的正确做法：**用 OkHttp 的 socket 级超时**（callTimeout / readTimeout）。
 * 协程的 withTimeout 打断不了阻塞中的 socket 读——试过 `Job.invokeOnCompletion { call.cancel() }`，
 * 但回调要等协程体自己结束才会触发，对"服务器一直不回"的场景无效（真机验证过）。
 */
class OpenAiCompatibleProvider(
    private val client: OkHttpClient,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val log: Logx,
) : LlmProvider {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun config(): AppResult<LlmConfig> {
        val settings = settings.current()
        val key = secrets.llmKey()
            ?: return AppResult.Err(AppError.Config("还没填 API Key，去设置里填一下"))
        if (settings.baseUrl.isBlank()) {
            return AppResult.Err(AppError.Config("还没填 base_url（例如 https://api.deepseek.com/v1）"))
        }
        if (settings.model.isBlank()) {
            return AppResult.Err(AppError.Config("还没填 model 名（例如 deepseek-chat）"))
        }
        // 漏写 https:// 是最常见的填错法：这里补全；补不出来的（ftp:// 等）当配置错报人话，
        // 绝不让它变成 OkHttp 的 IllegalArgumentException（那会把 App 崩掉，docs/28 P0-1）
        val base = EndpointBuilder.normalizeBaseUrl(settings.baseUrl)
        if (!EndpointBuilder.isHttpUrl(base)) {
            return AppResult.Err(
                AppError.Config(
                    "base_url 得是 http(s) 开头的地址，例如 https://api.deepseek.com/v1" +
                        "（你现在填的是「${settings.baseUrl}」）",
                ),
            )
        }
        return AppResult.Ok(
            LlmConfig(
                baseUrl = base,
                apiKey = key,
                model = settings.model,
                temperature = settings.temperature,
            ),
        )
    }

    override suspend fun ping(): AppResult<String> = withContext(Dispatchers.IO) {
        val config = when (val result = config()) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> return@withContext result
        }
        val startedAt = System.nanoTime()
        val pingClient = clientFor(PING_TIMEOUT_MS)
        val call: Call = try {
            pingClient.newCall(
                Request.Builder()
                    .url(EndpointBuilder.chatCompletions(config.baseUrl))
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(pingBody(config.model))
                    .build(),
            )
        } catch (badUrl: IllegalArgumentException) {
            // 地址还是建不出合法 URL（scheme 不对/有非法字符）：报配置错，别崩
            return@withContext AppResult.Err(
                AppError.Config(
                    "base_url 这个地址用不了（${badUrl.message ?: "格式不对"}）。" +
                        "检查一下是不是少了 https:// 或者中间有空格。",
                ),
            )
        }
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                if (response.isSuccessful) {
                    log.d(TAG, "ping ok ${response.code} in ${elapsedMs}ms")
                    AppResult.Ok("连接正常 · ${config.model} · ${elapsedMs}ms")
                } else {
                    val retryAfter = response.header("Retry-After")?.toIntOrNull()
                    log.w(TAG, "ping ${response.code}: ${text.take(200)}")
                    AppResult.Err(HttpErrors.fromStatus(response.code, text, retryAfter))
                }
            }
        } catch (timeout: SocketTimeoutException) {
            AppResult.Err(AppError.Timeout(PING_TIMEOUT_MS))
        } catch (timeout: InterruptedIOException) {
            AppResult.Err(AppError.Timeout(PING_TIMEOUT_MS))
        } catch (io: IOException) {
            AppResult.Err(AppError.Network(io.message ?: io::class.java.simpleName, io))
        } catch (t: Throwable) {
            log.e(TAG, "ping 意外失败", t)
            AppResult.Err(AppError.Network(t.message ?: t::class.java.simpleName, t))
        } finally {
            call.cancel()
        }
    }

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val config = when (val result = config()) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> {
                emit(LlmEvent.Failed(result.error))
                return@flow
            }
        }
        val streamClient = clientFor(request.callTimeoutMs)
        val call: Call = try {
            streamClient.newCall(
                Request.Builder()
                    .url(EndpointBuilder.chatCompletions(config.baseUrl))
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .post(buildStreamBody(config, request).toRequestBody(jsonMedia))
                    .build(),
            )
        } catch (badUrl: IllegalArgumentException) {
            emit(
                LlmEvent.Failed(
                    AppError.Config(
                        "base_url 这个地址用不了（${badUrl.message ?: "格式不对"}）。" +
                            "检查一下是不是少了 https:// 或者中间有空格。",
                    ),
                ),
            )
            return@flow
        }
        val parser = SseParser()
        val full = StringBuilder()
        var failure: AppError? = null
        var finishReason: String? = null
        var stoppedByDone = false

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    failure = HttpErrors.fromStatus(
                        response.code,
                        body,
                        response.header("Retry-After")?.toIntOrNull(),
                    )
                    return@use
                }
                val source = response.body?.source()
                if (source == null) {
                    failure = AppError.Network("响应没有正文（body 为空）")
                    return@use
                }
                var atEof = false
                while (!atEof) {
                    currentCoroutineContext().ensureActive()
                    val read = source.readUtf8Line()
                    if (read == null) atEof = true
                    // EOF 时补喂一个空行：SseParser 只在空行 dispatch，
                    // 服务端/代理在最后一个 data 行之后直接断流（少了结尾空行）时，
                    // 最后一帧（finish_reason，json_object 请求时还有 JSON 尾部）会整段丢掉（docs/28 P2-1）
                    val frame = parser.feed(read ?: "") ?: continue
                    when (val parsed = ChatChunkParser.parse(frame)) {
                        is ChatChunkParser.Result.Delta -> {
                            full.append(parsed.text)
                            emit(LlmEvent.Delta(parsed.text))
                        }

                        is ChatChunkParser.Result.Finish -> {
                            finishReason = parsed.reason
                            if (frame.isDoneSentinel) {
                                stoppedByDone = true
                                break
                            }
                        }

                        is ChatChunkParser.Result.Failure -> {
                            failure = parsed.error
                            break
                        }

                        ChatChunkParser.Result.Ignored -> Unit
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (timeout: SocketTimeoutException) {
            failure = AppError.Timeout(request.callTimeoutMs)
        } catch (timeout: InterruptedIOException) {
            failure = AppError.Timeout(request.callTimeoutMs)
        } catch (io: IOException) {
            failure = AppError.Network(io.message ?: io::class.java.simpleName, io)
        } catch (t: Throwable) {
            log.e(STREAM_TAG, "流式请求异常", t)
            failure = AppError.Network(t.message ?: t::class.java.simpleName, t)
        } finally {
            call.cancel()
        }

        log.d(
            STREAM_TAG,
            "流结束：${full.length} 字，reason=$finishReason，done=$stoppedByDone，failure=${failure?.message}",
        )
        val error = failure
        if (error != null) {
            emit(LlmEvent.Failed(error))
        } else {
            emit(LlmEvent.Done(full.toString(), finishReason))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 拉模型列表（设置页「拉取模型列表 / 换模型」）。
     * 只校验 base_url + key：**不要求 model 已填**，否则"还没选模型"的人就没法拉列表了。
     */
    override suspend fun listModels(): AppResult<List<String>> = withContext(Dispatchers.IO) {
        val settings = settings.current()
        val key = secrets.llmKey()
            ?: return@withContext AppResult.Err(AppError.Config("还没填 API Key，去设置里填一下"))
        if (settings.baseUrl.isBlank()) {
            return@withContext AppResult.Err(
                AppError.Config("还没填 base_url（例如 https://api.deepseek.com/v1）"),
            )
        }
        val base = EndpointBuilder.normalizeBaseUrl(settings.baseUrl)
        if (!EndpointBuilder.isHttpUrl(base)) {
            return@withContext AppResult.Err(
                AppError.Config(
                    "base_url 得是 http(s) 开头的地址，例如 https://api.deepseek.com/v1" +
                        "（你现在填的是「${settings.baseUrl}」）",
                ),
            )
        }
        val modelClient = clientFor(PING_TIMEOUT_MS)
        val call = try {
            modelClient.newCall(
                Request.Builder()
                    .url(EndpointBuilder.models(base))
                    .header("Authorization", "Bearer $key")
                    .header("Accept", "application/json")
                    .get()
                    .build(),
            )
        } catch (badUrl: IllegalArgumentException) {
            return@withContext AppResult.Err(
                AppError.Config(
                    "base_url 这个地址用不了（${badUrl.message ?: "格式不对"}）。" +
                        "检查一下是不是少了 https:// 或者中间有空格。",
                ),
            )
        }
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    log.w(MODELS_TAG, "models ${response.code}: ${text.take(200)}")
                    return@withContext AppResult.Err(
                        HttpErrors.fromStatus(
                            response.code,
                            text,
                            response.header("Retry-After")?.toIntOrNull(),
                        ),
                    )
                }
                ModelList.parse(text)
            }
        } catch (timeout: SocketTimeoutException) {
            AppResult.Err(AppError.Timeout(PING_TIMEOUT_MS))
        } catch (timeout: InterruptedIOException) {
            AppResult.Err(AppError.Timeout(PING_TIMEOUT_MS))
        } catch (io: IOException) {
            AppResult.Err(AppError.Network(io.message ?: io::class.java.simpleName, io))
        } catch (t: Throwable) {
            log.e(MODELS_TAG, "拉取模型列表失败", t)
            AppResult.Err(AppError.Network(t.message ?: t::class.java.simpleName, t))
        } finally {
            call.cancel()
        }
    }

    /** timeoutMs<=0：用全局客户端；否则克隆一个带 callTimeout 的。 */
    private fun clientFor(timeoutMs: Long): OkHttpClient {
        if (timeoutMs <= 0) return client
        val readTimeout = if (timeoutMs > READ_TIMEOUT_MS) timeoutMs else READ_TIMEOUT_MS
        return client.newBuilder()
            .callTimeout(timeoutMs, MILLISECONDS)
            .readTimeout(readTimeout, MILLISECONDS)
            .build()
    }

    private fun pingBody(model: String): okhttp3.RequestBody {
        val json = buildString {
            append("{\"model\":")
            append(AppJson.encodeToString(String.serializer(), model))
            append(",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]")
            append(",\"max_tokens\":1,\"stream\":false}")
        }
        return json.toRequestBody(jsonMedia)
    }

    private fun buildStreamBody(config: LlmConfig, request: ChatRequest): String = buildString {
        append("{\"model\":")
        append(quote(request.model.ifBlank { config.model }))
        append(",\"messages\":[")
        request.messages.forEachIndexed { index, message ->
            if (index > 0) append(',')
            append("{\"role\":")
            append(quote(message.role))
            append(",\"content\":")
            append(quote(message.content))
            append('}')
        }
        append("],\"temperature\":")
        append(request.temperature)
        append(",\"max_tokens\":")
        append(request.maxTokens)
        append(",\"stream\":true")
        if (request.jsonObject) {
            append(",\"response_format\":{\"type\":\"json_object\"}")
        }
        append('}')
    }

    private fun quote(value: String): String = AppJson.encodeToString(String.serializer(), value)

    companion object {
        private const val TAG = "LlmPing"
        private const val STREAM_TAG = "LlmStream"
        private const val MODELS_TAG = "LlmModels"
        const val PING_TIMEOUT_MS = 20_000L
        private const val READ_TIMEOUT_MS = 90_000L
    }
}