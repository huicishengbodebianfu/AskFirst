package com.askfirst.data.repo

import com.askfirst.core.Clock
import com.askfirst.core.IdPrefix
import com.askfirst.core.IdSource
import com.askfirst.data.db.dao.CitationDao
import com.askfirst.data.db.dao.SessionDao
import com.askfirst.domain.citation.CitationPolicy
import com.askfirst.domain.model.CitationCandidate
import com.askfirst.domain.model.CitationEntry
import com.askfirst.domain.model.SheetDigest

/**
 * M4 跨场景引用：搜索 / 勾选入库 / 引擎摘要 / 详情溯源。
 * 上限 3 份在 [CitationPolicy.cap] 双拦（UI 之外门面再拦一次）。
 */
class CitationRepository(
    private val citationDao: CitationDao,
    private val sessionDao: SessionDao,
    private val clock: Clock,
    private val ids: IdSource,
) {

    /** 关键词搜候选（空 = 最近 [CitationPolicy.RECENT_LIMIT] 份）。关键词须先过 sanitize。 */
    suspend fun search(keyword: String): List<CitationCandidate> {
        val kw = CitationPolicy.sanitizeKeyword(keyword)
        return sessionDao.searchCitationCandidates(kw, CitationPolicy.RECENT_LIMIT)
            .map { row ->
                CitationCandidate(
                    sheetId = row.sheetId,
                    sessionId = row.sessionId,
                    sceneName = row.sceneName ?: "（场景已删）",
                    summary = CitationPolicy.summaryText(row.summary, "（这份旧结论没有摘要）"),
                    intent = row.intent,
                    updatedAt = row.updatedAt,
                )
            }
    }

    /** 开新会话时落引用关系（≤3 份，超出截断）。 */
    suspend fun cite(sessionId: String, sheetIds: List<String>) {
        val capped = CitationPolicy.cap(sheetIds.distinct())
        if (capped.isEmpty()) return
        val now = clock.now()
        citationDao.insertAll(
            capped.map { sheetId ->
                com.askfirst.data.db.entity.CitationEntity(
                    id = ids.newId(IdPrefix.CITATION),
                    sessionId = sessionId,
                    sheetId = sheetId,
                    createdAt = now,
                )
            },
        )
    }

    /** 引擎每轮注入的摘要（SheetDigest：sceneId + summary，不给全文）。 */
    suspend fun digestsOf(sessionId: String): List<SheetDigest> =
        citationDao.digestsOf(sessionId).map { row ->
            SheetDigest(
                sheetId = row.sheetId,
                sceneId = row.sceneId,
                summary = CitationPolicy.summaryText(row.summary),
                createdAt = row.createdAt,
            )
        }

    suspend fun countOf(sessionId: String): Int = citationDao.ofSession(sessionId).size

    /** 历史详情溯源行。 */
    suspend fun citedEntries(sessionId: String): List<CitationEntry> =
        citationDao.citedEntries(sessionId).map { row ->
            CitationEntry(
                sheetId = row.sheetId,
                sessionId = row.citedSessionId,
                sceneName = row.sceneName ?: "（场景已删）",
                summary = CitationPolicy.summaryText(row.summary, "（没有摘要）"),
            )
        }
}
