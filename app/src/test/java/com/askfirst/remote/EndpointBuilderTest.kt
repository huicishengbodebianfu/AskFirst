package com.askfirst.remote

import com.askfirst.core.AppError
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL 拼接与错误映射是纯函数，放在 JVM 单测里跑（S2 验收）。 */
class EndpointBuilderTest {

    @Test
    fun `正常 base 以 v1 结尾时拼一次 chat completions`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointBuilder.chatCompletions("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointBuilder.chatCompletions("https://api.deepseek.com/v1/"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointBuilder.chatCompletions("  https://api.deepseek.com/v1///  "),
        )
    }

    @Test
    fun `用户粘了完整地址时不许拼成双份`() {
        assertEquals(
            "https://gw.example.com/v1/chat/completions",
            EndpointBuilder.chatCompletions("https://gw.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `展示用 URL 不许带出凭据`() {
        val safe = EndpointBuilder.safeForDisplay("https://x.com/v1?api_key=sk-123456&model=a")
        assertTrue(safe.contains("api_key=***"))
        assertTrue(!safe.contains("sk-123456"))
    }

    // ---- docs/28 P0-1：漏写 scheme 曾经把 App 崩掉 ----

    @Test
    fun `漏写 scheme 的 base 自动补 https 并且 OkHttp 认它`() {
        val url = EndpointBuilder.chatCompletions("api.deepseek.com/v1")
        assertEquals("https://api.deepseek.com/v1/chat/completions", url)
        // 这条就是当初崩掉的那一步：Request.Builder().url() 内部走的就是 toHttpUrl，
        // 修复前 "api.deepseek.com/v1/chat/completions" 在这里抛 IllegalArgumentException。
        assertEquals("api.deepseek.com", url.toHttpUrl().host)
    }

    @Test
    fun `带空白和多余斜杠也不会建出坏地址`() {
        val url = EndpointBuilder.chatCompletions("  api.deepseek.com/v1///  ")
        assertEquals("https://api.deepseek.com/v1/chat/completions", url)
        assertEquals("api.deepseek.com", url.toHttpUrl().host)
    }

    @Test
    fun `非 http 的 scheme 不瞎猜 交给上层报配置错`() {
        assertEquals("ftp://x.com/v1", EndpointBuilder.normalizeBaseUrl("ftp://x.com/v1"))
        assertTrue(!EndpointBuilder.isHttpUrl("ftp://x.com/v1"))
        assertTrue(EndpointBuilder.isHttpUrl("api.deepseek.com/v1"))
        assertTrue(EndpointBuilder.isHttpUrl("http://127.0.0.1:8080/v1"))
    }

    // ---- 设置页「拉取模型列表」用的 /models 端点 ----

    @Test
    fun `models 端点按 base 拼一次`() {
        assertEquals("https://x.com/v1/models", EndpointBuilder.models("https://x.com/v1"))
        assertEquals("https://x.com/v1/models", EndpointBuilder.models("https://x.com/v1/"))
        assertEquals("https://x.com/v1/models", EndpointBuilder.models("  https://x.com/v1///  "))
    }

    @Test
    fun `models 端点要补 scheme 也要摘掉 chat 后缀`() {
        // 漏写 https:// 是最常见的填错法，这里同样补全（否则又要在 OkHttp 里崩）
        assertEquals("https://api.deepseek.com/v1/models", EndpointBuilder.models("api.deepseek.com/v1"))
        // 用户把完整对话地址粘进来时，不能拼成 …/chat/completions/models
        assertEquals("https://x.com/v1/models", EndpointBuilder.models("https://x.com/v1/chat/completions"))
        // 已经是 /models 就别重复拼
        assertEquals("https://x.com/v1/models", EndpointBuilder.models("https://x.com/v1/models"))
    }

    @Test
    fun `401 映射成 Key 错误 429 映射成限流`() {
        val auth = HttpErrors.fromStatus(401, """{"error":{"message":"Invalid API key"}}""")
        assertTrue(auth is AppError.Auth)
        assertTrue(auth.message!!.contains("Invalid API key"))

        val limited = HttpErrors.fromStatus(429, "", retryAfterSec = 12)
        assertTrue(limited is AppError.RateLimited)
        assertTrue(limited.message!!.contains("12"))

        val server = HttpErrors.fromStatus(502, "<html>bad gateway</html>")
        assertTrue(server is AppError.Server)
    }

    @Test
    fun `服务端返回的不是 JSON 时错误文案不许崩`() {
        assertEquals(null, HttpErrors.serverMessage("<html>oops</html>"))
        assertEquals("boom", HttpErrors.serverMessage("""{"message":"boom"}"""))
    }

    @Test
    fun `404 要说成 model 名可能填错 而不是通用运维文案`() {
        val notFound = HttpErrors.fromStatus(404, "")
        assertTrue("404 是用户能自己修的，别套通用文案", notFound is com.askfirst.core.AppError.Config)
        assertTrue(notFound.message!!.contains("拉取模型列表"))
    }
}