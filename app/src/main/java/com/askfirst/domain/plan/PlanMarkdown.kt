package com.askfirst.domain.plan

import com.askfirst.domain.model.Plan
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 方案 → Markdown（落盘/分享用的那一种）。纯 Kotlin，模板逐字稳定（单测锁定）。
 *
 * 段落溯源在这里变成**脚注**：正文里每节末尾挂 `[^3][^7]`，文末「出处」按首次出现顺序编号，
 * 每条把当时的原话引出来——拿到文档的人不用装 App 也能验证这句话是从哪来的。
 */
class PlanMarkdown {

    fun export(plan: Plan, sceneName: String?, turns: List<Turn>, now: Long = System.currentTimeMillis()): String {
        val byId = turns.associateBy { it.id }
        val numbering = LinkedHashMap<String, Int>()
        val sections = plan.sorted()
        sections.forEach { section ->
            section.sourceMessageIds.forEach { id -> numbering.getOrPut(id) { numbering.size + 1 } }
        }

        return buildString {
            appendLine("# ${titleOf(plan)}")
            appendLine()
            appendLine("- 场景：${sceneName ?: "（场景已删除）"}")
            appendLine("- 日期：${formatDate(now)}")
            appendLine("- 出处：这场问答（脚注编号对应文末原话）")
            appendLine()

            sections.forEachIndexed { index, section ->
                appendLine("## ${index + 1}. ${section.heading}")
                appendLine()
                val marks = section.sourceMessageIds
                    .mapNotNull { id -> numbering[id]?.let { "[^$it]" } }
                    .joinToString("")
                appendLine(if (marks.isEmpty()) section.body else "${section.body} $marks")
                appendLine()
            }

            if (numbering.isNotEmpty()) {
                appendLine("## 出处")
                appendLine()
                numbering.entries.sortedBy { it.value }.forEach { (id, number) ->
                    val turn = byId[id]
                    val who = if (turn?.role == TurnRole.USER) "你" else "它"
                    val quote = turn?.text?.let { flatten(it) } ?: "（当时的原话已经找不到了）"
                    appendLine("[^$number] $who：$quote")
                }
                appendLine()
            }

            appendLine("---")
            appendLine("由 AskFirst 生成 · 数据只在本机")
        }
    }

    /** 落盘文件名：`2026-11-24-缓存改造方案.md`（方案 §3.5 的样式），非法文件字符替换掉。 */
    fun fileName(plan: Plan, now: Long = System.currentTimeMillis()): String {
        val base = titleOf(plan)
            .take(24)
            .replace(Regex("""[\\/:*?"<>|\s，。；：？！、（）【】《》"'“”‘’]+"""), "_")
            .trim('_')
            .ifBlank { "方案" }
        return "${formatFileDate(now)}-$base.md"
    }

    fun titleOf(plan: Plan): String = plan.title.trim().ifBlank { "方案" }

    private fun flatten(text: String): String {
        val oneLine = text.replace(Regex("""\s+"""), " ").trim()
        return if (oneLine.length <= 160) oneLine else oneLine.take(160) + "…"
    }

    private fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy年M月d日"))

    private fun formatFileDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
}
