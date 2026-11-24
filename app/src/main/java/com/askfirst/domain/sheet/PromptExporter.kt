package com.askfirst.domain.sheet

import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.Sheet

/**
 * 「复制成提示词」的模板（方案 §3.2，M1-09 要逐字比对）。
 * 空栏不省略标题，写「（还没定）」——省略会被下游模型当成"没有约束"。
 */
class PromptExporter {

    fun export(intent: String, sheet: Sheet): String = buildString {
        appendLine("我要做这件事：${intent.trim()}")
        appendLine("目标与成功标准：${line(sheet, Cols.GOAL)}")
        appendLine("约束与前提：${line(sheet, Cols.CONSTRAINTS)}")
        appendLine("已选定方案（放弃了 X，因为 Y）：${line(sheet, Cols.OPTIONS)}")
        appendLine("第一步：${line(sheet, Cols.FIRST_STEPS)}")
        appendLine("这些我还没想清楚，别替我决定：${line(sheet, Cols.OPEN_QUESTIONS)}")
        appendLine()
        append("请基于以上内容直接开始「${intent.trim()}」，不要重新问我已回答过的部分。")
    }

    /** 一栏多条时用「；」连起来，保持"每栏一行"的模板形状。 */
    private fun line(sheet: Sheet, col: Int): String {
        val items = sheet.texts(col)
            .filter { it.isNotBlank() }
            // 条目本身常带句号；拼成一行时去掉尾标点，避免出现「。；」
            .map { it.trimEnd('。', '；', ';', '，', ',', ' ') }
        return if (items.isEmpty()) EMPTY else items.joinToString("；")
    }

    companion object {
        const val EMPTY = "（还没定）"
    }
}