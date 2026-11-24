package com.askfirst.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.askfirst.data.db.CitationCandidateRow
import com.askfirst.data.db.SessionHistoryRow
import com.askfirst.data.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM session WHERE status = 'open' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun resumable(): SessionEntity?

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM session ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SessionEntity>

    /** M2 历史列表：全量，时间倒序。Room 的 Flow 会在任一相关表变更时重发。 */
    @Query(
        """
        SELECT s.id, s.sceneId, sc.name AS sceneName, s.intent, s.summary, s.status, s.state, s.updatedAt,
               (SELECT COUNT(*) FROM message m WHERE m.sessionId = s.id) AS turnCount,
               (SELECT COUNT(*) FROM sheet_item si JOIN sheet sh ON si.sheetId = sh.id
                 WHERE sh.sessionId = s.id) AS itemCount
        FROM session s LEFT JOIN scene sc ON sc.id = s.sceneId
        ORDER BY s.updatedAt DESC
        """,
    )
    fun observeHistory(): Flow<List<SessionHistoryRow>>

    /** M2 历史列表：按场景筛选。 */
    @Query(
        """
        SELECT s.id, s.sceneId, sc.name AS sceneName, s.intent, s.summary, s.status, s.state, s.updatedAt,
               (SELECT COUNT(*) FROM message m WHERE m.sessionId = s.id) AS turnCount,
               (SELECT COUNT(*) FROM sheet_item si JOIN sheet sh ON si.sheetId = sh.id
                 WHERE sh.sessionId = s.id) AS itemCount
        FROM session s LEFT JOIN scene sc ON sc.id = s.sceneId
        WHERE s.sceneId = :sceneId
        ORDER BY s.updatedAt DESC
        """,
    )
    fun observeHistoryOf(sceneId: String): Flow<List<SessionHistoryRow>>

    @Insert
    suspend fun insert(entity: SessionEntity)

    @Query("UPDATE session SET state = :state, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun patchState(id: String, state: String, status: String, now: Long)

    @Query("UPDATE session SET summary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun patchSummary(id: String, summary: String, now: Long)

    /** M3：这套场景被多少会话用过（删除前的引用检查）。 */
    @Query("SELECT COUNT(*) FROM session WHERE sceneId = :sceneId")
    suspend fun countByScene(sceneId: String): Int

    /**
     * M4 引用候选：有单子的会话，每会话取最新一张单；关键词 LIKE 命中
     * 会话摘要 / 最初那句话 / 单子摘要三处；空关键词 = 最近列表。
     * 方案 §五：不上 FTS，LIKE 全表扫（个人数据几百条量级）。
     */
    @Query(
        """
        SELECT sh.id AS sheetId, s.id AS sessionId, sc.name AS sceneName,
               COALESCE(NULLIF(s.summary, ''), NULLIF(sh.summary, ''), s.intent) AS summary,
               s.intent AS intent, s.updatedAt AS updatedAt
        FROM session s
        JOIN sheet sh ON sh.id = (
            SELECT id FROM sheet WHERE sessionId = s.id ORDER BY createdAt DESC LIMIT 1
        )
        LEFT JOIN scene sc ON sc.id = s.sceneId
        WHERE :kw = ''
           OR s.summary LIKE '%' || :kw || '%'
           OR s.intent LIKE '%' || :kw || '%'
           OR sh.summary LIKE '%' || :kw || '%'
        ORDER BY s.updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchCitationCandidates(kw: String, limit: Int): List<CitationCandidateRow>
}