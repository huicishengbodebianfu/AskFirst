package com.askfirst.domain.research

import com.askfirst.domain.model.Evidence
import com.askfirst.domain.model.EvidenceKind
import com.askfirst.domain.model.Judgement
import com.askfirst.domain.model.Research
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.ResearchSource
import com.askfirst.domain.model.ResearchStatus
import com.askfirst.domain.model.Verdict

/** M6 调研的测试夹具：问题 / 证据 / 判定 / 一份调研。 */
object ResearchFixtures {

    const val SESSION_ID = "s_research"

    fun question(
        idx: Int = 1,
        text: String = "Room 里做中文全文检索，成熟方案是什么",
        keywords: List<String> = listOf("Room FTS5", "中文分词"),
        why: String = "你确认单里提过本地搜索",
    ) = ResearchQuestion(idx = idx, text = text, keywords = keywords, why = why)

    fun repo(
        id: String = "ev_q1_repo_1",
        questionIdx: Int = 1,
        repo: String = "owner/cache-lib",
        url: String = "https://github.com/owner/cache-lib",
        stars: Int = 1234,
    ) = Evidence(
        id = id,
        questionIdx = questionIdx,
        kind = EvidenceKind.REPO,
        title = repo,
        url = url,
        snippet = "一个本地优先的缓存库",
        repoFullName = repo,
        stars = stars,
        pushedAt = "2026-09-01T00:00:00Z",
    )

    fun issue(
        id: String = "ev_q1_issue_1",
        questionIdx: Int = 1,
        title: String = "single JSON blob corrupted on concurrent writes",
        url: String = "https://github.com/owner/cache-lib/issues/217",
        snippet: String = "single JSON file caused corruption on concurrent writes, moved to per-record rows",
    ) = Evidence(
        id = id,
        questionIdx = questionIdx,
        kind = EvidenceKind.ISSUE,
        title = title,
        url = url,
        snippet = snippet,
        repoFullName = "owner/cache-lib",
    )

    fun readme(
        id: String = "ev_q1_readme_1",
        questionIdx: Int = 1,
        repo: String = "owner/cache-lib",
    ) = Evidence(
        id = id,
        questionIdx = questionIdx,
        kind = EvidenceKind.README,
        title = "$repo 的 README",
        url = "https://github.com/$repo",
        snippet = "本地优先：数据先落 Room，导出走 Markdown。取舍：不上 FTS5，中文分词另开一仗。",
        repoFullName = repo,
    )

    fun verdict(
        id: String = "vd_q1_1",
        questionIdx: Int = 1,
        judgement: Judgement = Judgement.KNOWN_PIT,
        rationale: String = "至少两个项目在同一个地方摔过，你的方案里没有这一步。",
        quotedText: String = "",
        quotedUrl: String = "",
        evidenceIds: List<String> = listOf("ev_q1_issue_1"),
    ) = Verdict(
        id = id,
        questionIdx = questionIdx,
        judgement = judgement,
        rationale = rationale,
        quotedText = quotedText,
        quotedUrl = quotedUrl,
        // 夹具模拟「归一化之后」的状态：落点由规则分配，不是 APPENDIX 默认值
        appliedTo = ResearchRules.defaultAppliedTo(judgement),
        evidenceIds = evidenceIds,
    )

    fun research(
        questions: List<ResearchQuestion> = listOf(question()),
        evidence: List<Evidence> = listOf(repo(), issue()),
        verdicts: List<Verdict> = listOf(verdict()),
        failures: List<com.askfirst.domain.model.ResearchFailure> = emptyList(),
        status: ResearchStatus = ResearchStatus.DONE,
        title: String = "首屏缓存分层",
        rechecked: List<Int> = emptyList(),
    ) = Research(
        id = "rs_1",
        sessionId = SESSION_ID,
        sheetId = "sh_1",
        planId = "pl_1",
        title = title,
        questions = questions,
        evidence = evidence,
        verdicts = verdicts,
        failures = failures,
        status = status,
        elapsedMs = 12_000,
        source = ResearchSource.GITHUB,
        authenticated = false,
        recheckedQuestions = rechecked,
        createdAt = 0,
        updatedAt = 0,
    )

    /** GitHub search 的真实形状（截取了用得上的字段）。 */
    const val REPOS_JSON = """
        {"total_count":3,"items":[
          {"full_name":"owner/cache-lib","html_url":"https://github.com/owner/cache-lib",
           "description":"本地优先的缓存库","stargazers_count":1234,
           "pushed_at":"2026-09-01T00:00:00Z","default_branch":"main"},
          {"full_name":"owner2/room-fts","html_url":"https://github.com/owner2/room-fts",
           "description":null,"stargazers_count":88,"pushed_at":"2026-05-02T00:00:00Z"},
          {"full_name":"owner3/third-lib","html_url":"https://github.com/owner3/third-lib",
           "description":"第三个独立项目","stargazers_count":42,"pushed_at":"2026-01-09T00:00:00Z"},
          {"full_name":"owner4/no-url","description":"没有 html_url 的脏数据"}
        ]}
    """

    const val ISSUES_JSON = """
        {"total_count":2,"items":[
          {"html_url":"https://github.com/owner/cache-lib/issues/217",
           "title":"single JSON blob corrupted on concurrent writes",
           "body":"single JSON file caused corruption on concurrent writes,\n moved to per-record rows",
           "state":"closed","created_at":"2026-03-02T00:00:00Z",
           "repository_url":"https://api.github.com/repos/owner/cache-lib"},
          {"html_url":"https://github.com/owner2/room-fts/issues/9",
           "title":"FTS5 tokenizer eats chinese","body":null,
           "repository_url":"https://api.github.com/repos/owner2/room-fts"}
        ]}
    """

    const val CODE_JSON = """
        {"total_count":1,"items":[
          {"name":"CacheManager.kt","path":"app/src/CacheManager.kt",
           "html_url":"https://github.com/owner/cache-lib/blob/main/app/src/CacheManager.kt",
           "repository":{"full_name":"owner/cache-lib"}}
        ]}
    """

    /** README（base64）：内容是「本地优先 + 取舍」那几句，够判断「常见做法」。 */
    val README_JSON: String = run {
        val text = "# cache-lib\n\n![badge](https://img.shields.io/x)\n\n" +
            "本地优先：数据先落 Room，导出走 Markdown。\n\n" +
            "取舍：不上 FTS5，中文分词另开一仗。"
        val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        """{"name":"README.md","encoding":"base64","content":"$encoded"}"""
    }
}
