package com.askfirst.domain.sheet

import com.askfirst.core.Clock
import com.askfirst.core.IdPrefix
import com.askfirst.core.IdSource
import com.askfirst.core.Text
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetItem
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole

/**
 * 把模型出的五栏合并进已有单子（纯函数，可单测）：
 * - **用户手改过的条目必须保留**（`edited = true` 的原样留着，模型不许覆盖）
 * - 其余条目以模型这次的结果为准（同栏同文去重、每栏最多 [SheetGrower.MAX_ITEMS_PER_COLUMN] 条）
 * - 每条都要有出处：按"和他的哪句回答最像（二字组重合最多）"配，实在配不上就用最后一条回答
 */
class SheetAssembler(
    private val ids: IdSource,
    private val clock: Clock,
) {

    fun merge(
        existing: Sheet,
        payload: SheetPayload,
        turns: List<Turn>,
    ): Sheet {
        val userTurns = turns.filter { it.role == TurnRole.USER && it.text.isNotBlank() }
        val editedItems = existing.allItems().filter { it.edited }
        val now = clock.now()
        val merged = mutableMapOf<Int, List<SheetItem>>()

        Cols.ALL.forEach { col ->
            val keepEdited = editedItems.filter { it.col == col }.sortedBy { it.order }
            val generatedTexts = payload.normalizedColumns()[col].orEmpty()
            val generated = generatedTexts
                .filter { text -> keepEdited.none { Text.normalize(it.text) == Text.normalize(text) } }
                .take((SheetGrower.MAX_ITEMS_PER_COLUMN - keepEdited.size).coerceAtLeast(0))
                .mapIndexed { index, text ->
                    SheetItem(
                        id = ids.newId(IdPrefix.ITEM),
                        sheetId = existing.id,
                        col = col,
                        order = keepEdited.size + index,
                        text = Text.normalize(text),
                        sourceMessageIds = matchSource(text, userTurns),
                        edited = false,
                    )
                }
            val combined = (keepEdited + generated).sortedBy { it.order }
            if (combined.isNotEmpty()) merged[col] = combined
        }

        return existing.copy(
            summary = payload.summary.trim().takeIf { it.isNotBlank() } ?: existing.summary,
            items = merged,
            updatedAt = now,
        )
    }

    /** 二字组重合度最高的那条回答就是出处；都不沾边时退回到最后一条回答（可追溯，不编造）。 */
    fun matchSource(text: String, userTurns: List<Turn>): List<String> {
        if (userTurns.isEmpty()) return emptyList()
        val target = bigrams(text)
        val best = userTurns
            .map { turn -> turn to (bigrams(turn.text) intersect target).size }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
        return listOf((best?.first ?: userTurns.last()).id)
    }

    private fun bigrams(text: String): Set<String> {
        val compact = text.filter { it.isLetterOrDigit() }
        if (compact.length < 2) return emptySet()
        return (0 until compact.length - 1).map { compact.substring(it, it + 2) }.toSet()
    }
}