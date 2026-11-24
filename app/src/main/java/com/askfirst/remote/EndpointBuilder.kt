package com.askfirst.remote

/**
 * OpenAI 兼容端点的 URL 拼装（纯函数，可单测）。
 * 约定：用户填到 `/v1` 为止，例如 `https://api.deepseek.com/v1`。
 * 如果用户直接把完整地址粘进来（带 `/chat/completions`），也不许拼出双份。
 *
 * 另外必须**兜住漏写 scheme** 的情况：`api.deepseek.com/v1` 是真人最常填的错法，
 * OkHttp 的 `Request.Builder().url()` 对这种输入抛 IllegalArgumentException，
 * 而它是在建 Request 时抛的、在 try 之外——曾经直接把 App 崩掉（docs/28 P0-1）。
 * 所以在这一层就补全，把"崩"变成"能用"；真补不了的（ftp:// 之类）交给 config() 报人话。
 */
object EndpointBuilder {

    /** `http://` / `https://` / 其它显式 scheme 的判定。 */
    private val EXPLICIT_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /**
     * 补全/规整 base_url：
     * - 去空白、去尾部 `/`
     * - 没有 scheme 的补 `https://`（`api.deepseek.com/v1` → `https://api.deepseek.com/v1`）
     * - 已有显式 scheme 的原样保留（哪怕是 `ftp://`——让上层报"地址不对"，不猜）
     */
    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        return if (EXPLICIT_SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    }

    /** 规整后是不是一个 http(s) 地址——填错了要在设置页就告诉人，别等他点"测试连通"。 */
    fun isHttpUrl(baseUrl: String): Boolean {
        val lower = normalizeBaseUrl(baseUrl).lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    fun chatCompletions(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
        return when {
            base.endsWith("/chat/completions") -> base
            else -> "$base/chat/completions"
        }
    }

    /**
     * 模型列表端点。用户可能把完整的 `/chat/completions` 粘进 base_url，
     * 这里先把它摘掉再拼 `/models`，避免出现 `…/chat/completions/models`。
     */
    fun models(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
            .removeSuffix("/chat/completions")
            .trimEnd('/')
        return when {
            base.endsWith("/models") -> base
            else -> "$base/models"
        }
    }

    /** 展示用：把 host 里可能带的凭据抹掉（用户可能把 token 塞进 URL）。 */
    fun safeForDisplay(url: String): String =
        url.replace(Regex("(api[_-]?key|token|key)=[^&]+", RegexOption.IGNORE_CASE), "$1=***")
}
