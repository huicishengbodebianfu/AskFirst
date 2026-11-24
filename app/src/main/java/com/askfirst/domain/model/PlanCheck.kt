package com.askfirst.domain.model

/**
 * 方案文档的本地检查结果（方案 §3.5 三条硬约束的机械那一半）。
 * `blocking` 非空 = 「这份文档还不能发出去」，但**不阻止保存**（用户可以自己再改）。
 */
data class PlanCheck(
    val blocking: List<PlanProblem> = emptyList(),
    val warnings: List<PlanProblem> = emptyList(),
) {
    val canSend: Boolean get() = blocking.isEmpty()

    /** 给用户看的一句话（人话，不出现内部术语）。 */
    val headline: String?
        get() = when {
            blocking.any { it is PlanProblem.PendingMissing } ->
                "这份方案还发不出去：缺了「待决问题」这一节。没想清楚的事必须留在文档里，不能省。"

            blocking.any { it is PlanProblem.PendingSoftened } ->
                "「待决问题」里出现了「后续持续优化」这类话。这一节是整份文档的安全带，请照原话写。"

            blocking.any { it is PlanProblem.UnsourcedSection } ->
                "有一段找不到出处了，我先把它标出来，你确认下是不是你自己说过的。"

            else -> null
        }

    /** 提醒但不拦（数字对不上、骨架缺章、用词不书面）。 */
    val warningText: String?
        get() = buildList {
            warnings.filterIsInstance<PlanProblem.MissingSection>()
                .takeIf { it.isNotEmpty() }
                ?.joinToString("、") { it.heading }
                ?.let { add("骨架里这几节这次没写：$it") }
            warnings.filterIsInstance<PlanProblem.NumberNotInSources>()
                .flatMap { it.tokens }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString("、")
                ?.let { add("文档里出现了原话里没有的数字：$it（数字要么来自原话，要么删掉）") }
            warnings.filterIsInstance<PlanProblem.BannedTerm>()
                .flatMap { it.terms }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString("、")
                ?.let { add("这几个词在给同事看的文档里显得空：$it") }
        }.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

sealed interface PlanProblem {
    /** 缺「待决问题」这一节（或者写空了）——整份文档的安全带 */
    data object PendingMissing : PlanProblem

    /** 「待决问题」被写成了漂亮话（"后续持续优化"） */
    data class PendingSoftened(val sectionId: String, val hit: String) : PlanProblem

    /** 骨架里的这一节这次没出（不拦，但要说） */
    data class MissingSection(val heading: String) : PlanProblem

    /** 有段落一条真实出处都没有（理论上前面的解析已经丢掉，这里兜底） */
    data class UnsourcedSection(val sectionId: String) : PlanProblem

    /** 正文里的数字在原话里找不到（改动 #7：硬答案保护） */
    data class NumberNotInSources(val tokens: List<String>) : PlanProblem

    /** 命中公文体黑名单（赋能/闭环/…） */
    data class BannedTerm(val terms: List<String>) : PlanProblem
}
