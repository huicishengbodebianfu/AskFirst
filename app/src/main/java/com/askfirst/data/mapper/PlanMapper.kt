package com.askfirst.data.mapper

import com.askfirst.core.AppJson
import com.askfirst.data.db.entity.PlanEntity
import com.askfirst.domain.model.Plan
import com.askfirst.domain.model.PlanSection
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 方案段落 ↔ JSON（存 plan.sectionsJson）。**解码失败就当没有方案**：
 * 方案是确认单的渲染产物，重生成一次就有；宁可空着，也不许拿半截 JSON 崩掉历史页。
 */
@Serializable
internal data class PlanSectionJson(
    val id: String = "",
    val order: Int = 0,
    val heading: String = "",
    val body: String = "",
    val sources: List<String> = emptyList(),
)

private val sectionList = ListSerializer(PlanSectionJson.serializer())

internal fun Plan.sectionsToJson(): String =
    AppJson.encodeToString(sectionList, sorted().map { section ->
        PlanSectionJson(
            id = section.id,
            order = section.order,
            heading = section.heading,
            body = section.body,
            sources = section.sourceMessageIds,
        )
    })

internal fun sectionsFromJson(json: String): List<PlanSection> =
    runCatching { AppJson.decodeFromString(sectionList, json) }
        .getOrElse { emptyList() }
        .map { row ->
            PlanSection(
                id = row.id,
                order = row.order,
                heading = row.heading,
                body = row.body,
                sourceMessageIds = row.sources,
            )
        }

fun PlanEntity.toDomain(): Plan = Plan(
    id = id,
    sessionId = sessionId,
    sheetId = sheetId,
    title = title,
    sections = sectionsFromJson(sectionsJson).sortedBy { it.order },
    createdAt = createdAt,
    updatedAt = updatedAt,
)
