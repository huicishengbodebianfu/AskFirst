package com.askfirst.domain.model

/** 引用选择页的一行候选：一个「有单子的会话」的最新确认单。 */
data class CitationCandidate(
    val sheetId: String,
    val sessionId: String,
    val sceneName: String,
    /** 展示用摘要：会话摘要优先，退到单子摘要，再退到最初那句话。 */
    val summary: String,
    val intent: String,
    val updatedAt: Long,
)

/** 历史详情页溯源行：「这份结论基于哪几份旧结论」。 */
data class CitationEntry(
    val sheetId: String,
    val sessionId: String,
    val sceneName: String,
    val summary: String,
)
