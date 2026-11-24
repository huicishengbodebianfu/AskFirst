package com.askfirst.domain.research

import com.askfirst.domain.model.AppliedTo
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.Evidence
import com.askfirst.domain.model.Judgement
import com.askfirst.domain.model.ResearchFailure
import com.askfirst.domain.model.ResearchStatus
import com.askfirst.domain.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四条硬规则 + 落点 + 覆盖度。这是 M6 的「不许乱说」那一层：
 * 规则放松了，报告就会开始骗人。
 */
class ResearchRulesTest {

    private val questions = listOf(
        ResearchFixtures.question(idx = 1),
        ResearchFixtures.question(idx = 2, text = "SAF 持久授权重启后还在不在", keywords = listOf("SAF")),
    )

    // ---- 落点 ----

    @Test
    fun `反模式与常见做法进第 2 栏 坑与无人区进第 5 栏`() {
        assertEquals(AppliedTo.COL2, ResearchRules.defaultAppliedTo(Judgement.ANTIPATTERN))
        assertEquals(AppliedTo.COL2, ResearchRules.defaultAppliedTo(Judgement.COMMON))
        assertEquals(AppliedTo.COL5, ResearchRules.defaultAppliedTo(Judgement.KNOWN_PIT))
        assertEquals(AppliedTo.COL5, ResearchRules.defaultAppliedTo(Judgement.UNEXPLORED))
        assertEquals(AppliedTo.APPENDIX, ResearchRules.defaultAppliedTo(Judgement.UNGATHERED))
    }

    @Test
    fun `枚举里根本不存在第 3 栏`() {
        val cols = AppliedTo.entries.mapNotNull { it.col }
        assertFalse("第 3 栏（选定方案）必须是进不去的", cols.contains(Cols.OPTIONS))
        assertEquals(listOf(Cols.CONSTRAINTS, Cols.OPEN_QUESTIONS), cols)
    }

    // ---- 常见做法：要 ≥3 个独立项目 ----

    @Test
    fun `三个独立项目的常见做法成立`() {
        val evidence = listOf(
            ResearchFixtures.repo("ev_q1_repo_1", repo = "a/one"),
            ResearchFixtures.repo("ev_q1_repo_2", repo = "b/two"),
            ResearchFixtures.repo("ev_q1_repo_3", repo = "c/three"),
        )
        val verdict = ResearchFixtures.verdict(
            judgement = Judgement.COMMON,
            evidenceIds = evidence.map { it.id },
        )
        val result = ResearchRules.normalize(listOf(verdict), evidence, questions, setOf(1, 2))
        assertEquals(Judgement.COMMON, result.verdicts.single().judgement)
        assertEquals(AppliedTo.COL2, result.verdicts.single().appliedTo)
    }

    @Test
    fun `只有两个项目时常见做法降级为没结论`() {
        val evidence = listOf(
            ResearchFixtures.repo("ev_q1_repo_1", repo = "a/one"),
            ResearchFixtures.repo("ev_q1_repo_2", repo = "b/two"),
        )
        val verdict = ResearchFixtures.verdict(
            judgement = Judgement.COMMON,
            evidenceIds = evidence.map { it.id },
        )
        val result = ResearchRules.normalize(listOf(verdict), evidence, questions, setOf(1, 2))
        assertEquals(Judgement.UNGATHERED, result.verdicts.single().judgement)
        assertTrue(result.verdicts.single().rationale.contains("3 个"))
    }

    @Test
    fun `同一个仓库出现三次不算三个独立项目`() {
        val evidence = listOf(
            ResearchFixtures.repo("ev_q1_repo_1", repo = "a/one"),
            ResearchFixtures.repo("ev_q1_repo_2", repo = "a/one"),
            ResearchFixtures.repo("ev_q1_repo_3", repo = "a/one"),
        )
        val verdict = ResearchFixtures.verdict(judgement = Judgement.COMMON, evidenceIds = evidence.map { it.id })
        val result = ResearchRules.normalize(listOf(verdict), evidence, questions, setOf(1, 2))
        assertEquals(Judgement.UNGATHERED, result.verdicts.single().judgement)
    }

    // ---- 反模式：必须引原话 + 链接对得上 ----

