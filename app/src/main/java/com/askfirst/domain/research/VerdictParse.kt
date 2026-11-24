package com.askfirst.domain.research

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.core.Text
import com.askfirst.domain.model.Evidence
import com.askfirst.domain.model.Judgement
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「对照」这一步的输出 → [Verdict]（纯函数）。
 *
 * 契约（见 assets/prompts/research_verdict.md）：
 * ```json
 * {"verdicts":[{"question":1,"judgement":"common","why":"…","quote":"…","quote_url":"…",
 *               "evidence":["ev_q1_repo_1"]}]}
 * ```
 * 这里只做**结构**上的容错；「够不够格下这个结论」交给 [ResearchRules.normalize]。
 */
object VerdictParse {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; isLenient = true }

    /** 内部术语不许出现在用户看得见的判定文字里（docs/05 术语门）。 */
    private val bannedWords = listOf(
        "verdict", "evidence", "research", "prompt",
        "sectionsjson", "planskeleton", "sessionid", "prompt_version",
    )

    fun parse(
        raw: String,
        questions: List<ResearchQuestion>,
        evidence: List<Evidence>,
    ): AppResult<List<Verdict>> {
        val root = runCatching { json.parseToJsonElement(Text.stripCodeFence(raw)).jsonObject }.getOrElse {
            return AppResult.Err(AppError.BadJson(raw, "模型回的不是 JSON"))
        }
        val array: JsonArray = when (val element = root["verdicts"]) {
            null -> return AppResult.Err(AppError.BadJson(raw, "回的内容里没有 verdicts"))
            else -> runCatching { element.jsonArray }.getOrElse {
                return AppResult.Err(AppError.BadJson(raw, "verdicts 不是数组"))
            }
        }
        val knownQuestions = questions.map { it.idx }.toSet()
        val knownEvidence = evidence.map { it.id }.toSet()
        val out = mutableListOf<Verdict>()
        var seq = 0

        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val questionIdx = obj.intOrNull("question") ?: return@forEach
            if (questionIdx !in knownQuestions) return@forEach
            val judgement = Judgement.fromDb(obj.str("judgement")) ?: return@forEach
            val why = Text.truncate(obj.str("why").orEmpty().trim(), 400)
            if (why.isBlank()) return@forEach
            if (bannedWords.any { why.lowercase().contains(it) }) return@forEach
            val evidenceIds = obj.stringList("evidence").filter { it in knownEvidence }.distinct()
            seq += 1
            out += Verdict(
                id = "vd_q${questionIdx}_$seq",
                questionIdx = questionIdx,
                judgement = judgement,
                rationale = why,
                quotedText = Text.truncate(obj.str("quote").orEmpty().trim(), 400),
                quotedUrl = obj.str("quote_url").orEmpty().trim(),
                evidenceIds = evidenceIds,
            )
        }

        if (out.isEmpty()) {
            return AppResult.Err(AppError.BadJson(raw, "一条能用的判定都没有"))
        }
        return AppResult.Ok(out)
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.let { runCatching { it.jsonPrimitive.content.trim().toInt() }.getOrNull() }

    private fun JsonObject.stringList(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        val array = runCatching { element.jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }
}
