package com.askfirst.research

import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLEncoder

/**
 * 取证层：GitHub 检索 + README 原文抓取（方案 §3.6.1 的调用链）。
 *
 * 这一层只管**怎么问、怎么失败、什么时候不算数**，不管 JSON 长什么样（解析在 EvidenceParse）。
 *
 * 纪律（docs/26 §3.6，逐条对应方案 §3.6.3）：
 * 1. 单请求 connect 12s / read 25s / call 30s（交给 [HttpFetcher] 的默认值）；
 * 2. 只对**连接层**失败退避重试 2 次（0.5s、1.5s）；4xx/5xx 一次都不重试——4xx 重试只会烧配额；
 * 3. 部分成功是正常态：每个方法各自返回失败原因，由管道记进报告；
 * 4. token 只发白名单 host（`api.github.com` 或用户自己填的代理域），
 *    `raw.githubusercontent.com` 一律不带；
 * 5. 代理失败自动回退直连一次（网络状况本身在变）；
 * 6. 同一 URL 24h 内走缓存，不重复打网络（匿名 Search 限流 10 次/分钟）。
 */
class GitHubClient(
    private val fetcher: HttpFetcher,
    private val cache: EvidenceCache = EvidenceCache.None,
    /** 现取：设置页「清除 token」之后立刻生效（不缓存明文） */
    private val tokenProvider: suspend () -> String? = { null },
    /** 代理/镜像 base，可空；空 = 直连 */
    private val proxyProvider: suspend () -> String? = { null },
    /** 退避用的等待（单测注入空实现，免得跑测试真等 2 秒） */
    private val sleeper: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val perPage: Int = 8,
    private val codePerPage: Int = 5,
) {

    /** 有没有 PAT（决定 code search 能不能跑、报告顶部写匿名还是已认证）。 */
    suspend fun hasToken(): Boolean = !tokenProvider().isNullOrBlank()

    suspend fun searchRepos(keywords: List<String>): FetchOutcome {
        val query = keywords.joinToString(" ").trim()
        if (query.isBlank()) return FetchOutcome.Failed("没有可检索的关键词", needsToken = false)
        val url = "${apiBase()}/search/repositories?q=${encode(query)}&sort=stars&order=desc&per_page=$perPage"
        return call(url)
    }

    suspend fun searchIssues(keywords: List<String>): FetchOutcome {
        val query = keywords.joinToString(" ").trim()
        if (query.isBlank()) return FetchOutcome.Failed("没有可检索的关键词", needsToken = false)
        val q = encode("$query in:title,body type:issue")
        val url = "${apiBase()}/search/issues?q=$q&sort=created&order=desc&per_page=$perPage"
        return call(url)
    }

    /**
     * code search 需要登录（GitHub 的硬规定）。没 token 时**如实返回失败**，
     * 由管道记一行「代码检索需要 token」——不假装查过（静默失败最害人）。
     */
    suspend fun searchCode(keywords: List<String>): FetchOutcome {
        val query = keywords.joinToString(" ").trim()
        if (query.isBlank()) return FetchOutcome.Failed("没有可检索的关键词", needsToken = false)
        if (!hasToken()) return FetchOutcome.Failed("代码检索需要先在设置里填 GitHub token", needsToken = true)
        val url = "${apiBase()}/search/code?q=${encode(query)}&per_page=$codePerPage"
        return call(url)
    }

    /** README 原文。走 api 拿 base64，而不是 raw 域——这样 token 只出现在一个白名单 host 上。 */
    suspend fun readme(fullName: String): FetchOutcome {
        if (fullName.isBlank()) return FetchOutcome.Failed("仓库名是空的", needsToken = false)
        return call("${apiBase()}/repos/$fullName/readme")
    }

    /**
     * 「测试检索」按钮用：能拿到额度就说明 token（或匿名）是通的。
     *
     * **必须绕过 24h 缓存**：这是唯一一个用来证明"现在能连通"的调用，命中缓存就等于
     * 断网也能显示"能查"，按钮在说谎（docs/28 P2-12）。
     */
    suspend fun rateLimit(): FetchOutcome =
        call("${apiBase()}/rate_limit", allowRetry = false, useCache = false)

    // --------------------------------------------------------------- internals

    private suspend fun apiBase(): String {
        val proxy = proxyProvider()?.trim()?.trimEnd('/').orEmpty()
        return proxy.ifBlank { DEFAULT_API_BASE }
    }

    private suspend fun call(
        url: String,
        allowRetry: Boolean = true,
        useCache: Boolean = true,
    ): FetchOutcome {
        if (useCache) cache.get(url)?.let { cached -> return FetchOutcome.Ok(cached) }

        val headers = headersFor(url)
        val proxy = proxyProvider()?.trim().orEmpty()
        val proxyHost = if (proxy.isBlank()) null else hostOf(proxy)

        // 1) 先按当前配置打（有代理就先走代理）
        var result = attempt(url, headers, allowRetry)
        // 2) 代理失败 → 回退直连一次（配置错/镜像挂了时别让用户干等）
        if (result is FetchOutcome.Failed && result.connection && proxyHost != null) {
            val proxyReason = result.reason
            val directUrl = url.replace("${proxy.trimEnd('/')}", DEFAULT_API_BASE, ignoreCase = false)
            if (directUrl != url) {
                val direct = attempt(directUrl, headersFor(directUrl), allowRetry)
                result = when (direct) {
                    is FetchOutcome.Ok -> {
                        if (useCache) cache.put(directUrl, direct.body)
                        direct
                    }
                    // 两条路都不通就把两边的原因都写上：只说「token 不认」会让人以为是自己填错了凭证
                    is FetchOutcome.Failed -> FetchOutcome.Failed(
                        "代理连不上（$proxyReason）；直连也不行（${direct.reason}）",
                        connection = true,
                    )
                }
            }
        }
        if (useCache && result is FetchOutcome.Ok) cache.put(url, result.body)
        return result
    }

    private suspend fun attempt(url: String, headers: Map<String, String>, allowRetry: Boolean): FetchOutcome {
        val maxAttempts = if (allowRetry) 3 else 1
        var lastFailure: FetchFailure? = null
        var attemptNo = 0
        while (attemptNo < maxAttempts) {
            attemptNo += 1
            val result = fetcher.get(url, headers)
            when {
                result.failure != null -> {
                    lastFailure = result.failure
                    if (attemptNo < maxAttempts) sleeper(BACKOFF[attemptNo - 1])
                }

                result.isHttpOk -> return FetchOutcome.Ok(result.body)

                result.code == 429 -> return FetchOutcome.Failed(
                    "GitHub 限流了（匿名每小时只有 60 次，Search 每分钟 10 次）",
                    needsToken = false,
                )

                result.code == 401 || result.code == 403 -> return FetchOutcome.Failed(
                    "GitHub 不认这个 token（或权限不够）",
                    needsToken = false,
                )

                result.code == 404 -> return FetchOutcome.Failed("这个仓库没有 README", needsToken = false)

                result.code == 422 -> return FetchOutcome.Failed("检索词 GitHub 不认，换个说法", needsToken = false)

                result.code in 500..599 -> return FetchOutcome.Failed(
                    "GitHub 服务器返回 ${result.code}",
                    needsToken = false,
                )

                else -> return FetchOutcome.Failed("GitHub 返回 ${result.code}", needsToken = false)
            }
        }
        val label = lastFailure?.label ?: "连不上"
        return FetchOutcome.Failed("$label（试了 $attemptNo 次）", needsToken = false, connection = true)
    }

    /** token 只发白名单 host：默认的 api.github.com，或用户自己填的代理域。绝不发 raw。 */
    private suspend fun headersFor(url: String): Map<String, String> {
        val base = LinkedHashMap<String, String>()
        base["Accept"] = "application/vnd.github+json"
        base["X-GitHub-Api-Version"] = "2022-11-28"
        val token = tokenProvider()?.trim().orEmpty()
        if (token.isBlank()) return base
        val host = hostOf(url) ?: return base
        val proxyHost = proxyProvider()?.trim()?.takeIf { it.isNotBlank() }?.let { hostOf(it) }
        val tokenHost = when {
            host == GITHUB_API_HOST -> true
            proxyHost != null && host == proxyHost -> true
            else -> false
        }
        // 存文件的域（raw.githubusercontent.com 等）永远不带凭据，哪怕是用户填的代理
        if (tokenHost && NEVER_TOKEN_HOSTS.none { host.endsWith(it) }) {
            base["Authorization"] = "Bearer $token"
        }
        return base
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

    companion object {
        const val DEFAULT_API_BASE = "https://api.github.com"
        const val GITHUB_API_HOST = "api.github.com"

        /** 只发文件的域：token 一律不带（方案 §3.6.2 红线：第三方链接不带 token）。 */
        private val NEVER_TOKEN_HOSTS = listOf("raw.githubusercontent.com", "githubusercontent.com")

        /** 只对连接层失败退避：第一次 0.5s，第二次 1.5s（方案 §3.6.3 第 2 条） */
        private val BACKOFF = longArrayOf(500L, 1_500L)
    }
}

/** 取证结果：要么拿到正文，要么带着人话原因失败（原因必须进报告）。 */
sealed interface FetchOutcome {
    data class Ok(val body: String) : FetchOutcome

    /**
     * `connection` 标记「连不上」这一类失败：**只有这一类**才允许代理→直连回退。
     * 4xx/5xx 是服务端真的回话了，换成直连再打一次既浪费配额，又会把真实错误盖掉
     * （真机验收时踩过：假服务回 500，回退直连打到真 GitHub，最后报的是「token 不认」）。
     */
    data class Failed(
        val reason: String,
        val needsToken: Boolean = false,
        val connection: Boolean = false,
    ) : FetchOutcome
}

/** 「同一 URL 24h 内不再打网络」的缓存抽象（真实实现在数据层，单测给内存版）。 */
interface EvidenceCache {
    suspend fun get(url: String): String?
    suspend fun put(url: String, body: String)

    companion object {
        val None: EvidenceCache = object : EvidenceCache {
            override suspend fun get(url: String): String? = null
            override suspend fun put(url: String, body: String) = Unit
        }
    }
}
