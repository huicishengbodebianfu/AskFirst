package com.askfirst.data.repo

import com.askfirst.core.Clock
import com.askfirst.core.IdPrefix
import com.askfirst.core.IdSource
import com.askfirst.data.db.dao.PlanDao
import com.askfirst.data.db.entity.PlanEntity
import com.askfirst.data.mapper.sectionsToJson
import com.askfirst.data.mapper.toDomain
import com.askfirst.domain.model.Plan
import com.askfirst.domain.model.PlanSection
import com.askfirst.domain.model.PlanSectionDraft

/**
 * M5 方案仓库：一场会话一份方案（重新生成/续写都是整份覆盖同一行），支持逐段删。
 * 段落的 id 每次重写都新生成——段落是渲染产物，稳定 id 没有意义（条目才有）。
 */
class PlanRepository(
    private val dao: PlanDao,
    private val clock: Clock,
    private val ids: IdSource,
) {

    suspend fun of(sessionId: String): Plan? = dao.bySession(sessionId)?.toDomain()

    suspend fun byId(planId: String): Plan? = dao.byId(planId)?.toDomain()

    /** 整份写入（没有就新建、有就覆盖），返回落库后的方案。 */
    suspend fun save(
        sessionId: String,
        sheetId: String,
        title: String,
        sections: List<PlanSectionDraft>,
    ): Plan {
        val existing = dao.bySession(sessionId)
        val now = clock.now()
        val ordered = sections.mapIndexed { index, draft ->
            PlanSection(
                id = ids.newId(IdPrefix.PLAN_SECTION),
                order = index + 1,
                heading = draft.heading.trim(),
                body = draft.body.trim(),
                sourceMessageIds = draft.sourceMessageIds.distinct(),
            )
        }
        val plan = Plan(
            id = existing?.id ?: ids.newId(IdPrefix.PLAN),
            sessionId = sessionId,
            sheetId = sheetId,
            title = title.trim(),
            sections = ordered,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(
            PlanEntity(
                id = plan.id,
                sessionId = plan.sessionId,
                sheetId = plan.sheetId,
                title = plan.title,
                sectionsJson = plan.sectionsToJson(),
                createdAt = plan.createdAt,
                updatedAt = plan.updatedAt,
            ),
        )
        return plan
    }

    /** 逐段删：删掉一段后把顺序重排 1..n，不让页面出现空洞。 */
    suspend fun deleteSection(planId: String, sectionId: String): Plan? {
        val entity = dao.byId(planId) ?: return null
        val kept = entity.toDomain().sorted().filterNot { it.id == sectionId }
        val now = clock.now()
        val reordered = kept.mapIndexed { index, section -> section.copy(order = index + 1) }
        val plan = entity.toDomain().copy(sections = reordered, updatedAt = now)
        dao.upsert(
            PlanEntity(
                id = plan.id,
                sessionId = plan.sessionId,
                sheetId = plan.sheetId,
                title = plan.title,
                sectionsJson = plan.sectionsToJson(),
                createdAt = plan.createdAt,
                updatedAt = now,
            ),
        )
        return plan
    }
}
