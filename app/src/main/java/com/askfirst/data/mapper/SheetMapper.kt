package com.askfirst.data.mapper

import com.askfirst.core.decodeJsonStringList
import com.askfirst.core.encodeJsonArray
import com.askfirst.data.db.entity.SheetEntity
import com.askfirst.data.db.entity.SheetItemEntity
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetItem

fun SheetItemEntity.toDomain(): SheetItem = SheetItem(
    id = id,
    sheetId = sheetId,
    col = col,
    order = order,
    text = text,
    sourceMessageIds = sourceMessageIds.decodeJsonStringList(),
    edited = edited,
)

fun SheetEntity.toDomain(items: List<SheetItemEntity>): Sheet = Sheet(
    id = id,
    sessionId = sessionId,
    summary = summary,
    items = items
        .map { it.toDomain() }
        .groupBy { it.col }
        .mapValues { (_, list) -> list.sortedBy { it.order } },
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Sheet.toEntities(now: Long): Pair<SheetEntity, List<SheetItemEntity>> {
    val sheetEntity = SheetEntity(
        id = id,
        sessionId = sessionId,
        summary = summary,
        createdAt = createdAt.takeIf { it > 0 } ?: now,
        updatedAt = now,
    )
    val items = allItems().map { item ->
        SheetItemEntity(
            id = item.id,
            sheetId = id,
            col = item.col,
            order = item.order,
            text = item.text,
            sourceMessageIds = item.sourceMessageIds.encodeJsonArray(),
            edited = item.edited,
            createdAt = now,
        )
    }
    return sheetEntity to items
}