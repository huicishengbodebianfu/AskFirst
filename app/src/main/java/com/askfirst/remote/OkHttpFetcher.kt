package com.askfirst.remote

import com.askfirst.research.FetchFailure
import com.askfirst.research.FetchResult
import com.askfirst.research.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * [HttpFetcher] 的真实实现（M6 取证层）。
 *
 * 和模型通道分开一个 client：模型的读超时是 90s（流式），
 * 而 GitHub 的单请求预算短得多（connect 12s / read 25s / call 30s），
 * 两套超时混在一起会让「60 秒出一份报告」变成「60 秒卡在一个请求上」。
 */
class OkHttpFetcher(
    private val baseClient: OkHttpClient = HttpFactory.client(),
) : HttpFetcher {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        callTimeoutMs: Int,
    ): FetchResult = withContext(Dispatchers.IO) {
        val client = baseClient.newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        val request = try {
            Request.Builder()
                .url(url)
                .get()
                .apply { headers.forEach { (key, value) -> header(key, value) } }
                .build()
        } catch (t: IllegalArgumentException) {
            // 地址填错（代理 base 少了 https:// 之类）不该炸整个调研
            return@withContext FetchResult(code = 0, failure = FetchFailure.DNS)
        }

        val startedAt = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                FetchResult(
                    code = response.code,
                    body = body,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            }
        } catch (t: Throwable) {
            FetchResult(
                code = 0,
                elapsedMs = System.currentTimeMillis() - startedAt,
                failure = FetchFailure.fromThrowable(t),
            )
        }
    }
}
