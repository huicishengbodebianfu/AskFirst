package com.askfirst.domain.model

/**
 * M5 方案文档（方案 §3.5）：确认单是给自己看的骨架，这份是**能交给别人看的文档**。
 * 结构上是「段落集合」而不是一段散文——段落天生带出处，逐段溯源与逐段删才不用重建。
 */
data class Plan(
    val id: String,
    val sessionId: String,
    val sheetId: String,
    /** 文档标题（模型给，落盘时进文件名） */
    val title: String,
    val sections: List<PlanSection>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun sorted(): List<PlanSection> = sections.sortedBy { it.order }

    /** 全文引用到的原话 id（去重保序），出处清单按这个顺序编号。 */
    fun sourceIds(): List<String> = sections.flatMap { it.sourceMessageIds }.distinct()

    val isEmpty: Boolean get() = sections.isEmpty()
}

/** 方案里的一节。 */
data class PlanSection(
    val id: String,
    val order: Int,
    val heading: String,
    val body: String,
    /** D4：至少一条真实存在的 message id；一条都没有的段落不许进库 */
    val sourceMessageIds: List<String>,
)

/** 生成中的一节（还没有 id / order）。 */
data class PlanSectionDraft(
    val heading: String,
    val body: String,
    val sourceMessageIds: List<String>,
)

/** 模型吐出来、还没落库的整份方案。 */
data class PlanDraft(
    val title: String,
    val sections: List<PlanSectionDraft>,
)
