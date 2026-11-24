package com.askfirst.domain.prompt

import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.model.PlanSection
import com.askfirst.domain.model.PrecisionIssue
import com.askfirst.domain.model.PrecisionKind
import com.askfirst.domain.model.Question
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetDigest
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import com.askfirst.remote.ChatMessage

/**
 * 上下文组装器（改动 #10：引擎 + 模板 + 出单规则**在同一个上下文里**，不分层委托）。
 * 六段固定顺序，缺段写"（空）"。纯 Kotlin，可单测。
 */
class PromptBuilder(
    private val engineSystemPrompt: String,
    private val sheetSystemPrompt: String,
    private val promptVersion: String = "1.0.0",
    private val maxTranscriptChars: Int = 3000,
    private val maxQuestionsInContext: Int = 12,
    private val recentTurns: Int = 6,
    /** M5 方案文档的 system prompt（assets/prompts/plan_system.md） */
    private val planSystemPrompt: String = "",
    /** M6 立题的 system prompt（assets/prompts/research_questions.md） */
    private val researchQuestionsSystemPrompt: String = "",
    /** M6 对照的 system prompt（assets/prompts/research_verdict.md） */
    private val researchVerdictSystemPrompt: String = "",
    /** 方案要看整场（含早先的边界回答），给得比提问流宽 */
    private val maxPlanTranscriptChars: Int = 6000,
    /** 调研只给「这场的回答摘要」：证据对照用不着每一句，省 token */
    private val maxResearchTranscriptChars: Int = 3000,
    /** 一次喂给对照的证据条数上限（手机端内存与 token 都有限） */
    private val maxEvidenceInContext: Int = 40,
) {

    fun engineMessages(context: EngineContext): List<ChatMessage> = listOf(
        ChatMessage.system(engineSystemPrompt.trimEnd() + "\n\nprompt_version=$promptVersion"),
        ChatMessage.user(assembleEngineBlock(context)),
    )

    fun sheetMessages(context: SheetContext): List<ChatMessage> = listOf(
        ChatMessage.system(sheetSystemPrompt.trimEnd() + "\n\nprompt_version=$promptVersion"),
        ChatMessage.user(assembleSheetBlock(context)),
    )

    fun planMessages(context: PlanContext): List<ChatMessage> = listOf(
        ChatMessage.system(planSystemPrompt.trimEnd() + "\n\nprompt_version=$promptVersion"),
        ChatMessage.user(assemblePlanBlock(context)),
    )

    /** M6 立题：场景 + 五栏 + 这场的原话摘要，让模型出 3~6 个可检索问题。 */
    fun researchQuestionMessages(context: ResearchQuestionContext): List<ChatMessage> = listOf(
        ChatMessage.system(researchQuestionsSystemPrompt.trimEnd() + "\n\nprompt_version=$promptVersion"),
        ChatMessage.user(assembleResearchQuestionBlock(context)),
    )

    /** M6 对照：问题清单 + 证据清单（带 id）+ 五栏，让模型只许对着证据说话。 */
    fun researchVerdictMessages(input: com.askfirst.domain.research.JudgeInput): List<ChatMessage> = listOf(
        ChatMessage.system(researchVerdictSystemPrompt.trimEnd() + "\n\nprompt_version=$promptVersion"),
        ChatMessage.user(assembleResearchVerdictBlock(input, maxEvidenceInContext)),
    )

    // ---------------------------------------------------------------- engine

    private fun assembleEngineBlock(context: EngineContext): String = buildString {
        appendLine("## 用户最初那句话")
        appendLine(context.intent.ifBlank { "（空）" })
        appendLine()

        appendLine("## 当前场景：${context.scene.name}")
        appendLine("关注点：${context.scene.focus}")
        appendLine("问题清单（id | 必问/选问 | 是否条件题 | 问题 | 为什么问）：")
        appendLine(questionLines(context))
        appendLine()

        appendLine("## 已覆盖问题 id")
        appendLine(context.covered.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "（无）")
        appendLine()

        appendLine("## 会话全文")
        appendLine(transcript(context.turns))
        appendLine()

        appendLine("## 确认单草稿")
        appendLine(draftLines(context.draftColumns))
        appendLine()

        appendLine("## 当前状态（内部用，不许在回答里出现这些词）")
        appendLine("state = ${context.state.api}")
        appendLine(issueLines(context.pendingIssues))

        if (context.referencedSheets.isNotEmpty()) {
            appendLine()
            appendLine("## 引用的旧确认单摘要（只作背景，不许拿它替他回答）")
            context.referencedSheets.forEach { sheet ->
                appendLine("- [${sheet.sceneId}] ${sheet.summary}")
            }
        }
    }

    private fun questionLines(context: EngineContext): String {
        val haystack = buildString {
            append(context.intent)
            context.turns.forEach { append(' ').append(it.text) }
        }
        val selected = selectQuestions(context.scene.questions, context.covered, haystack)
        if (selected.isEmpty()) return "（这个场景没有任何问题）"
        return selected.joinToString("\n") { question ->
            val required = if (question.required) "必问" else "选问"
            val conditional = if (question.trigger == null) "" else "条件题"
            "${question.id} | $required | $conditional | ${question.text} | ${question.why}"
        }
    }

    /** 必问优先，其次条件题命中，最后选问；已覆盖的剔除。 */
    fun selectQuestions(questions: List<Question>, covered: Set<String>, haystack: String): List<Question> =
        questions
            .asSequence()
            .filterNot { it.id in covered }
            .filter { it.trigger == null || it.matchesTrigger(haystack) }
            .sortedWith(
                compareByDescending<Question> { it.required }
                    .thenByDescending { it.trigger != null }
                    .thenBy { it.order },
            )
            .take(maxQuestionsInContext)
            .sortedBy { it.order }
            .toList()

    private fun transcript(turns: List<Turn>): String {
        if (turns.isEmpty()) return "（空）"
        val recent = turns.takeLast(recentTurns)
        var omitted = turns.size > recent.size
        var lines = recent.map { turn ->
            val who = if (turn.role == TurnRole.USER) "U" else "A"
            "[$who] ${turn.text.trim()}"
        }
        var text = lines.joinToString("\n")
        while (text.length > maxTranscriptChars && lines.size > 1) {
            lines = lines.drop(1)
            omitted = true
            text = lines.joinToString("\n")
        }
        if (text.length > maxTranscriptChars) {
            text = text.takeLast(maxTranscriptChars)
            omitted = true
        }
        return if (omitted) "（更早的对话已省略）\n$text" else text
    }

    private fun draftLines(draft: Map<Int, List<String>>): String = buildString {
        Cols.ALL.forEachIndexed { index, col ->
            if (index > 0) appendLine()
            appendLine("$col ${Cols.title(col)}：")
            val items = draft[col].orEmpty()
            if (items.isEmpty()) {
                append(" （空）")
            } else {
                items.forEachIndexed { itemIndex, item ->
                    if (itemIndex > 0) appendLine()
                    append(" - $item")
                }
            }
        }
    }

    private fun issueLines(issues: List<PrecisionIssue>): String {
        if (issues.isEmpty()) return "待处理的精确度缺口：（无）"
        return buildString {
            appendLine("待处理的精确度缺口：")
            issues.forEach { issue ->
                appendLine("- ${issue.kind.api}: ${issue.detail}")
                if (issue.quoted.isNotBlank()) appendLine("  原话：「${issue.quoted}」")
            }
        }.trimEnd()
    }

    // ----------------------------------------------------------------- sheet

    private fun assembleSheetBlock(context: SheetContext): String = buildString {
        appendLine("## 用户最初那句话")
        appendLine(context.intent.ifBlank { "（空）" })
        appendLine()
        appendLine("## 场景：${context.scene.name}（${context.scene.focus}）")
        appendLine()
        appendLine("## 会话全文（用他的话填单，不要用你的知识）")
        appendLine(transcript(context.turns))
        appendLine()
        appendLine("## 已实时填进去的条目（可以合并同义，但不许丢条目里的数字/日期/否定式）")
        appendLine(draftLines(context.draftColumns))
    }

    // ------------------------------------------------------------------ plan

    private fun assemblePlanBlock(context: PlanContext): String = buildString {
        appendLine("## 用户最初那句话")
        appendLine(context.intent.ifBlank { "（空）" })
        appendLine()
        appendLine("## 场景：${context.scene.name}（${context.scene.focus}）")
        appendLine()
        appendLine("## 文档骨架（按这个顺序写，heading 与下面逐字一致）")
        context.skeleton.forEachIndexed { index, heading -> appendLine("${index + 1}. $heading") }
        appendLine()
        appendLine("## 五栏确认单（他确认过的结论，正文的事实要以它为准）")
        appendLine(sheetLines(context.sheet))
        appendLine()
        appendLine("## 本场问答（每行 [消息 id] 角色：原话；sources 只能从这里挑，不许编）")
        appendLine(planTranscript(context.turns))

        if (context.existing.isNotEmpty()) {
            appendLine()
            appendLine("## 已经写好的章节（接着写：这些**不要重写**，内容保持原样）")
            context.existing.forEach { appendLine("- ${it.heading}") }
            appendLine()
            appendLine("## 这一次只补下面这几节（同样按骨架顺序、同样一字不差）")
            context.missingHeadings.forEachIndexed { index, heading -> appendLine("${index + 1}. $heading") }
        }
    }

    private fun sheetLines(sheet: Sheet?): String {
        if (sheet == null || sheet.allItems().isEmpty()) return "（还没出单，只能靠问答）"
        return buildString {
            Cols.ALL.forEachIndexed { index, col ->
                if (index > 0) appendLine()
                appendLine("$col ${Cols.title(col)}：")
                val items = sheet.column(col)
                if (items.isEmpty()) {
                    append(" - （空）")
                } else {
                    items.forEachIndexed { itemIndex, item ->
                        if (itemIndex > 0) appendLine()
                        val sources = if (item.sourceMessageIds.isEmpty()) "" else
                            "（出处 ${item.sourceMessageIds.joinToString(", ")}）"
                        append(" - ${item.text}$sources")
                    }
                }
            }
        }
    }

    private fun planTranscript(turns: List<Turn>): String {
        if (turns.isEmpty()) return "（空）"
        val text = turns.joinToString("\n") { turn ->
            val who = if (turn.role == TurnRole.USER) "你" else "它"
            "[${turn.id}] $who：${turn.text.trim()}"
        }
        return if (text.length <= maxPlanTranscriptChars) {
            text
        } else {
            "（更早的对话已省略）\n" + text.takeLast(maxPlanTranscriptChars)
        }
    }

    // --------------------------------------------------------------- research

    private fun assembleResearchQuestionBlock(context: ResearchQuestionContext): String = buildString {
        appendLine("## 场景：${context.scene.name}（${context.scene.focus}）")
        appendLine()
        appendLine("## 他最初那句话")
        appendLine(context.intent.ifBlank { "（空）" })
        appendLine()
        appendLine("## 五栏确认单（他已经确认过的结论，立题要从这里出发）")
        appendLine(sheetLines(context.sheet))
        appendLine()
        appendLine("## 这场问答的回答摘要（每行 [消息 id] 角色：原话）")
        appendLine(researchTranscript(context.turns))
        if (context.planHeadings.isNotEmpty()) {
            appendLine()
            appendLine("## 已经写出的方案章节（只用来判断该查什么，不要评价它）")
            context.planHeadings.forEachIndexed { index, heading -> appendLine("${index + 1}. $heading") }
        }
    }

    private fun assembleResearchVerdictBlock(input: com.askfirst.domain.research.JudgeInput, maxEvidence: Int): String =
        buildString {
            appendLine("## 他这次要做的事")
            appendLine(input.intent.ifBlank { "（空）" })
            appendLine()
            if (!input.sceneName.isNullOrBlank()) {
                appendLine("## 场景：${input.sceneName}")
                appendLine()
            }
            appendLine("## 要对照的问题（question 就是这个编号）")
            input.questions.forEach { question ->
                appendLine("${question.idx}. ${question.text}（关键词：${question.keywords.joinToString(" / ")}）")
            }
            appendLine()
            appendLine("## 这次查到的证据（evidence 只能填这里的 id，一个都不许编）")
            if (input.evidence.isEmpty()) {
                appendLine("（一条都没有）")
            } else {
                input.evidence.take(maxEvidence).forEach { evidence ->
                    val stars = if (evidence.stars > 0) " ★${evidence.stars}" else ""
                    val repo = evidence.repoFullName?.let { " $it" }.orEmpty()
                    appendLine("[${evidence.id}] ${evidence.kind.db}$repo$stars ${evidence.title}")
                    appendLine("  url: ${evidence.url}")
                    if (evidence.snippet.isNotBlank()) appendLine("  内容: ${evidence.snippet}")
                }
                if (input.evidence.size > maxEvidence) {
                    appendLine("（还有 ${input.evidence.size - maxEvidence} 条没列出来，已省略）")
                }
            }
            appendLine()
            appendLine("## 五栏确认单（他确认过的结论，判断「他要做什么」以此为准）")
            appendLine(sheetLines(input.sheet))
            appendLine()
            appendLine("## 输出纪律")
            appendLine("- 每条判定写清「这意味着什么」，30~80 字，人话，坏消息直说")
            appendLine("- evidence 只能引用上面出现过的 id；没有证据支撑就不要输出这条判定")
            appendLine("- 反模式必须逐字照抄 issue 原话到 quote，并把该 issue 的 URL 填进 quote_url")
            appendLine("- 不许给选型建议、不许说「别人都这么做所以你也这么做」")
            if (!input.allowUnexplored) {
                appendLine("- 这次没开换词复核：不要输出 unexplored（无人区），没命中的问题留给机械层写「未取证」")
            }
        }

    private fun researchTranscript(turns: List<Turn>): String {
        if (turns.isEmpty()) return "（空）"
        val text = turns.joinToString("\n") { turn ->
            val who = if (turn.role == TurnRole.USER) "你" else "它"
            "[${turn.id}] $who：${turn.text.replace(Regex("\\s+"), " ").trim()}"
        }
        return if (text.length <= maxResearchTranscriptChars) {
            text
        } else {
            "（更早的对话已省略）\n" + text.takeLast(maxResearchTranscriptChars)
        }
    }
}

