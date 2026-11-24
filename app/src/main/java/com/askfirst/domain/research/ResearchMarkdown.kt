package com.askfirst.domain.research

import com.askfirst.domain.model.AppliedTo
import com.askfirst.domain.model.Judgement
import com.askfirst.domain.model.Research
import com.askfirst.domain.model.ResearchSource
import com.askfirst.domain.model.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 验证报告 → Markdown（附录 C 的模板，落盘/分享用）。纯 Kotlin，模板逐字稳定（单测锁定）。
 *
 * 三条格式纪律（方案 附录 C 末尾）写死在代码里：
 * 1. 坏消息放最前——反模式在最上面，且这份报告不会只说好话；
 * 2.「证据覆盖 N/M」必须在顶部，缺了多少就说什么不成立；
 * 3. 无人区必须附「已覆盖关键词」，否则「没找到」三个字不许出现。
 */
class ResearchMarkdown {

    fun export(research: Research, sceneName: String?, intent: String, now: Long = System.currentTimeMillis()): String {
        val checkWarnings = ResearchRules.check(research).warnings
        return buildString {
            appendLine("# 方案验证：${titleOf(research, intent)}")
            appendLine()
            appendLine(
                "调研时间 ${formatDate(now)} · 证据覆盖 ${research.coverageText} 问题 · " +
                    "耗时 ${(research.elapsedMs / 1000).coerceAtLeast(1)}s · ${sourceLine(research)}",
            )
            if (sceneName != null) appendLine("场景：$sceneName")

            val uncovered = research.unansweredQuestions
            if (uncovered.isNotEmpty()) {
                appendLine(
                    "其中 ${uncovered.size} 个问题未取得证据：" +
                        uncovered.joinToString("、") { "「${it.text}」" } +
                        " → 本报告对它们的任何结论均不成立",
                )
            }
            appendLine()

            // 1. 反模式（先看坏消息）
            val antipatterns = research.verdictsOf(Judgement.ANTIPATTERN)
            if (antipatterns.isNotEmpty()) {
                appendLine("## ⚠️ 反模式（先看坏消息）")
                appendLine()
                antipatterns.forEach { verdict ->
                    appendLine("- ${verdict.rationale}")
                    if (verdict.quotedText.isNotBlank()) {
                        appendLine("  > ${verdict.quotedText}")
                        if (verdict.quotedUrl.isNotBlank()) appendLine("  > — ${verdict.quotedUrl}")
                    }
                    appendLine("  → ${where(verdict)}")
                }
                appendLine()
            }

            // 2. 已知坑
            val pits = research.verdictsOf(Judgement.KNOWN_PIT)
            if (pits.isNotEmpty()) {
                appendLine("## 🔍 已知坑（按频率排序）")
                appendLine()
                pits.forEachIndexed { index, verdict ->
                    appendLine("${index + 1}. ${verdict.rationale}")
                    evidenceLinks(research, verdict).forEach { appendLine("   - $it") }
                }
                appendLine()
            }

            // 3. 常见做法（你不是孤例）
            val commons = research.verdictsOf(Judgement.COMMON)
            if (commons.isNotEmpty()) {
                appendLine("## ✅ 常见做法（你不是孤例）")
                appendLine()
                commons.forEach { verdict ->
                    appendLine("- ${verdict.rationale}")
                    evidenceLinks(research, verdict).take(4).forEach { appendLine("  - $it") }
                }
                appendLine()
            }

            // 4. 无人区（必须附已覆盖关键词）
            val unexplored = research.verdictsOf(Judgement.UNEXPLORED)
            if (unexplored.isNotEmpty()) {
                appendLine("## ❓ 无人区")
                appendLine()
                unexplored.forEach { verdict ->
                    val question = research.questions.firstOrNull { it.idx == verdict.questionIdx }
                    appendLine("「${question?.text.orEmpty()}」未检索到同类。")
                    appendLine("已覆盖关键词：${question?.keywords.orEmpty().joinToString(" / ")}")
                    appendLine("${verdict.rationale}")
                    appendLine("→ ${where(verdict)}")
                    appendLine()
                }
            }

            // 5. 未取证（不下结论）
            val ungathered = research.verdictsOf(Judgement.UNGATHERED)
            if (ungathered.isNotEmpty()) {
                appendLine("## · 没查够的（不写结论）")
                appendLine()
                ungathered.forEach { verdict -> appendLine("- ${verdict.rationale}") }
                appendLine()
            }

            // 6. 失败可见
            if (research.failures.isNotEmpty()) {
                appendLine("## 证据缺在哪 / 哪几条没采纳")
                appendLine()
                research.failures.forEach { failure ->
                    appendLine("❌ ${failureLabel(research, failure.questionIdx)} · ${failure.source}：${failure.reason}")
                }
                appendLine()
            }

            if (research.verdicts.isEmpty()) {
                appendLine("## 没有被验证的部分（不写结论）")
                appendLine()
                appendLine("这次一条能站得住的结论都没有。证据不够的时候，不下结论比下错结论有用。")
                appendLine()
            }

            checkWarnings.forEach { appendLine("> $it") }
            if (checkWarnings.isNotEmpty()) appendLine()

            appendLine("---")
            appendLine("由 AskFirst 生成 · 证据来自公开仓库与 issue · 结论只进第 2/5 栏")
        }
    }

