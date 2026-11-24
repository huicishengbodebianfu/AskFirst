package com.askfirst.domain.plan

import com.askfirst.core.AppError
import com.askfirst.core.AppJson
import com.askfirst.core.AppResult
import com.askfirst.domain.engine.JsonRepair
import com.askfirst.domain.model.PlanDraft
import com.askfirst.domain.model.PlanSectionDraft
import kotlinx.serialization.Serializable

/**
 * 方案 JSON 契约（提示词见 `assets/prompts/plan_system.md`）：
 *
 * ```json
 * {"title":"…","sections":[{"heading":"背景与问题","body":"…","sources":["m_1"]}]}
 * ```
 *
 * 容错与出单一致（`JsonRepair`），但**出处过滤更严**：不在本场问答里的 id 一律丢掉，
 * 丢完一个都不剩的段落整段不要——D4 的执行层保证，宁可少一节，也不留没出处的话。
 */
@Serializable
data class PlanPayload(
    val title: String = "",
    val sections: List<PlanSectionPayload> = emptyList(),
)

@Serializable
data class PlanSectionPayload(
    val heading: String = "",
    val body: String = "",
    val sources: List<String> = emptyList(),
)

class PlanParser {

    fun parse(raw: String, knownSourceIds: Set<String>): AppResult<PlanDraft> {
        if (raw.isBlank()) return AppResult.Err(AppError.BadJson(raw, "模型没有输出任何内容"))
        val cleaned = JsonRepair.clean(raw)
            ?: return AppResult.Err(AppError.BadJson(raw, "找不到完整的 JSON 对象"))
        val payload = runCatching {
            AppJson.decodeFromString(PlanPayload.serializer(), cleaned)
        }.getOrElse { error ->
            return AppResult.Err(AppError.BadJson(raw, error.message ?: "方案 JSON 结构不匹配"))
        }

        val kept = payload.sections.mapNotNull { section ->
            val heading = section.heading.trim().removePrefix("#").trim()
            val body = section.body.trim()
            if (heading.isEmpty() || body.isEmpty()) return@mapNotNull null
            val sources = section.sources
                .map { it.trim() }
                .filter { it.isNotEmpty() && it in knownSourceIds }
                .distinct()
            if (sources.isEmpty()) return@mapNotNull null
            PlanSectionDraft(heading = heading, body = body, sourceMessageIds = sources)
        }

        // 同一个标题只留第一次：模型偶尔会把「约束」写两遍，页面上就会长出两个「3. 约束」
        val deduped = mutableListOf<PlanSectionDraft>()
        kept.forEach { draft ->
            if (deduped.none { PlanSkeleton.matches(it.heading, draft.heading) }) deduped += draft
        }

        if (payload.sections.isEmpty()) {
            return AppResult.Err(AppError.BadJson(raw, "模型这次一节都没写"))
        }
        if (deduped.isEmpty()) {
            return AppResult.Err(
                AppError.BadJson(raw, "每一段都对不上原话（出处全是编的），这份先不生成"),
            )
        }
        return AppResult.Ok(PlanDraft(title = payload.title.trim(), sections = deduped))
    }
}
