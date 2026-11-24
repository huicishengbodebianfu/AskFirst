package com.askfirst.domain.plan

import com.askfirst.domain.model.Plan
import com.askfirst.domain.model.PlanCheck
import com.askfirst.domain.model.PlanProblem

/**
 * 方案文档的机械检查（方案 §3.5 三条硬约束里能自动查的部分）。纯函数，可单测。
 *
 * 1. 扩写不许引入新决策 → **硬答案保护**（改动 #7）：正文里的数字/日期必须在原话里出现过，
 *    找不到的列出来（warning）。凭空冒出来的「3 个月排期」比错别字危险得多。
 * 2. 第 5 栏不许被消化 → 「待决问题」这一节必须在、不能空、不能写成「后续持续优化」（blocking）。
 * 3. 公文体黑名单 → 赋能/抓手/闭环这类词在给同事看的文档里显得空（warning）。
 */
class PlanValidator {

    fun check(plan: Plan, skeleton: List<String>, facts: String): PlanCheck {
        val sections = plan.sorted()
        val blocking = mutableListOf<PlanProblem>()
        val warnings = mutableListOf<PlanProblem>()

        // ---- 1. 「待决问题」必须在，且不是漂亮话
        val pending = sections.filter { PlanSkeleton.isPending(it.heading) }
        if (pending.isEmpty() || pending.all { it.body.isBlank() }) {
            blocking += PlanProblem.PendingMissing
        }
        val softened = pending.mapNotNull { section ->
            SOFTENING.firstOrNull { section.body.contains(it) }
                ?.let { PlanProblem.PendingSoftened(section.id, it) }
        }
        blocking += softened

        // ---- 2. 段落必须带出处（解析层已丢过，这里兜底）
        sections.filter { it.sourceMessageIds.isEmpty() }
            .forEach { blocking += PlanProblem.UnsourcedSection(it.id) }

        // ---- 3. 骨架缺章（不拦，但要说：可能是这场的材料真不够写那一节）
        val missing = skeleton.filter { heading ->
            sections.none { PlanSkeleton.matches(heading, it.heading) }
        }
        if (missing.isNotEmpty()) warnings += PlanProblem.MissingSection(joinHeadings(missing))

        // ---- 4. 硬答案保护：正文里的数字都要能在「原话 + 已确认的条目」里找到
        val corpus = facts
        val strayNumbers = sections.flatMap { section ->
            numbersOf(section.body).filterNot { corpus.contains(it) }
        }.distinct().take(8)
        if (strayNumbers.isNotEmpty()) warnings += PlanProblem.NumberNotInSources(strayNumbers)

        // ---- 5. 公文体黑名单
        val text = sections.joinToString("\n") { it.heading + "\n" + it.body }
        val hits = SOFT_TERMS.filter { text.contains(it) }
        if (hits.isNotEmpty()) warnings += PlanProblem.BannedTerm(hits)

        return PlanCheck(blocking = blocking.distinct(), warnings = warnings)
    }

    /**
     * 正文里「像事实」的数字：先去掉列表序号与脚注标记，免得把「1.」「2.」当成事实。
     *
     * 剥序号时必须要求**序号后面跟空白/行尾**：否则 `- 3.5 天上线` 会先变 `3.5 天上线`、
     * 再被当成序号剥成 `5 天上线`，于是既报一个原话里不存在的 `5`、又漏掉真正的 3.5（docs/28 P2-3）。
     */
    fun numbersOf(body: String): List<String> {
        val stripped = body
            .replace(Regex("""\[\^\d+]"""), " ")
            .lines()
            .joinToString("\n") { line ->
                // 反复剥：`- 3. xxx` 要剥两次（先项目符号，再序号）
                var current = line
                while (true) {
                    val next = current.replace(LIST_MARKER, "")
                    if (next == current) break
                    current = next
                }
                current
            }
        return Regex("""\d+(?:\.\d+)?""").findAll(stripped).map { it.value }.toList()
    }

    /** 列表符号 / 有序序号。序号必须后接空白或行尾（小数点是 `3.5`，不是序号）。 */
    private val LIST_MARKER = Regex("""^\s*(#{1,6}\s*)?((\d+[.、)])(?=\s|$)|[-*+])\s*""")

    private fun joinHeadings(headings: List<String>): String = headings.joinToString("、")

    private companion object {
        /** 「没想清楚」被改写成漂亮话的典型句式（方案 §3.5 硬约束 3 点名的就是这个）。 */
        val SOFTENING = listOf(
            "后续持续", "持续优化", "逐步完善", "进一步优化", "不断优化",
            "持续迭代", "后续再", "待后续", "后续跟进", "逐步提升",
        )

        /** 给同事看的文档里显得空的词（内部术语黑名单里挑与底座无关的那些）。 */
        val SOFT_TERMS = listOf("赋能", "抓手", "闭环", "沉淀", "心智", "对齐", "收敛", "结构化")
    }
}
