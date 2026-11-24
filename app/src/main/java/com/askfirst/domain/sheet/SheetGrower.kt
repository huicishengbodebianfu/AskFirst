package com.askfirst.domain.sheet

import com.askfirst.core.IdPrefix
import com.askfirst.core.IdSource
import com.askfirst.core.Text
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetDelta
import com.askfirst.domain.model.SheetItem

/**
 * 确认单的"实时生长"（改动 #6）。纯函数：只算**新增条目**，落库是仓库的事。
 *
 * 三条硬规则（与 docs/03 D4 一致）：
 * 1. 没有 `sourceMessageIds` 的条目一律丢弃（不编造的执行层保证）；
 * 2. 同栏同文去重（normalize 后比较）；
 * 3. 每栏上限 8 条，超了丢弃（手机上读得动的上限）。
 */
class SheetGrower(private val ids: IdSource) {

    fun newItems(sheet: Sheet, deltas: List<SheetDelta>, now: Long = 0L): List<SheetItem> {
        val existing = sheet.allItems().map { it.col to Text.normalize(it.text) }.toSet()
        val nextOrder = sheet.items
            .mapValues { (_, items) -> (items.maxOfOrNull { it.order } ?: -1) + 1 }
            .toMutableMap()
        val counts = sheet.items.mapValues { (_, items) -> items.size }.toMutableMap()
        val seen = existing.toMutableSet()
        val result = mutableListOf<SheetItem>()

        deltas.forEach { delta ->
            val text = Text.normalize(delta.text)
            if (text.isBlank()) return@forEach
            if (delta.col !in 1..5) return@forEach
            if (delta.sourceMessageIds.isEmpty()) return@forEach
            val key = delta.col to text
            if (key in seen) return@forEach
            val count = counts[delta.col] ?: 0
            if (count >= MAX_ITEMS_PER_COLUMN) return@forEach

            val order = nextOrder.getOrPut(delta.col) { 0 }
            nextOrder[delta.col] = order + 1
            counts[delta.col] = count + 1
            seen += key
            result += SheetItem(
                id = ids.newId(IdPrefix.ITEM),
                sheetId = sheet.id,
                col = delta.col,
                order = order,
                text = text,
                sourceMessageIds = delta.sourceMessageIds.distinct(),
                edited = false,
            )
        }
        return result
    }

    /** 合并后（未落库）的完整单子，给 UI 预览用；`now` 只是 updatedAt。 */
    fun grow(sheet: Sheet, deltas: List<SheetDelta>, now: Long): Sheet {
        val added = newItems(sheet, deltas, now)
        if (added.isEmpty()) return sheet
        val merged = sheet.items.toMutableMap()
        added.groupBy { it.col }.forEach { (col, items) ->
            merged[col] = (merged[col].orEmpty() + items).sortedBy { it.order }
        }
        return sheet.copy(items = merged, updatedAt = now)
    }

    companion object {
        const val MAX_ITEMS_PER_COLUMN = 8
    }
}