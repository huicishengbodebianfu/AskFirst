package com.askfirst.domain.research

import com.askfirst.core.AppResult
import com.askfirst.core.Clock
import com.askfirst.core.IdSource
import com.askfirst.domain.model.Judgement
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.ResearchStatus
import com.askfirst.domain.model.Verdict
import com.askfirst.research.EvidenceCache
import com.askfirst.research.FetchFailure
import com.askfirst.research.FetchResult
import com.askfirst.research.GitHubClient
import com.askfirst.research.HttpFetcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管道：60 秒预算、部分成功、失败可见、0 证据不下结论、模型挂了也留残本。
 * 网络与模型都是假的——这里验的是「纪律」，不是外部服务。
 */
class ResearchPipelineTest {

    private class FakeClock(var current: Long = 1_000L) : Clock {
        override fun now(): Long = current
    }

    private val ids = IdSource { prefix -> "${prefix}_fIX" }

    private val questions = listOf(
        ResearchFixtures.question(idx = 1),
        ResearchFixtures.question(idx = 2, text = "SAF 授权重启后还在不在", keywords = listOf("SAF persistable")),
    )

    /** 一个能按需失败的假 GitHub 网络层。 */
    private fun fetcher(
        clock: FakeClock? = null,
        advancePerCall: Long = 100L,
        failIssues: Boolean = false,
        failReadme: Boolean = false,
        emptyRepos: Boolean = false,
        alwaysTimeout: Boolean = false,
    ) = object : HttpFetcher {
        var calls = 0
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            callTimeoutMs: Int,
        ): FetchResult {
            calls += 1
            clock?.let { it.current += advancePerCall }
            if (alwaysTimeout) return FetchResult(code = 0, failure = FetchFailure.TIMEOUT)
            return when {
                url.contains("/search/issues") && failIssues -> FetchResult(code = 500)
                url.contains("/search/repositories") ->
                    if (emptyRepos) FetchResult(code = 200, body = """{"total_count":0,"items":[]}""")
                    else FetchResult(code = 200, body = ResearchFixtures.REPOS_JSON)

                url.contains("/search/issues") -> FetchResult(code = 200, body = ResearchFixtures.ISSUES_JSON)
                url.contains("/readme") ->
                    if (failReadme) FetchResult(code = 404) else FetchResult(code = 200, body = ResearchFixtures.README_JSON)

                else -> FetchResult(code = 200, body = """{"total_count":0,"items":[]}""")
            }
        }
    }

    private fun github(fetcher: HttpFetcher, token: String? = null): GitHubClient = GitHubClient(
        fetcher = fetcher,
        cache = EvidenceCache.None,
        tokenProvider = { token },
        proxyProvider = { null },
        sleeper = { },
    )

    private fun judgeReturning(verdicts: List<Verdict>) = object : VerdictJudge {
        var called = 0
        override suspend fun judge(input: JudgeInput): AppResult<List<Verdict>> {
            called += 1
            return AppResult.Ok(verdicts)
        }
    }

    private fun input(
        recheck: Boolean = true,
        questions: List<ResearchQuestion> = this.questions,
    ) = ResearchInput(
        sessionId = "s_1",
        sheetId = "sh_1",
        planId = null,
        intent = "首屏 4 秒太慢，想加缓存",
        sceneName = "技术方案 · 架构选型",
        title = "首屏缓存分层",
        questions = questions,
        sheet = null,
        recheckEnabled = recheck,
    )

    @Test
    fun `跑完一轮拿到证据与判定`() = runTest {
        val clock = FakeClock()
        val judge = judgeReturning(
            listOf(
                ResearchFixtures.verdict(
                    judgement = Judgement.KNOWN_PIT,
                    evidenceIds = listOf("ev_q1_issue_1", "ev_q1_issue_2"),
                ),
            ),
        )
        // 给一个 token：否则 code search 这一类会如实失败，状态就是 partial（那是另一条用例）
        val pipeline = ResearchPipeline(github(fetcher(clock), token = "ghp_test"), judge, ids, clock)
        val events = pipeline.run(input()).toList()
        val done = events.filterIsInstance<ResearchEvent.Done>().single()
        assertTrue("证据要进库", done.research.evidence.isNotEmpty())
        assertTrue("至少要有一条判定", done.research.verdicts.isNotEmpty())
        assertTrue("README 原文也要被抓下来", done.research.evidence.any { it.kind == com.askfirst.domain.model.EvidenceKind.README })
        assertEquals(1, judge.called)
        assertEquals(ResearchStatus.DONE, done.research.status)
        assertTrue(done.check.canShow)
    }

    @Test
    fun `issue 检索 500 只是部分成功，报告照出`() = runTest {
        val clock = FakeClock()
        // 仓库那一侧正常（3 个独立项目）→ 常见做法成立，于是「有结论 + 有失败」= partial
        val judge = judgeReturning(
            listOf(
                ResearchFixtures.verdict(
                    judgement = Judgement.COMMON,
                    evidenceIds = listOf("ev_q1_repo_1", "ev_q1_repo_2", "ev_q1_repo_3"),
                ),
            ),
        )
        val pipeline = ResearchPipeline(github(fetcher(clock, failIssues = true), token = "ghp_test"), judge, ids, clock)
        val done = pipeline.run(input()).toList().filterIsInstance<ResearchEvent.Done>().single()
        assertTrue(done.research.failures.any { it.source == "issue 检索" })
        assertEquals(ResearchStatus.PARTIAL, done.research.status)
        assertTrue("残缺要如实标出来", done.check.warnings.any { it.contains("没成功") })
    }

    @Test
    fun `README 404 记一行失败但不影响结论`() = runTest {
        val clock = FakeClock()
        val judge = judgeReturning(listOf(ResearchFixtures.verdict()))
        val pipeline = ResearchPipeline(github(fetcher(clock, failReadme = true), token = "ghp_test"), judge, ids, clock)
        val done = pipeline.run(input()).toList().filterIsInstance<ResearchEvent.Done>().single()
        assertTrue(done.research.failures.any { it.source == "README" })
        assertTrue(done.research.verdicts.isNotEmpty())
    }

    @Test
    fun `一条证据都没有时不下结论也不白烧 token`() = runTest {
        val clock = FakeClock()
        val judge = judgeReturning(listOf(ResearchFixtures.verdict()))
        val pipeline = ResearchPipeline(
            github(fetcher(clock, emptyRepos = true, failIssues = true, failReadme = true), token = "ghp_test"),
            judge,
            ids,
            clock,
        )
        val events = pipeline.run(input()).toList()
        assertEquals(0, judge.called)
        val failed = events.filterIsInstance<ResearchEvent.Failed>().single()
        assertTrue(failed.error.message.orEmpty().contains("一条证据都没拿到"))
        assertTrue("半成品也要留着，用户可以看失败原因", failed.partial != null)
        assertTrue(failed.partial!!.verdicts.isEmpty())
    }

    @Test
    fun `模型对照失败时留下证据残本`() = runTest {
        val clock = FakeClock()
        val judge = object : VerdictJudge {
            override suspend fun judge(input: JudgeInput): AppResult<List<Verdict>> =
                AppResult.Err(com.askfirst.core.AppError.BadJson("{", "一条能用的判定都没有"))
        }
        val pipeline = ResearchPipeline(github(fetcher(clock), token = "ghp_test"), judge, ids, clock)
        val done = pipeline.run(input()).toList().filterIsInstance<ResearchEvent.Done>().single()
        assertTrue(done.research.evidence.isNotEmpty())
        assertTrue(done.research.verdicts.isEmpty())
        // 有证据没结论：这份报告的判据是 FAILED（不是「无人区」）
        assertEquals(ResearchStatus.FAILED, done.research.status)
        assertTrue("失败的对照要留一行原因", done.research.failures.any { it.source == "对照" })
    }

    @Test
    fun `60 秒预算用完就用手里的结果出报告`() = runTest {
        val clock = FakeClock()
        // 每次请求吃掉 20 秒 → 第 4 次请求之后就超预算了
        val slow = fetcher(clock, advancePerCall = 20_000L)
        val judge = judgeReturning(listOf(ResearchFixtures.verdict()))
        val pipeline = ResearchPipeline(github(slow, token = "ghp_test"), judge, ids, clock)
        val events = pipeline.run(input()).toList()
        val done = events.filterIsInstance<ResearchEvent.Done>().single()
        assertTrue("预算用尽要记一行", done.research.failures.any { it.reason.contains("60 秒") })
        assertTrue("手里有多少就出多少", done.research.evidence.isNotEmpty())
    }

    @Test
    fun `0 命中后做换词复核，复核过的问题才可能判无人区`() = runTest {
        val clock = FakeClock()
        val empty = object : HttpFetcher {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                connectTimeoutMs: Int,
                readTimeoutMs: Int,
                callTimeoutMs: Int,
            ): FetchResult {
                clock.current += 50
                return FetchResult(code = 200, body = """{"total_count":0,"items":[]}""")
            }
        }
        val judge = judgeReturning(
            listOf(
                ResearchFixtures.verdict(
                    judgement = Judgement.UNEXPLORED,
                    rationale = "换了说法也没找到同类，这块没人验证过。",
                    evidenceIds = emptyList(),
                ),
            ),
        )
        val pipeline = ResearchPipeline(github(empty), judge, ids, clock)
        val events = pipeline.run(input(questions = listOf(questions.first()))).toList()
        // 一条证据都没有 → 走 Failed 分支，残本里仍然保留了「复核过哪些问题」
        val partial = (events.filterIsInstance<ResearchEvent.Failed>().single()).partial!!
        assertEquals(listOf(1), partial.recheckedQuestions)
    }

    // ---- docs/28 P1-2：复核失败不许记成"复核过" ----

    @Test
    fun `复核请求也失败时 不许把自己标成已复核`() = runTest {
        val clock = FakeClock()
        // 所有请求都超时：三条检索全失败，复核那一条也失败
        val dead = fetcher(clock, alwaysTimeout = true)
        val pipeline = ResearchPipeline(
            github(dead, token = "ghp_test"),
            judgeReturning(emptyList()),
            ids,
            clock,
        )
        val events = pipeline.run(input(recheck = true)).toList()
        val research = events.filterIsInstance<ResearchEvent.Failed>().single().partial!!
        assertTrue(
            "一个请求都没成功，凭什么说复核过：${research.recheckedQuestions}",
            research.recheckedQuestions.isEmpty(),
        )
        assertTrue("失败原因要如实记下来", research.failures.any { it.source == "换词复核" })
    }

    @Test
    fun `网络全挂时模型说无人区也必须被降级`() = runTest {
        val clock = FakeClock()
        val dead = fetcher(clock, alwaysTimeout = true)
        val judge = judgeReturning(
            listOf(
                ResearchFixtures.verdict(
                    judgement = Judgement.UNEXPLORED,
                    rationale = "换了说法也没找到同类，这块没人验证过。",
                    evidenceIds = emptyList(),
                ),
            ),
        )
        val pipeline = ResearchPipeline(github(dead, token = "ghp_test"), judge, ids, clock)
        val research = pipeline.run(input(questions = listOf(questions.first())))
            .toList()
            .filterIsInstance<ResearchEvent.Failed>()
            .single()
            .partial!!

        val normalized = ResearchRules.normalize(
            // 一条证据都没有时管道不会调模型，所以这里直接拿一条「无人区」判定来撞规则：
            // 用管道跑出来的真实 recheckedQuestions，看它够不够格支撑这个结论
            verdicts = listOf(
                ResearchFixtures.verdict(
                    judgement = Judgement.UNEXPLORED,
                    rationale = "换了说法也没找到同类，这块没人验证过。",
                    evidenceIds = emptyList(),
                ),
            ),
            evidence = research.evidence,
            questions = research.questions,
            rechecked = research.recheckedQuestions.toSet(),
        )
        assertTrue(
            "网络全挂不许下「无人区」的结论：${normalized.verdicts.map { it.judgement }}",
            normalized.verdicts.none { it.judgement == Judgement.UNEXPLORED },
        )
    }

    @Test
    fun `关掉复核开关就不做第二次检索`() = runTest {

        val clock = FakeClock()
        val fetcher = fetcher(clock, emptyRepos = true, failIssues = true, failReadme = true)
        val pipeline = ResearchPipeline(github(fetcher), judgeReturning(emptyList()), ids, clock)
        pipeline.run(input(recheck = false, questions = listOf(questions.first()))).toList()
        val repoSearches = fetcher.calls
        // 关掉复核：只有 repo/issue/code 三类各一次，没有第二次 repo 检索
        assertTrue(repoSearches <= 3)
    }

    @Test
    fun `失败原因里不会出现 token 明文`() = runTest {
        val clock = FakeClock()
        val fetcher = fetcher(clock, failIssues = true)
        val githubWithToken = GitHubClient(
            fetcher = fetcher,
            tokenProvider = { "ghp_super_secret" },
            sleeper = { },
        )
        val pipeline = ResearchPipeline(githubWithToken, judgeReturning(emptyList()), ids, clock)
        val events = pipeline.run(input()).toList()
        val text = events.toString()
        assertFalse(text.contains("ghp_super_secret"))
    }
}
