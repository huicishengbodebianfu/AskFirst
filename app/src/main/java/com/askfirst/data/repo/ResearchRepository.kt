package com.askfirst.data.repo

import androidx.room.withTransaction
import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.core.Clock
import com.askfirst.core.Logx
import com.askfirst.data.db.AskFirstDatabase
import com.askfirst.data.db.dao.ResearchDao
import com.askfirst.data.db.entity.ResearchCacheEntity
import com.askfirst.data.mapper.toDomain
import com.askfirst.data.mapper.toEntity
import com.askfirst.domain.model.AppliedTo
import com.askfirst.domain.model.Research
import com.askfirst.domain.model.Verdict
import com.askfirst.research.EvidenceCache
import kotlinx.coroutines.CancellationException

/**
 * 调研的读写（M6）。
 *
 * 两条策略：
 * 1. **一场会话只留最新一次调研**：重查覆盖旧记录（确认单才是真相来源，证据是它的体检报告，
 *    留五份只会让人分不清哪份是准的——和 M5 的方案同一个判断）；
 * 2. 顺带当检索缓存的门面（[EvidenceCache]）：24h 内同一个 URL 不再打网络。
 */
class ResearchRepository(
    private val db: AskFirstDatabase,
    private val dao: ResearchDao,
    private val clock: Clock,
    private val log: Logx,
) : EvidenceCache {

    suspend fun latest(sessionId: String): Research? {
        val row = dao.researchBySession(sessionId) ?: return null
        return row.toDomain(dao.verdictsOf(row.id))
    }

    suspend fun byId(id: String): Research? {
        val row = dao.researchById(id) ?: return null
        return row.toDomain(dao.verdictsOf(row.id))
    }

    /**
     * 整份覆盖保存（先删同会话的旧记录，再写新的）。
     *
     * 三处必须一起做：**删除 + 写 research + 写 verdict 在一个事务里**，否则用户保存途中
     * 退出调研页（协程被取消）会留下"旧报告已删、新报告没写"的空档，
     * 而调用方拿到的还是成功（docs/28 P1-5）。真失败必须返回 Err，不许拿残值冒充成功。
     */
    suspend fun save(research: Research): AppResult<Research> {
        val now = clock.now()
        return try {
            db.withTransaction {
                dao.deleteBySession(research.sessionId)
                dao.upsertResearch(research.toEntity())
                if (research.verdicts.isNotEmpty()) {
                    dao.upsertVerdicts(
                        research.verdicts.map { it.toEntity(researchId = research.id, sessionId = research.sessionId, createdAt = now) },
                    )
                }
            }
            AppResult.Ok(research)
        } catch (cancelled: CancellationException) {
            // 取消不是失败：原样抛出去，让上层知道这次没保存（也不许吞）
            throw cancelled
        } catch (t: Throwable) {
            log.e(TAG, "保存调研失败", t)
            AppResult.Err(
                AppError.Storage(
                    "这次的调研没存下来（${t.message ?: t::class.java.simpleName}）。再查一次就会重新出一份。",
                    t,
                ),
            )
        }
    }

    /** 回写确认单用的那几条（col2 / col5）。 */
    suspend fun appliedVerdicts(sessionId: String): List<Verdict> =
        dao.appliedVerdicts(sessionId).map { it.toDomain() }

    /** 页面上「这条结论放哪一栏」：只允许 col2 / col5 / appendix（第 3 栏没有入口）。 */
    suspend fun moveVerdict(verdictId: String, target: AppliedTo): AppResult<Unit> = try {
        dao.updateAppliedTo(verdictId, target.db)
        AppResult.Ok(Unit)
    } catch (t: Throwable) {
        log.e(TAG, "切换结论落点失败", t)
        AppResult.Err(com.askfirst.core.AppError.Storage("这条结论没存下来", t))
    }

    suspend fun count(): Int = dao.count()

    // ---- EvidenceCache ----

    override suspend fun get(url: String): String? = try {
        dao.cachedBody(url, clock.now() - CACHE_TTL_MS)
    } catch (t: Throwable) {
        log.e(TAG, "读检索缓存失败（当成没命中）", t)
        null
    }

    override suspend fun put(url: String, body: String) {
        try {
            dao.putCache(ResearchCacheEntity(url = url, body = body, fetchedAt = clock.now()))
        } catch (t: Throwable) {
            log.e(TAG, "写检索缓存失败（不影响这次调研）", t)
        }
    }

    /** 每次调研开始时顺手清过期行（不做定时任务，手机上没那个必要）。 */
    suspend fun evictExpiredCache() {
        try {
            dao.evictCacheOlderThan(clock.now() - CACHE_TTL_MS)
        } catch (t: Throwable) {
            log.e(TAG, "清检索缓存失败", t)
        }
    }

    companion object {
        private const val TAG = "ResearchRepository"

        /** 方案 §3.6.3 第 5 条：按查询关键词缓存 24h */
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
