package com.askfirst.data.repo

import androidx.room.withTransaction
import com.askfirst.core.Clock
import com.askfirst.core.IdSource
import com.askfirst.core.Logx
import com.askfirst.core.Text
import com.askfirst.core.encodeJsonArray
import com.askfirst.data.db.AskFirstDatabase
import com.askfirst.data.db.dao.SheetDao
import com.askfirst.data.db.entity.SheetItemEntity
import com.askfirst.data.mapper.toDomain
import com.askfirst.data.mapper.toEntities
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetDelta
import com.askfirst.domain.model.SheetItem
import com.askfirst.domain.sheet.SheetGrower

/**
 * 确认单仓库。条目新增规则在 [SheetGrower]（纯函数、可单测），这里只负责落库。
 * D4 铁律（无出处不入库）由 grower 执行。
 */
class SheetRepository(
    private val db: AskFirstDatabase,
    private val sheetDao: SheetDao,
    private val clock: Clock,
    private val ids: IdSource,
    private val grower: SheetGrower,
    private val log: Logx,
) {

    suspend fun latest(sessionId: String): Sheet? {
        val sheet = sheetDao.latestSheetOf(sessionId) ?: return null
        return sheet.toDomain(sheetDao.itemsOf(sheet.id))
    }

    suspend fun byId(sheetId: String): Sheet? {
        val sheet = sheetDao.sheetById(sheetId) ?: return null
        return sheet.toDomain(sheetDao.itemsOf(sheet.id))
    }

    /** 开一张空单（会话中途预览用），已有则取最新。 */
    suspend fun ensureSheet(sessionId: String, summary: String = ""): Sheet {
        val existing = latest(sessionId)
        if (existing != null) return existing
        val now = clock.now()
        val sheet = Sheet(
            id = ids.newId(com.askfirst.core.IdPrefix.SHEET),
            sessionId = sessionId,
            summary = summary,
            items = emptyMap(),
            createdAt = now,
            updatedAt = now,
        )
        val (entity, items) = sheet.toEntities(now)
        db.withTransaction {
            sheetDao.insertSheet(entity)
            if (items.isNotEmpty()) sheetDao.upsertItems(items)
        }
        return sheet
    }

    /** 把一轮的 sheet_delta 合进单子；返回最新的单子。 */
    suspend fun appendItems(sheetId: String, deltas: List<SheetDelta>): Sheet {
        val sheet = byId(sheetId) ?: error("sheet 不存在: $sheetId")
        if (deltas.isEmpty()) return sheet
        val now = clock.now()
        val newItems = grower.newItems(sheet, deltas, now)
        if (newItems.isEmpty()) {
            log.d("SheetRepo", "本轮没有可入库条目（去重/无出处/超上限）")
            return sheet
        }
        val entities = newItems.map { item ->
            SheetItemEntity(
                id = item.id,
                sheetId = item.sheetId,
                col = item.col,
                order = item.order,
                text = item.text,
                sourceMessageIds = item.sourceMessageIds.encodeJsonArray(),
                edited = item.edited,
                createdAt = now,
            )
        }
        db.withTransaction {
            sheetDao.upsertItems(entities)
            sheetDao.patchSummary(sheetId, sheet.summary, now)
        }
        return byId(sheetId) ?: sheet
    }

    suspend fun editItem(itemId: String, text: String) {
        sheetDao.editItem(itemId, Text.normalize(text))
    }

    suspend fun itemById(itemId: String): SheetItem? = sheetDao.itemById(itemId)?.toDomain()

    suspend fun updateSummary(sheetId: String, summary: String) {
        sheetDao.patchSummary(sheetId, summary, clock.now())
    }

    suspend fun clearColumn(sheetId: String, col: Int) {
        sheetDao.clearColumn(sheetId, col)
    }

    /**
     * 出单/重新出单：非编辑条目整批替换，编辑过的条目保留（用户在单子上的手工修改不许被覆盖）。
     */
    suspend fun replaceGenerated(sheetId: String, items: List<SheetItem>, summary: String): Sheet {
        val now = clock.now()
        val entities = items.map { item ->
            SheetItemEntity(
                id = item.id,
                sheetId = item.sheetId,
                col = item.col,
                order = item.order,
                text = item.text,
                sourceMessageIds = item.sourceMessageIds.encodeJsonArray(),
                edited = item.edited,
                createdAt = now,
            )
        }
        db.withTransaction {
            sheetDao.deleteUnedited(sheetId)
            if (entities.isNotEmpty()) sheetDao.upsertItems(entities)
            sheetDao.patchSummary(sheetId, summary, now)
        }
        return byId(sheetId) ?: error("出单后读不到 sheet: $sheetId")
    }
}