    /** 落盘文件名：`2026-11-24-验证-首屏缓存分层.md`。 */
    fun fileName(research: Research, intent: String, now: Long = System.currentTimeMillis()): String {
        val base = titleOf(research, intent)
            .take(24)
            .replace(Regex("""[\\/:*?"<>|\s，。；：？！、（）【】《》"'“”‘’]+"""), "_")
            .trim('_')
            .ifBlank { "验证" }
        return "${formatFileDate(now)}-验证-$base.md"
    }

    fun titleOf(research: Research, intent: String): String {
        val fromResearch = research.title.trim()
        if (fromResearch.isNotBlank()) return fromResearch
        val oneLine = intent.replace(Regex("""\s+"""), " ").trim()
        return if (oneLine.length <= 22) oneLine.ifBlank { "这份方案" } else oneLine.take(22) + "…"
    }

    /** 只导出写进确认单的那两栏（附录不写，避免同一句话在两处出现）。 */
    fun appliedSummary(research: Research): String = buildString {
        listOf(AppliedTo.COL2, AppliedTo.COL5).forEach { target ->
            val items = ResearchRules.appliedTo(research.verdicts, target)
            if (items.isEmpty()) return@forEach
            appendLine("### 调研补充 · ${target.label}")
            items.forEach { verdict -> appendLine("- ${verdict.rationale}") }
            appendLine()
        }
    }

    private fun where(verdict: Verdict): String = when (verdict.appliedTo) {
        AppliedTo.COL2 -> "已写入第 2 栏「约束与前提」"
        AppliedTo.COL5 -> "已写入第 5 栏「还没想清楚的问题」"
        AppliedTo.APPENDIX -> "只放进这份报告"
    }

    /**
     * 失败的归属：能对上问题就引问题，对不上（questionIdx = 0 是整轮级的失败，
     * 例如 60 秒预算用尽、模型对照失败）就给个人话标签，别印出空的「」（docs/28 P2-5）。
     */
    private fun failureLabel(research: Research, questionIdx: Int): String {
        research.questions.firstOrNull { it.idx == questionIdx }?.let { return "「${it.text}」" }
        return if (questionIdx <= 0) "【整轮调研】" else "【第 $questionIdx 个问题】"
    }

    private fun evidenceLinks(research: Research, verdict: Verdict): List<String> =
        research.evidenceOf(verdict).map { evidence ->
            val stars = if (evidence.stars > 0) " · ★${evidence.stars}" else ""
            "${evidence.title}$stars · ${evidence.url}"
        }

    private fun sourceLine(research: Research): String = when (research.source) {
        ResearchSource.GITHUB -> "源 GitHub（${if (research.authenticated) "已认证" else "匿名"}）"
        ResearchSource.OFFLINE -> "源：离线跑完粘回来的结果"
    }

    private fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    private fun formatFileDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
}

