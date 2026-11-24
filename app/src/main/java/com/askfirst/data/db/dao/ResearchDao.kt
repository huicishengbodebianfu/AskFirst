package com.askfirst.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.askfirst.data.db.entity.ResearchCacheEntity
import com.askfirst.data.db.entity.ResearchEntity
import com.askfirst.data.db.entity.VerdictEntity

@Dao
interface ResearchDao {

    @Query("SELECT * FROM research WHERE sessionId = :sessionId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun researchBySession(sessionId: String): ResearchEntity?

    @Query("SELECT * FROM research WHERE id = :id")
    suspend fun researchById(id: String): ResearchEntity?

    @Query("SELECT * FROM verdict WHERE researchId = :researchId ORDER BY questionIdx ASC, judgement ASC")
    suspend fun verdictsOf(researchId: String): List<VerdictEntity>

    /** 回写用：这场会话里所有「要进确认单」的判定（只可能是 col2 / col5）。 */
    @Query(
        "SELECT * FROM verdict WHERE sessionId = :sessionId AND appliedTo IN ('col2','col5') " +
            "ORDER BY questionIdx ASC",
    )
    suspend fun appliedVerdicts(sessionId: String): List<VerdictEntity>

    @Query("SELECT * FROM verdict WHERE sessionId = :sessionId ORDER BY questionIdx ASC")
    suspend fun verdictsBySession(sessionId: String): List<VerdictEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResearch(entity: ResearchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVerdicts(entities: List<VerdictEntity>)

    @Query("DELETE FROM verdict WHERE researchId = :researchId")
    suspend fun deleteVerdicts(researchId: String)

    @Query("DELETE FROM research WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** 页面上「这条结论放哪一栏」的切换 —— 只可能是 col2 / col5 / appendix。 */
    @Query("UPDATE verdict SET appliedTo = :target WHERE id = :verdictId")
    suspend fun updateAppliedTo(verdictId: String, target: String)

    @Query("SELECT COUNT(*) FROM research")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM verdict")
    suspend fun verdictCount(): Int

    // ---- 检索缓存（24h） ----

    @Query("SELECT body FROM research_cache WHERE url = :url AND fetchedAt >= :minFetchedAt")
    suspend fun cachedBody(url: String, minFetchedAt: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCache(entity: ResearchCacheEntity)

    @Query("DELETE FROM research_cache WHERE fetchedAt < :minFetchedAt")
    suspend fun evictCacheOlderThan(minFetchedAt: Long)

    @Query("SELECT COUNT(*) FROM research_cache")
    suspend fun cacheCount(): Int
}
