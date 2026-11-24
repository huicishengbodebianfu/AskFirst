package com.askfirst.domain.research

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.core.Text
import com.askfirst.domain.model.Evidence
import com.askfirst.domain.model.EvidenceKind
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 离线外包（方案 §3.6.2 四条来源里的第 4 条，也是超时兜底）。
 *
 * 手机端连不上 GitHub 是**默认运行条件**，不是异常。所以这里给两条路：
 * 1. [promptOf]：把立题结果打包成一段提示词，用户拿到桌面端（有网/有 gh CLI）跑；
 * 2. [parseBundle]：把桌面端跑出来的 JSON 粘回来，走同一套解析与四条硬规则。
 *
 * 约定：桌面端要交回**证据 + 判定**两部分，因为手机上重放判定必须有证据垫底，
 * 否则报告里会出现「引用了不存在的链接」——那比没有报告更糟。
 */
object OfflineResearch {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 给桌面端的那段提示词（复制/分享出去）。 */
    fun promptOf(intent: String, questions: List<ResearchQuestion>): String = buildString {
        appendLine("请帮我在 GitHub 上检索下面这些问题，并按给定格式回一份 JSON。")
        appendLine()
        appendLine("这件事的背景（用户确认单里的原话摘要）：${intent.ifBlank { "（无）" }}")
        appendLine()
        appendLine("要查的问题（逐条去 GitHub 搜仓库、issue 与 README 原文）：")
        questions.forEach { question ->
            appendLine("${question.idx}. ${question.text}")
            appendLine("   检索关键词：${question.keywords.joinToString(" / ")}")
        }
        appendLine()
        appendLine("请遵守：")
        appendLine("- 每个问题查 5~8 个仓库、若干 issue；至少读 1~2 个 README 原文（不要只看简介）")
        appendLine("- 只许引用你真看到的仓库/issue/README，不许编；引文要能对上 URL")
        appendLine("- 判定只有四种：common（≥3 个独立项目一致）、antipattern（被明确抱怨，必须引原话）、")
        appendLine("  known_pit（≥2 条同类 issue）、unexplored（换个说法再搜仍 0 命中）")
        appendLine("- 找不到就说找不到，不要拿 1~2 个项目硬凑「常见做法」")
        appendLine()
        appendLine("只输出 JSON，不要解释、不要代码围栏：")
        appendLine()
        appendLine("""{"evidence":[{"id":"ev_q1_repo_1","question":1,"kind":"repo","title":"owner/repo","url":"https://github.com/owner/repo","snippet":"一句话说明","repo":"owner/repo","stars":123}],""")
        appendLine(""" "verdicts":[{"question":1,"judgement":"known_pit","why":"这意味着什么","quote":"","quote_url":"","evidence":["ev_q1_repo_1"]}]}""")
    }

    /** 粘回来的 JSON → 证据 + 判定（结构容错，语义仍由 ResearchRules 管）。 */
    fun parseBundle(raw: String, questions: List<ResearchQuestion>): AppResult<OfflineBundle> {
        val root = runCatching { json.parseToJsonElement(Text.stripCodeFence(raw)).jsonObject }.getOrElse {
            return AppResult.Err(AppError.BadJson(raw, "粘回来的不是 JSON"))
        }
        val knownQuestions = questions.map { it.idx }.toSet()
        val evidence = mutableListOf<Evidence>()

        (root["evidence"] as? JsonArray).orEmpty().forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val questionIdx = obj.int("question") ?: return@forEach
            if (questionIdx !in knownQuestions) return@forEach
            val url = obj.str("url") ?: return@forEach
            val title = obj.str("title") ?: url
            val kind = EvidenceKind.fromDb(obj.str("kind")) ?: EvidenceKind.REPO
            val id = obj.str("id") ?: EvidenceParse.evidenceId(questionIdx, kind, evidence.size + 1)
            if (evidence.any { it.id == id }) return@forEach
            evidence += Evidence(
                id = id,
                questionIdx = questionIdx,
                kind = kind,
                title = title,
                url = url,
                snippet = Text.truncate(obj.str("snippet").orEmpty(), 320),
                repoFullName = obj.str("repo"),
                stars = obj.int("stars") ?: 0,
                pushedAt = obj.str("pushed"),
            )
        }

        val verdicts = mutableListOf<Verdict>()
        var seq = 0
        (root["verdicts"] as? JsonArray).orEmpty().forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val questionIdx = obj.int("question") ?: return@forEach
            if (questionIdx !in knownQuestions) return@forEach
            val judgement = com.askfirst.domain.model.Judgement.fromDb(obj.str("judgement")) ?: return@forEach
            val why = Text.truncate(obj.str("why").orEmpty().trim(), 400)
            if (why.isBlank()) return@forEach
            val ids = (obj["evidence"] as? JsonArray).orEmpty()
                .mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                .filter { candidate -> evidence.any { it.id == candidate } }
            seq += 1
            verdicts += Verdict(
                id = "vd_q${questionIdx}_off$seq",
                questionIdx = questionIdx,
                judgement = judgement,
                rationale = why,
                quotedText = Text.truncate(obj.str("quote").orEmpty().trim(), 400),
                quotedUrl = obj.str("quote_url").orEmpty().trim(),
                evidenceIds = ids.distinct(),
            )
        }

        if (evidence.isEmpty() && verdicts.isEmpty()) {
            return AppResult.Err(AppError.BadJson(raw, "里面既没有证据也没有判定"))
        }
        return AppResult.Ok(OfflineBundle(evidence = evidence, verdicts = verdicts, questions = questions))
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.let { runCatching { it.jsonPrimitive.content.trim().toInt() }.getOrNull() }

}

data class OfflineBundle(
    val evidence: List<Evidence>,
    val verdicts: List<Verdict>,
    val questions: List<ResearchQuestion>,
)

