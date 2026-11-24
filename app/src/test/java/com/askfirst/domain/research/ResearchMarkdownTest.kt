package com.askfirst.domain.research

import com.askfirst.domain.model.Judgement
import com.askfirst.domain.model.ResearchFailure
import com.askfirst.domain.model.ResearchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 验证报告的模板纪律（方案 附录 C 三条）：
 * 坏消息在最前、顶部必须有「证据覆盖 N/M」、无人区必须附已覆盖关键词。
 */
class ResearchMarkdownTest {

    private val markdown = ResearchMarkdown()

    private fun dayOf(epochDay: Long): Long =
        LocalDate.of(2026, 11, 24).plusDays(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun fullResearch() = ResearchFixtures.research(
        questions = listOf(
            ResearchFixtures.question(idx = 1),
            ResearchFixtures.question(idx = 2, text = "SAF 授权重启后还在不在", keywords = listOf("SAF", "persistable")),
        ),
        evidence = listOf(
            ResearchFixtures.repo(questionIdx = 1),
            ResearchFixtures.issue(id = "ev_q1_issue_1", questionIdx = 1),
            ResearchFixtures.issue(id = "ev_q1_issue_2", questionIdx = 1, title = "同一个坑又踩了一次"),
        ),
        verdicts = listOf(
            ResearchFixtures.verdict(
                id = "v1",
                judgement = Judgement.ANTIPATTERN,
                rationale = "有项目明确放弃了把确认单存成单个 JSON。",
                quotedText = "single JSON file caused corruption on concurrent writes, moved to per-record rows",
                quotedUrl = "https://github.com/owner/cache-lib/issues/217",
                evidenceIds = listOf("ev_q1_issue_1"),
            ),
            ResearchFixtures.verdict(
                id = "v2",
                judgement = Judgement.COMMON,
                rationale = "本地优先 + 导出 Markdown 是常见组合。",
                evidenceIds = listOf("ev_q1_repo_1"),
            ),
            ResearchFixtures.verdict(
                id = "v3",
                judgement = Judgement.KNOWN_PIT,
                rationale = "两个项目在同一个写入路径上摔过。",
                evidenceIds = listOf("ev_q1_issue_1", "ev_q1_issue_2"),
            ),
            ResearchFixtures.verdict(
                id = "v4",
                questionIdx = 2,
                judgement = Judgement.UNEXPLORED,
                rationale = "换了说法也没找到同类，这块没人验证过。",
                evidenceIds = emptyList(),
            ),
        ),
        failures = listOf(ResearchFailure(2, "issue 检索", "GitHub 返回 500")),
        status = ResearchStatus.PARTIAL,
    )

    @Test
    fun `顶部有覆盖度 耗时 与来源`() {
        val text = markdown.export(fullResearch(), "技术方案 · 架构选型", "首屏 4 秒太慢，想加缓存", dayOf(0))
        assertTrue(text.startsWith("# 方案验证："))
        assertTrue(text.contains("证据覆盖 1/2 问题"))
        assertTrue(text.contains("耗时 12s"))
        assertTrue(text.contains("源 GitHub（匿名）"))
        assertTrue("场景要写出来", text.contains("技术方案 · 架构选型"))
    }

    @Test
    fun `没拿到证据的那个问题要明说结论不成立`() {
        val text = markdown.export(fullResearch(), null, "首屏 4 秒太慢", dayOf(0))
        assertTrue(text.contains("其中 1 个问题未取得证据"))
        assertTrue(text.contains("「SAF 授权重启后还在不在」"))
        assertTrue(text.contains("本报告对它们的任何结论均不成立"))
    }

    @Test
    fun `反模式排在常见做法前面且带原话与链接`() {
        val text = markdown.export(fullResearch(), null, "首屏 4 秒太慢", dayOf(0))
        val anti = text.indexOf("## ⚠️ 反模式")
        val common = text.indexOf("## ✅ 常见做法")
        assertTrue("坏消息必须在最前", anti in 0 until common)
        assertTrue(text.contains("> single JSON file caused corruption"))
        assertTrue(text.contains("> — https://github.com/owner/cache-lib/issues/217"))
        assertTrue(text.contains("已写入第 2 栏「约束与前提」"))
    }

    @Test
    fun `无人区必须附已覆盖关键词`() {
        val text = markdown.export(fullResearch(), null, "首屏 4 秒太慢", dayOf(0))
        assertTrue(text.contains("## ❓ 无人区"))
        assertTrue(text.contains("已覆盖关键词：SAF / persistable"))
        assertTrue(text.contains("已写入第 5 栏"))
    }

    @Test
    fun `失败逐条可见并写明原因`() {
        val text = markdown.export(fullResearch(), null, "首屏 4 秒太慢", dayOf(0))
        assertTrue(text.contains("## 证据缺在哪"))
        assertTrue(text.contains("❌ 「SAF 授权重启后还在不在」 · issue 检索：GitHub 返回 500"))
    }

    @Test
    fun `整轮级失败不许印出空的括号`() {
        // docs/28 P2-5：预算用尽 / 模型对照失败都记 questionIdx = 0，原来渲染成 `❌ 「」 · 全部：…`
        val research = ResearchFixtures.research(
            failures = listOf(ResearchFailure(0, "全部", "60 秒预算用完了")),
            evidence = listOf(ResearchFixtures.repo()),
        )
        val text = markdown.export(research, null, "首屏 4 秒太慢", dayOf(0))
        assertTrue("整轮级失败要有人话标签：$text", text.contains("【整轮调研】 · 全部：60 秒预算用完了"))
        assertFalse("不许出现空的「」", text.contains("「」"))
    }

    @Test
    fun `一条结论都没有时明确说不下结论`() {
        val empty = ResearchFixtures.research(
            questions = listOf(ResearchFixtures.question()),
            evidence = listOf(ResearchFixtures.repo()),
            verdicts = emptyList(),
            status = ResearchStatus.FAILED,
        )
        val text = markdown.export(empty, null, "首屏 4 秒太慢", dayOf(0))
        assertTrue(text.contains("没有被验证的部分（不写结论）"))
        assertTrue(text.contains("不下结论比下错结论有用"))
    }

    @Test
    fun `报告里没有 token 与内部 id`() {
        val text = markdown.export(fullResearch(), null, "首屏 4 秒太慢", dayOf(0))
        assertFalse(text.contains("Bearer"))
        assertFalse(text.contains("ghp_"))
        assertFalse(text.contains("sectionsJson"))
        assertFalse(text.contains("ev_q"))
    }

    @Test
    fun `文件名是日期加验证加标题`() {
        val research = ResearchFixtures.research(title = "首屏缓存分层")
        val name = markdown.fileName(research, "首屏 4 秒太慢", dayOf(0))
        assertTrue(name.endsWith(".md"))
        assertTrue(name.contains("2026-11-24"))
        assertTrue(name.contains("验证"))
        assertTrue(name.contains("首屏缓存分层"))
    }

    @Test
    fun `没有标题时用最初那句话当标题`() {
        val research = ResearchFixtures.research(title = "")
        val text = markdown.export(research, null, "首屏 4 秒太慢，想加缓存", dayOf(0))
        assertTrue(text.contains("首屏 4 秒太慢，想加缓存"))
    }

    @Test
    fun `离线跑回来的报告标明来源`() {
        val research = ResearchFixtures.research().copy(
            source = com.askfirst.domain.model.ResearchSource.OFFLINE,
        )
        val text = markdown.export(research, null, "首屏 4 秒太慢", dayOf(0))
        assertTrue(text.contains("离线跑完粘回来的结果"))
    }
}
