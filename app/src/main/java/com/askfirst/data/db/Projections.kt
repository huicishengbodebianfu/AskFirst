package com.askfirst.data.db

/** DAO 查询投影（不是实体，不参与建表）。 */
data class SceneSummaryRow(
    val id: String,
    val name: String,
    val focus: String,
    val builtin: Boolean,
    val questionCount: Int,
    val requiredCount: Int,
)

/** M4 引用候选一行：有单子的会话 + 最新一张单 + 场景名。 */
data class CitationCandidateRow(
    val sheetId: String,
    val sessionId: String,
    val sceneName: String?,
    val summary: String?,
    val intent: String,
    val updatedAt: Long,
)

/** M4 引擎注入摘要（→ SheetDigest）。 */
data class CitationDigestRow(
    val sheetId: String,
    val sceneId: String,
    val summary: String?,
    val createdAt: Long,
)

/** M4 历史详情溯源行。 */
data class CitationEntryRow(
    val sheetId: String,
    val citedSessionId: String,
    val sceneName: String?,
    val summary: String?,
)

/** 历史列表一行：会话 + 场景名 + 轮数/条目数（M2）。 */
data class SessionHistoryRow(
    val id: String,
    val sceneId: String,
    val sceneName: String?,
    val intent: String,
    val summary: String?,
    val status: String,
    val state: String,
    val updatedAt: Long,
    val turnCount: Int,
    val itemCount: Int,
)