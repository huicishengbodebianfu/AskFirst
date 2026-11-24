package com.askfirst.domain.sheet

import com.askfirst.domain.model.AskSession
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole

/**
 * M2 导出 Markdown：整场会话（五栏 + 问答回看）转一份可归档/可分享的 .md。
 * 纯 Kotlin，模板逐字稳定（单测锁定），**不含 Key、不含内部状态机词**。
 */
class MarkdownExporter {

    fun export(scene: Scene?, session: AskSession, sheet: Sheet?, turns: List<Turn>): String = buildString {
        appendLine("# 思路确认单：${session.intent.trim()}")
        appendLine()
        appendLine("- 场景：${scene?.name ?: "（场景已删除）"}")
        appendLine("- 时间：${formatTime(session.updatedAt)}")
        if (!session.summary.isNullOrBlank()) {
            appendLine("- 摘要：${session.summary.trim()}")
        }
        appendLine()
        appendLine("## 确认单")
        appendLine()
        if (sheet == null || sheet.allItems().isEmpty()) {
            appendLine("（这次没有出单）")
            appendLine()
        } else {
            Cols.ALL.forEach { col ->
                appendLine("### ${Cols.title(col)}")
                appendLine()
                val items = sheet.column(col)
                if (items.isEmpty()) {
                    appendLine("- （还没定）")
                } else {
                    items.forEach { item ->
                        val marker = if (item.edited) "（改）" else ""
                        appendLine("- ${item.text}$marker")
                    }
                }
                appendLine()
            }
        }
        appendLine("## 当时聊了什么")
        appendLine()
        if (turns.isEmpty()) {
            appendLine("（没有问答记录）")
        } else {
            turns.forEach { turn ->
                appendLine(if (turn.role == TurnRole.USER) "**你**：" else "**它**：")
                turn.text.split('\n').filter { it.isNotBlank() }.forEach { line ->
                    appendLine("> $line")
                }
                appendLine()
            }
        }
        append("---")
        appendLine()
        appendLine("由 AskFirst 生成 · 数据只在本机")
    }

    /** 导出文件名：`askfirst-<摘要或开题句>-<sessionId尾8位>.md`，非法文件字符替换掉。 */
    fun fileName(session: AskSession): String {
        val base = (session.summary ?: session.intent)
            .trim()
            .take(24)
            .replace(Regex("""[\\/:*?"<>|\s，。；：？！、（）【】《》]+"""), "_")
            .ifBlank { "session" }
        return "askfirst-$base-${session.id.takeLast(8)}.md"
    }

    private fun formatTime(epochMs: Long): String {
        val zoned = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
        return zoned.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
    }
}
