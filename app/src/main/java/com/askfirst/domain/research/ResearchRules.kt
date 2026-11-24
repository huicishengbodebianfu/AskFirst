package com.askfirst.domain.research

import com.askfirst.domain.model.AppliedTo
import com.askfirst.domain.model.Evidence
import com.askfirst.domain.model.EvidenceKind
import com.askfirst.domain.model.Judgement
import com.askfirst.domain.model.Research
import com.askfirst.domain.model.ResearchCheck
import com.askfirst.domain.model.ResearchStatus
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.ResearchFailure
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.Verdict

/**
 * 判定层的机械规则（docs/26 §3.9）。模型负责「看出来」，这里负责「不许乱说」：
 *
 * - 常见做法要 ≥3 个**独立**项目；不够就降级成「未取证」（1~2 个不叫常见）
 * - 反模式必须引到原话，且引文 URL 必须是这次真抓到的；否则这条判定直接丢
 * - 已知坑要 ≥2 条 issue（1 条不叫高频）
 * - 无人区要三条同时成立：关键词非空 + 做过换词复核 + 复核后仍 0 命中
 *
 * 这些规则全是纯函数，单测里可以直接撞边界。
 */
object ResearchRules {

    /** 判定成立时默认写进哪一栏（用户可以在页面上改，改不出第 3 栏）。 */
    fun defaultAppliedTo(judgement: Judgement): AppliedTo = when (judgement) {
        // 「已有 X 方案在用」是既成事实 → 约束与前提
        Judgement.ANTIPATTERN, Judgement.COMMON -> AppliedTo.COL2
        // 「没人验证过这个规模」是风险 → 还没想清楚的问题
        Judgement.KNOWN_PIT, Judgement.UNEXPLORED -> AppliedTo.COL5
        Judgement.UNGATHERED -> AppliedTo.APPENDIX
    }

    /** 报告顺序：反模式 → 已知坑 → 常见做法 → 无人区 → 未取证（坏消息在最前）。 */
    fun sortForReport(verdicts: List<Verdict>): List<Verdict> =
        verdicts.sortedWith(compareBy({ it.judgement.reportOrder }, { it.questionIdx }))

    /**
     * 降级成「未取证」：判定和**落点**一起降。
     * 只改 judgement 不改 appliedTo 的话，一条"先不下结论"的东西会赖在第 2/5 栏，
     * 而它本该只进附录（同为 UNGATHERED 的其它路径都落在附录，两套行为不一致）。
     */
    private fun Verdict.demoted(rationale: String): Verdict = copy(
        judgement = Judgement.UNGATHERED,
        appliedTo = defaultAppliedTo(Judgement.UNGATHERED),
        rationale = rationale,
    )