/** 一次提问轮所需的全部上下文。 */
data class EngineContext(
    val scene: Scene,
    val intent: String,
    val state: EngineState,
    val covered: Set<String>,
    val turns: List<Turn>,
    val draftColumns: Map<Int, List<String>>,
    val pendingIssues: List<PrecisionIssue> = emptyList(),
    val referencedSheets: List<SheetDigest> = emptyList(),
)

/** 出单所需的上下文。 */
data class SheetContext(
    val scene: Scene,
    val intent: String,
    val turns: List<Turn>,
    val draftColumns: Map<Int, List<String>>,
)

/** M5 方案文档所需的上下文。`existing` 非空 = 续写（只补 `missingHeadings` 这几节）。 */
data class PlanContext(
    val scene: Scene,
    val intent: String,
    val turns: List<Turn>,
    val sheet: Sheet?,
    val skeleton: List<String>,
    val existing: List<PlanSection> = emptyList(),
    val missingHeadings: List<String> = emptyList(),
)

/** M6 立题上下文：场景 + 原话 + 五栏（+ 已有方案的章节标题，只用来判断查什么）。 */
data class ResearchQuestionContext(
    val scene: Scene,
    val intent: String,
    val turns: List<Turn>,
    val sheet: Sheet?,
    val planHeadings: List<String> = emptyList(),
)