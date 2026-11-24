package com.askfirst.domain.research

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.core.Text
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.Sheet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「立题」：把一场问答变成 3~6 个**可检索**的问题（方案 §3.6 第 1 步）。
 *
 * 两条底线：
 * 1. 模型起草失败不影响流程——[fallback] 从五栏单里抽关键词自己拼，
 *    因为「宁可残缺也不空手」是本功能的设计目标（docs/26 §3.10）。
 * 2. 每个问题都必须带关键词（= 报告里「已覆盖关键词」那一行），
 *    没有关键词的问题既不能搜、也不能下「无人区」结论。
 */
object QuestionDrafting {

    const val MIN_QUESTIONS = 3
    const val MAX_QUESTIONS = 6

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 界面零内部词：这些词出现在问题上就说明模型在跟用户说内部话。 */
    private val bannedWords = listOf(
        "verdict", "evidence", "research", "prompt",
        "sectionsjson", "planskeleton", "planSkeleton", "sessionid", "prompt_version",
    )

    /** 断词用的停用词：只滤虚词，别把「缓存」「聚合层」这种实词滤掉。 */
    private val stopWords = setOf(
        "这个", "那个", "我们", "你们", "他们", "自己", "什么", "怎么", "就是", "还是",
        "没有", "还没", "不是", "可以", "需要", "因为", "所以", "但是", "而且", "然后",
        "如果", "已经", "现在", "一个", "这次", "是否", "觉得", "这样", "那样", "东西",
    )

    /** 契约：`{"questions":[{"text":"…","keywords":["…"],"why":"…"}]}`。 */
    fun parse(raw: String): AppResult<List<ResearchQuestion>> {
        val root = runCatching { json.parseToJsonElement(Text.stripCodeFence(raw)).jsonObject }.getOrElse {
            return AppResult.Err(AppError.BadJson(raw, "模型回的不是 JSON"))
        }
        val array: JsonArray = when (val element = root["questions"]) {
            null -> return AppResult.Err(AppError.BadJson(raw, "回的内容里没有 questions"))
            else -> runCatching { element.jsonArray }.getOrElse {
                return AppResult.Err(AppError.BadJson(raw, "questions 不是数组"))
            }
        }
        val out = mutableListOf<ResearchQuestion>()
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val text = Text.truncate(obj.str("text").orEmpty().trim(), 120)
            if (text.length < 6) return@forEach
            if (bannedWords.any { text.lowercase().contains(it) }) return@forEach
            val keywords = obj.stringList("keywords").map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { keywordsOf(text) }
            if (keywords.isEmpty()) return@forEach
            val question = ResearchQuestion(
                idx = 0,
                text = text,
                keywords = keywords.take(4),
                why = Text.truncate(obj.str("why").orEmpty().trim(), 120),
            )
            if (out.any { it.text.normalized() == question.text.normalized() }) return@forEach
            out += question
        }
        if (out.isEmpty()) {
            return AppResult.Err(AppError.BadJson(raw, "一个问题都没立起来"))
        }
        return AppResult.Ok(out.take(MAX_QUESTIONS).renumbered())
    }

    /**
     * 规则兜底：模型不可用/没网时，直接从确认单的第 2 栏（约束）与第 5 栏（没想清楚）
     * 以及最初那句话里抽词拼问题。宁可粗糙，也不让流程空手。
     */
    fun fallback(intent: String, sheet: Sheet?, limit: Int = 4): List<ResearchQuestion> {
        val seeds = linkedSetOf<String>()
        keywordsOf(intent).forEach { seeds += it }
        sheet?.let { notNull ->
            notNull.texts(Cols.CONSTRAINTS).forEach { keywordsOf(it).forEach { kw -> seeds += kw } }
            notNull.texts(Cols.OPEN_QUESTIONS).forEach { keywordsOf(it).forEach { kw -> seeds += kw } }
        }
        val picked = seeds.take(MAX_QUESTIONS)
        if (picked.isEmpty()) return emptyList()
        return picked.take(limit).mapIndexed { index, keyword ->
            ResearchQuestion(
                idx = index + 1,
                text = "「$keyword」这块，公开项目里有没有现成做法",
                keywords = listOf(keyword),
                why = "出单时你提过它，值得看看别人踩过没有",
            )
        }
    }

    /** 解析出来的少于 3 个时用兜底补齐（不够 3 个问题，调研没有意义）。 */
    fun merge(primary: List<ResearchQuestion>, fallback: List<ResearchQuestion>): List<ResearchQuestion> {
        if (primary.size >= MIN_QUESTIONS) return primary.take(MAX_QUESTIONS).renumbered()
        val out = primary.toMutableList()
        fallback.forEach { candidate ->
            if (out.size >= MIN_QUESTIONS) return@forEach
            if (out.none { it.text.normalized() == candidate.text.normalized() }) out += candidate
        }
        return out.take(MAX_QUESTIONS).renumbered()
    }

    /** 从一句中文里抽可检索的词：切标点、去虚词、留 2~12 字的实词片段。 */
    fun keywordsOf(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val segments = text
            .replace(Regex("[\\[\\]【】「」『』（）()《》\"'“”‘’，。、；：！？,.;:!?\\s]+"), "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.length in 2..12 }
            .filter { it !in stopWords }
            .filter { segment -> segment.any { it.isLetterOrDigit() } }
        return segments.distinct().take(3)
    }

    /** 问题编号必须是 1..n 连续的：报告与库里都按它排序。 */
    fun List<ResearchQuestion>.renumbered(): List<ResearchQuestion> =
        mapIndexed { index, question -> question.copy(idx = index + 1) }

    private fun String.normalized(): String = replace(Regex("\\s+"), "").lowercase()

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.stringList(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        val array = runCatching { element.jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }
}
