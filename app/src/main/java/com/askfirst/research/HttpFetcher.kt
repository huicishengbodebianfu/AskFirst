package com.askfirst.research

/**
 * 一次 GET 的结果。**网络层只回这三种状态**：拿到响应（含 4xx/5xx）、连接层失败、被取消。
 * 把 4xx/5xx 也算「拿到响应」很重要——429/500 不该被当成超时去重试（docs/26 §3.6）。
 */
data class FetchResult(
    val code: Int,
    val body: String = "",
    val elapsedMs: Long = 0,
    val failure: FetchFailure? = null,
) {
    val isHttpOk: Boolean get() = failure == null && code in 200..299
}

/** 连接层失败（可以退避重试的那一类）。4xx/5xx 不在这里。 */
enum class FetchFailure(val label: String) {
    TIMEOUT("超时"),
    DNS("域名解析失败"),
    REFUSED("连接被拒"),
    IO("连接中断"),
    ;

    companion object {
        fun fromThrowable(t: Throwable): FetchFailure = when (t) {
            is java.net.SocketTimeoutException -> TIMEOUT
            is java.net.UnknownHostException -> DNS
            is java.net.ConnectException -> REFUSED
            is java.io.InterruptedIOException -> TIMEOUT
            else -> IO
        }
    }
}

/**
 * 网络取数的**唯一**出口（纯接口）。真实实现见 `remote/OkHttpFetcher`。
 * 抽成接口是为了让 GitHub 的那些纪律（超时/退避/白名单/部分成功）能在 JVM 单测里跑，
 * 不用真网、不用 Robolectric。
 */
interface HttpFetcher {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 25_000,
        callTimeoutMs: Int = 30_000,
    ): FetchResult
}

