package com.askfirst.research

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取证纪律：token 只发白名单、429/4xx 不重试、连接失败退避、缓存命中不打网络、代理挂了回退直连。
 * 网络用假 fetcher——这些纪律全是纯逻辑，不该靠真网验证。
 */
class GitHubClientTest {

    /** 假网络：按 URL 依次吐预设结果，并记下每次请求的 URL 与 headers。 */
    private class FakeFetcher(
        private val responder: (url: String, headers: Map<String, String>, attempt: Int) -> FetchResult,
    ) : HttpFetcher {
        val calls = mutableListOf<Triple<String, Map<String, String>, Long>>()
        var attempts = 0

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            callTimeoutMs: Int,
        ): FetchResult {
            attempts += 1
            calls += Triple(url, headers, System.currentTimeMillis())
            return responder(url, headers, attempts)
        }
    }

    /** 内存里的 24h 缓存（真实实现落在库里，见 ResearchRepository）。 */
    private class MemoryCache : EvidenceCache {
        val store = mutableMapOf<String, String>()
        var hits = 0
        override suspend fun get(url: String): String? = store[url]?.also { hits += 1 }
        override suspend fun put(url: String, body: String) {
            store[url] = body
        }
    }

    private fun client(
        fetcher: HttpFetcher,
        token: String? = null,
        proxy: String? = null,
        cache: EvidenceCache = EvidenceCache.None,
    ) = GitHubClient(
        fetcher = fetcher,
        cache = cache,
        tokenProvider = { token },
        proxyProvider = { proxy },
        sleeper = { },
    )

    @Test
    fun `匿名时请求头里没有 Authorization`() = runTest {
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 200, body = """{"items":[]}""") }
        val outcome = client(fetcher).searchRepos(listOf("Room FTS5"))
        assertTrue(outcome is FetchOutcome.Ok)
        assertFalse(fetcher.calls.first().second.containsKey("Authorization"))
        assertTrue(fetcher.calls.first().first.contains("api.github.com/search/repositories"))
        assertTrue(fetcher.calls.first().first.contains("sort=stars"))
    }

    @Test
    fun `有 token 时 api 域带 Authorization 且只带一次前缀`() = runTest {
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 200, body = """{"items":[]}""") }
        client(fetcher, token = "ghp_secret").searchRepos(listOf("x"))
        assertEquals("Bearer ghp_secret", fetcher.calls.first().second["Authorization"])
    }

    @Test
    fun `代理域名在白名单里时也带 token 但存文件的域不带`() = runTest {
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 200, body = """{"items":[]}""") }
        // 用户把代理填成了 raw 域（错的配法）：这条路必须不带凭据
        client(fetcher, token = "ghp_secret", proxy = "https://raw.githubusercontent.com")
            .searchRepos(listOf("x"))
        assertFalse(fetcher.calls.first().second.containsKey("Authorization"))

        val fetcher2 = FakeFetcher { _, _, _ -> FetchResult(code = 200, body = """{"items":[]}""") }
        client(fetcher2, token = "ghp_secret", proxy = "https://gh-proxy.example.com")
            .searchRepos(listOf("x"))
        assertEquals("Bearer ghp_secret", fetcher2.calls.first().second["Authorization"])
        assertTrue(fetcher2.calls.first().first.startsWith("https://gh-proxy.example.com/search/repositories"))
    }

    @Test
    fun `429 不重试，一次就返回可读原因`() = runTest {
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 429, body = """{"message":"rate limit"}""") }
        val outcome = client(fetcher).searchIssues(listOf("x"))
        assertTrue(outcome is FetchOutcome.Failed)
        assertEquals(1, fetcher.attempts)
        assertTrue((outcome as FetchOutcome.Failed).reason.contains("限流"))
    }

    @Test
    fun `500 与 401 也不重试`() = runTest {
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 500) }
        client(fetcher).searchRepos(listOf("x"))
        assertEquals(1, fetcher.attempts)

        val fetcher2 = FakeFetcher { _, _, _ -> FetchResult(code = 401) }
        val outcome = client(fetcher2).searchRepos(listOf("x"))
        assertEquals(1, fetcher2.attempts)
        assertTrue((outcome as FetchOutcome.Failed).reason.contains("token"))
    }

    @Test
    fun `连接层失败退避重试两次后放弃`() = runTest {
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 0, failure = FetchFailure.TIMEOUT) }
        val outcome = client(fetcher).searchRepos(listOf("x"))
        assertEquals(3, fetcher.attempts)
        assertTrue(outcome is FetchOutcome.Failed)
        assertTrue((outcome as FetchOutcome.Failed).reason.contains("超时"))
    }

    @Test
    fun `第一次超时第二次成功也算拿到证据`() = runTest {
        val fetcher = FakeFetcher { _, _, attempt ->
            if (attempt == 1) FetchResult(code = 0, failure = FetchFailure.IO)
            else FetchResult(code = 200, body = """{"items":[]}""")
        }
        assertTrue(client(fetcher).searchRepos(listOf("x")) is FetchOutcome.Ok)
        assertEquals(2, fetcher.attempts)
    }

    @Test
    fun `没有 token 时代码检索直接如实失败`() = runTest {
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 200, body = """{"items":[]}""") }
        val outcome = client(fetcher).searchCode(listOf("x"))
        assertTrue(outcome is FetchOutcome.Failed)
        assertTrue((outcome as FetchOutcome.Failed).needsToken)
        assertEquals(0, fetcher.attempts)
    }

    @Test
    fun `缓存命中时不再打网络`() = runTest {
        val cache = MemoryCache()
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 200, body = """{"items":[]}""") }
        val github = client(fetcher, cache = cache)
        github.searchRepos(listOf("cached"))
        github.searchRepos(listOf("cached"))
        assertEquals(1, fetcher.attempts)
        assertEquals(1, cache.hits)
    }

    @Test
    fun `代理连不上时回退直连一次`() = runTest {
        val fetcher = FakeFetcher { url, _, _ ->
            if (url.startsWith("https://bad-proxy.example.com")) {
                FetchResult(code = 0, failure = FetchFailure.DNS)
            } else {
                FetchResult(code = 200, body = """{"items":[]}""")
            }
        }
        val cache = MemoryCache()
        val outcome = client(fetcher, proxy = "https://bad-proxy.example.com", cache = cache)
            .searchRepos(listOf("x"))
        assertTrue(outcome is FetchOutcome.Ok)
        assertTrue("直连的那次必须打在 api.github.com 上", fetcher.calls.any { it.first.contains("api.github.com") })
        assertTrue("直连成功后要写缓存", cache.store.isNotEmpty())
    }

    @Test
    fun `代理返回 500 时不许回退直连（服务端回话了就别换条路再打一次）`() = runTest {
        val fetcher = FakeFetcher { url, _, _ ->
            if (url.startsWith("https://flaky-proxy.example.com")) {
                FetchResult(code = 500)
            } else {
                FetchResult(code = 401) // 直连这条路会报「token 不认」，绝不能让它盖掉真实的 500
            }
        }
        val outcome = client(fetcher, proxy = "https://flaky-proxy.example.com").searchIssues(listOf("x"))
        assertTrue(outcome is FetchOutcome.Failed)
        assertTrue((outcome as FetchOutcome.Failed).reason.contains("500"))
        assertEquals("只打了代理一次，不该再打直连", 1, fetcher.attempts)
    }

    @Test
    fun `rate_limit 只打一次（测试连通按钮不该退避三连）`() = runTest {
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 0, failure = FetchFailure.TIMEOUT) }
        assertTrue(client(fetcher).rateLimit() is FetchOutcome.Failed)
        assertEquals(1, fetcher.attempts)
    }

    @Test
    fun `关键词为空不发请求`() = runTest {
        val fetcher = FakeFetcher { _, _, _ -> FetchResult(code = 200, body = "{}") }
        assertTrue(client(fetcher).searchRepos(emptyList()) is FetchOutcome.Failed)
        assertEquals(0, fetcher.attempts)
    }
}
