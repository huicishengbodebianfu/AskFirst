package com.askfirst.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.askfirst.data.db.CitationDigestRow
import com.askfirst.data.db.CitationEntryRow
import com.askfirst.data.db.entity.CitationEntity

@Dao
interface CitationDao {

    @Insert
    suspend fun insertAll(entities: List<CitationEntity>)

    @Query("SELECT * FROM citation WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun ofSession(sessionId: String): List<CitationEntity>

    /** 引擎每轮注入用：会话引用的旧确认单摘要（SheetDigest）。 */
    @Query(
        """
        SELECT sh.id AS sheetId, s.sceneId AS sceneId,
               COALESCE(NULLIF(s.summary, ''), NULLIF(sh.summary, ''), s.intent) AS summary,
               sh.createdAt AS createdAt
        FROM citation c
        JOIN sheet sh ON sh.id = c.sheetId
        JOIN session s ON s.id = sh.sessionId
        WHERE c.sessionId = :sessionId
        ORDER BY c.createdAt ASC
        """,
    )
    suspend fun digestsOf(sessionId: String): List<CitationDigestRow>

    /** 历史详情溯源：这份结论基于哪几份旧结论（被引会话的场景名 + 摘要）。 */
    @Query(
        """
        SELECT c.sheetId AS sheetId, cs.id AS citedSessionId, sc.name AS sceneName,
               COALESCE(NULLIF(cs.summary, ''), NULLIF(sh.summary, ''), cs.intent) AS summary
        FROM citation c
        JOIN sheet sh ON sh.id = c.sheetId
        JOIN session cs ON cs.id = sh.sessionId
        LEFT JOIN scene sc ON sc.id = cs.sceneId
        WHERE c.sessionId = :sessionId
        ORDER BY c.createdAt ASC
        """,
    )
    suspend fun citedEntries(sessionId: String): List<CitationEntryRow>
}