    @Test
    fun `引文 URL 不在这次证据里则整条丢掉`() {
        val evidence = listOf(ResearchFixtures.issue())
        val verdict = ResearchFixtures.verdict(
            judgement = Judgement.ANTIPATTERN,
            quotedText = "moved to per-record rows",
            quotedUrl = "https://github.com/someone/made-up/issues/1",
            evidenceIds = listOf("ev_q1_issue_1"),
        )
        val result = ResearchRules.normalize(listOf(verdict), evidence, questions, setOf(1, 2))
        assertTrue("编的链接不许下结论", result.verdicts.isEmpty())
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `引文与链接都真实时保留`() {
        val evidence = listOf(ResearchFixtures.issue())
        val verdict = ResearchFixtures.verdict(
            judgement = Judgement.ANTIPATTERN,
            quotedText = "moved to per-record rows",
            quotedUrl = "https://github.com/owner/cache-lib/issues/217",
            evidenceIds = listOf("ev_q1_issue_1"),
        )
        val result = ResearchRules.normalize(listOf(verdict), evidence, questions, setOf(1, 2))
        assertEquals(Judgement.ANTIPATTERN, result.verdicts.single().judgement)
        assertEquals(AppliedTo.COL2, result.verdicts.single().appliedTo)
    }

    // ---- 已知坑：≥2 条 issue ----

    @Test
    fun `一条 issue 不算高频坑`() {
        val evidence = listOf(ResearchFixtures.issue("ev_q1_issue_1"))
        val verdict = ResearchFixtures.verdict(judgement = Judgement.KNOWN_PIT, evidenceIds = listOf("ev_q1_issue_1"))
        val result = ResearchRules.normalize(listOf(verdict), evidence, questions, setOf(1, 2))
        assertEquals(Judgement.UNGATHERED, result.verdicts.single().judgement)
    }

    @Test
    fun `两条 issue 的坑成立且进第 5 栏`() {
        val evidence = listOf(
            ResearchFixtures.issue("ev_q1_issue_1"),
            ResearchFixtures.issue("ev_q1_issue_2", title = "same tokenizer bug again"),
        )
        val verdict = ResearchFixtures.verdict(
            judgement = Judgement.KNOWN_PIT,
            evidenceIds = evidence.map { it.id },
        )
        val result = ResearchRules.normalize(listOf(verdict), evidence, questions, setOf(1, 2))
        assertEquals(Judgement.KNOWN_PIT, result.verdicts.single().judgement)
        assertEquals(AppliedTo.COL5, result.verdicts.single().appliedTo)
    }

    // ---- 无人区：三条硬闸 ----

    @Test
    fun `复核过且关键词在且真没命中才算无人区`() {
        val verdict = ResearchFixtures.verdict(judgement = Judgement.UNEXPLORED, evidenceIds = emptyList())
        val result = ResearchRules.normalize(listOf(verdict), emptyList(), questions, setOf(1, 2))
        assertEquals(Judgement.UNEXPLORED, result.verdicts.single().judgement)
        assertEquals(AppliedTo.COL5, result.verdicts.single().appliedTo)
    }

    @Test
    fun `没做换词复核的无人区降级为未取证`() {
        val verdict = ResearchFixtures.verdict(judgement = Judgement.UNEXPLORED, evidenceIds = emptyList())
        val result = ResearchRules.normalize(listOf(verdict), emptyList(), questions, emptySet())
        assertEquals(Judgement.UNGATHERED, result.verdicts.single().judgement)
        assertTrue(result.verdicts.single().rationale.contains("换词复核"))
    }

    @Test
    fun `关键词为空的无人区降级为未取证`() {
        val bare = listOf(ResearchFixtures.question(idx = 1, keywords = emptyList()))
        val verdict = ResearchFixtures.verdict(judgement = Judgement.UNEXPLORED, evidenceIds = emptyList())
        val result = ResearchRules.normalize(listOf(verdict), emptyList(), bare, setOf(1))
        assertEquals(Judgement.UNGATHERED, result.verdicts.single().judgement)
        assertTrue(result.verdicts.single().rationale.contains("关键词"))
    }

    @Test
    fun `复核时有命中就不能算无人区`() {
        val evidence = listOf(ResearchFixtures.repo("ev_q1_repo_9"))
        val verdict = ResearchFixtures.verdict(judgement = Judgement.UNEXPLORED, evidenceIds = emptyList())
        val result = ResearchRules.normalize(listOf(verdict), evidence, questions, setOf(1, 2))
        assertEquals(Judgement.UNGATHERED, result.verdicts.single().judgement)
    }

    // ---- 排序 / 覆盖度 / 状态 ----

    @Test
    fun `报告排序是坏消息在前`() {
        val sorted = ResearchRules.sortForReport(
            listOf(
                ResearchFixtures.verdict(id = "a", judgement = Judgement.UNEXPLORED),
                ResearchFixtures.verdict(id = "b", judgement = Judgement.COMMON),
                ResearchFixtures.verdict(id = "c", judgement = Judgement.KNOWN_PIT),
                ResearchFixtures.verdict(id = "d", judgement = Judgement.ANTIPATTERN),
                ResearchFixtures.verdict(id = "e", judgement = Judgement.UNGATHERED),
            ),
        )
        assertEquals(
            listOf(Judgement.ANTIPATTERN, Judgement.KNOWN_PIT, Judgement.COMMON, Judgement.UNEXPLORED, Judgement.UNGATHERED),
            sorted.map { it.judgement },
        )
    }

    @Test
    fun `覆盖度按「拿到过证据的问题」算`() {
        val research = ResearchFixtures.research(
            questions = questions,
            evidence = listOf(ResearchFixtures.repo(questionIdx = 1)),
        )
        assertEquals("1/2", research.coverageText)
        assertEquals(1, research.unansweredQuestions.size)
    }

    @Test
    fun `状态：全成功是 done 有失败是 partial 一条结论都没有才 failed`() {
        assertEquals(
            ResearchStatus.DONE,
            ResearchRules.statusOf(listOf(ResearchFixtures.verdict()), emptyList()),
        )
        assertEquals(
            ResearchStatus.PARTIAL,
            ResearchRules.statusOf(
                listOf(ResearchFixtures.verdict()),
                listOf(ResearchFailure(1, "issue 检索", "GitHub 限流了")),
            ),
        )
        assertEquals(
            ResearchStatus.FAILED,
            ResearchRules.statusOf(
                listOf(ResearchFixtures.verdict(judgement = Judgement.UNGATHERED)),
                listOf(ResearchFailure(1, "issue 检索", "GitHub 限流了")),
            ),
        )
    }

    // ---- 机械检查 ----

    @Test
    fun `一条证据与一条失败都没有时报告不成立`() {
        val research = ResearchFixtures.research(
            evidence = emptyList(),
            verdicts = emptyList(),
            questions = emptyList(),
        )
        val check = ResearchRules.check(research)
        assertFalse(check.canShow)
        assertEquals(2, check.blocking.size)
    }

    @Test
    fun `有问题的失败与没覆盖的问题都写进提醒`() {
        val research = ResearchFixtures.research(
            questions = questions,
            evidence = listOf(ResearchFixtures.repo(questionIdx = 1)),
            failures = listOf(ResearchFailure(2, "issue 检索", "GitHub 返回 500")),
        )
        val check = ResearchRules.check(research)
        assertTrue(check.canShow)
        assertTrue(check.warnings.any { it.contains("覆盖") })
        assertTrue(check.warnings.any { it.contains("没成功") })
    }

    @Test
    fun `同一问题同一判定重复时只留第一条`() {
        val evidence = listOf(ResearchFixtures.issue("ev_q1_issue_1"), ResearchFixtures.issue("ev_q1_issue_2"))
        val verdicts = listOf(
            ResearchFixtures.verdict(id = "v1", judgement = Judgement.KNOWN_PIT, evidenceIds = evidence.map { it.id }),
            ResearchFixtures.verdict(id = "v2", judgement = Judgement.KNOWN_PIT, evidenceIds = evidence.map { it.id }),
        )
        val result = ResearchRules.normalize(verdicts, evidence, questions, setOf(1, 2))
        assertEquals(1, result.verdicts.size)
    }

    @Test
    fun `对不上问题号的判定被丢掉`() {
        val verdict: Verdict = ResearchFixtures.verdict(questionIdx = 99)
        val result = ResearchRules.normalize(listOf(verdict), listOf(ResearchFixtures.issue()), questions, setOf(1, 2))
        assertTrue(result.verdicts.isEmpty())
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `appliedTo 只挑出目标栏的判定`() {
        val verdicts = listOf(
            ResearchFixtures.verdict(id = "v1", judgement = Judgement.KNOWN_PIT),
            ResearchFixtures.verdict(id = "v2", judgement = Judgement.COMMON, evidenceIds = emptyList()),
        )
        val col5 = ResearchRules.appliedTo(verdicts, AppliedTo.COL5)
        assertEquals(listOf("v1"), col5.map { it.id })
        // 常见做法默认进第 2 栏（夹具的 appliedTo 已经按规则分配好了）
        assertEquals(listOf("v2"), ResearchRules.appliedTo(verdicts, AppliedTo.COL2).map { it.id })
        assertTrue(ResearchRules.appliedTo(verdicts, AppliedTo.APPENDIX).isEmpty())
    }

    @Test
    fun `证据清单里带 README 时不影响规则`() {
        val evidence: List<Evidence> = listOf(ResearchFixtures.readme())
        val verdict = ResearchFixtures.verdict(judgement = Judgement.KNOWN_PIT, evidenceIds = listOf("ev_q1_readme_1"))
        val result = ResearchRules.normalize(listOf(verdict), evidence, questions, setOf(1, 2))
        assertEquals(Judgement.UNGATHERED, result.verdicts.single().judgement)
    }
}