    /**
     * 归一化：把模型给的判定过一遍规则。返回值里的 `warnings` 是给人看的话（页面上显示）。
     */
    fun normalize(
        verdicts: List<Verdict>,
        evidence: List<Evidence>,
        questions: List<ResearchQuestion>,
        rechecked: Set<Int>,
    ): NormalizeResult {
        val byId = evidence.associateBy { it.id }
        val warnings = mutableListOf<Verdict>()
        val kept = mutableListOf<Verdict>()

        verdicts.forEach { verdict ->
            val question = questions.firstOrNull { it.idx == verdict.questionIdx }
            if (question == null) {
                warnings += verdict.copy(rationale = "这条判定对不上任何问题，丢了")
                return@forEach
            }
            // 证据白名单：模型编的 id 在这里被切掉
            val validEvidence = verdict.evidenceIds.mapNotNull { byId[it] }
            val cleaned = verdict.copy(
                evidenceIds = validEvidence.map { it.id },
                appliedTo = defaultAppliedTo(verdict.judgement),
            )

            val normalized = when (cleaned.judgement) {
                Judgement.COMMON -> {
                    val repos = validEvidence.mapNotNull { it.repoFullName }.distinct()
                    if (repos.size >= 3) {
                        cleaned
                    } else {
                        cleaned.demoted(
                            "只找到 ${repos.size} 个同类项目，够不上「常见做法」" +
                                "（要 3 个以上独立的项目才算）。这条先不下结论。",
                        )
                    }
                }

                Judgement.ANTIPATTERN -> {
                    val urlOk = cleaned.quotedUrl.isNotBlank() && evidence.any { it.url == cleaned.quotedUrl }
                    if (cleaned.quotedText.isNotBlank() && urlOk) {
                        cleaned
                    } else {
                        warnings += cleaned.copy(rationale = "这条反模式没引到原话或链接对不上，我不替你下这个结论")
                        return@forEach
                    }
                }

                Judgement.KNOWN_PIT -> {
                    val issues = validEvidence.filter { it.kind == EvidenceKind.ISSUE }
                    if (issues.size >= 2) {
                        cleaned
                    } else {
                        cleaned.demoted(
                            "同类 issue 只找到 ${issues.size} 条，够不上「高频坑」。这条先不下结论。",
                        )
                    }
                }

                Judgement.UNEXPLORED -> {
                    val hasKeywords = question.keywords.any { it.isNotBlank() }
                    val hasEvidence = evidence.any { it.questionIdx == question.idx }
                    when {
                        !hasKeywords -> cleaned.demoted(
                            "这个问题没留下检索关键词，没找到不能算数。改一下问题再查一次。",
                        )

                        !rechecked.contains(question.idx) -> cleaned.demoted(
                            "GitHub 内没命中，但这次没做换词复核——只能说「没查到」，不能说「没人做过」。",
                        )

                        hasEvidence -> cleaned.demoted(
                            "复核时有命中（见下面的证据），不能算无人区。",
                        )

                        else -> cleaned
                    }
                }

                Judgement.UNGATHERED -> cleaned
            }

            if (normalized.rationale.isBlank()) {
                warnings += normalized.copy(rationale = "这条判定没说清意味着什么")
                return@forEach
            }
            kept += normalized
        }

        // 同一问题同一判定只留第一条（模型偶尔会重复输出）
        val deduped = kept.distinctBy { it.questionIdx to it.judgement }
        return NormalizeResult(sortForReport(deduped), warnings)
    }

    /** 页面上「这份报告能不能看」的机械检查。 */
    fun check(research: Research): ResearchCheck {
        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (research.questions.isEmpty()) {
            blocking += "这次一个问题都没立起来。先加两个要查的问题。"
        }
        if (research.evidence.isEmpty() && research.failures.isEmpty()) {
            // 既没证据也没失败记录 = 静默失败，这种「无人区」是有害的
            blocking += "一条证据都没拿到，也没记下为什么。这次不算数，换个网络再试。"
        }
        research.verdicts.firstOrNull { it.appliedTo.col == Cols.OPTIONS }?.let {
            blocking += "有一条结论指向了「选定方案」那一栏。证据不许替你选方案。"
        }
        research.verdicts.filter { it.judgement == Judgement.UNEXPLORED }.forEach { verdict ->
            val question = research.questions.firstOrNull { it.idx == verdict.questionIdx }
            if (question == null || question.keywords.none { it.isNotBlank() }) {
                blocking += "有一条「无人区」没说清搜了哪些词。没找到不等于没人做。"
            }
        }

        val uncovered = research.unansweredQuestions
        if (uncovered.isNotEmpty()) {
            warnings += "证据覆盖 ${research.coverageText} 个问题：" +
                uncovered.joinToString("、") { "「${it.text}」" } + " 没查到，报告对它们的结论不成立。"
        }
        if (research.failures.isNotEmpty()) {
            warnings += "有 ${research.failures.size} 处检索没成功（下面逐条写了原因），这份报告是残缺的。"
        }
        return ResearchCheck(blocking = blocking, warnings = warnings)
    }

    /** 只留能写进某一栏的判定（页面与导出共用）。 */
    fun appliedTo(verdicts: List<Verdict>, target: AppliedTo): List<Verdict> =
        verdicts.filter { it.appliedTo == target }.sortedBy { it.questionIdx }

    /**
     * 状态判定：一条失败都没有才算 done；有失败但有结果就是 partial；
     * 一条像样的判定都没有 + 有失败 = failed，不算「无人区」。
     */
    fun statusOf(verdicts: List<Verdict>, failures: List<ResearchFailure>): ResearchStatus = when {
        verdicts.none { it.judgement != Judgement.UNGATHERED } && failures.isNotEmpty() -> ResearchStatus.FAILED
        failures.isNotEmpty() -> ResearchStatus.PARTIAL
        else -> ResearchStatus.DONE
    }

    /** 归一化结果：留下来的判定 + 被丢/被降级的原因（页面显示给人看）。 */
    data class NormalizeResult(val verdicts: List<Verdict>, val warnings: List<Verdict>)
}

