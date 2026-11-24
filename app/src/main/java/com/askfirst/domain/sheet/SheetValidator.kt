package com.askfirst.domain.sheet

import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetCheck
import com.askfirst.domain.model.SheetProblem

/**
 * 出单门（M1-10）。**本地判据，不依赖模型**：
 * - 第 4 栏 < 2 条动作 → blocking（"出不了第一步，说明还没到能开工的程度"）
 * - 有空栏 → warning（不拦，但要告诉用户）
 * - 有条目没有出处 → blocking（D4 的反向检查，正常不该出现）
 */
class SheetValidator {

    fun check(sheet: Sheet): SheetCheck {
        val blocking = mutableListOf<SheetProblem>()
        val warnings = mutableListOf<SheetProblem>()

        val steps = sheet.column(Cols.FIRST_STEPS).filter { it.text.isNotBlank() }
        if (steps.size < 2) blocking += SheetProblem.NotEnoughFirstSteps(steps.size)

        // 条数够了、但动作本身带着没定的前提（"卡点未确认""形态未定"）——这种单子也开不了工
        val hedged = steps.filter { step -> HEDGE_MARKERS.any { step.text.contains(it) } }
        if (steps.size >= 2 && hedged.isNotEmpty()) {
            warnings += SheetProblem.HedgedFirstSteps(hedged.size)
        }

        sheet.allItems()
            .filter { it.sourceMessageIds.isEmpty() }
            .forEach { blocking += SheetProblem.UnsourcedItem(it.id) }

        Cols.ALL.forEach { col ->
            if (sheet.isEmptyColumn(col)) warnings += SheetProblem.EmptyColumn(col)
        }

        return SheetCheck(blocking = blocking, warnings = warnings)
    }

    companion object {
        /** 这些词出现在"第一步动作"里，说明那个动作还悬着。 */
        val HEDGE_MARKERS = listOf("未定", "未确认", "不确定", "无法判断", "还无法", "待确认", "尚未明确", "尚不明确")
    }
}