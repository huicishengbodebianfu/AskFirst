package com.askfirst.domain.engine

import com.askfirst.core.AppError
import com.askfirst.core.AppJson
import com.askfirst.core.AppResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 模型这一轮的原始输出（docs/04 §2 的 JSON 契约）。
 * 字段全给了默认值：模型少给一两个字段不该让整轮失败。
 */
@Serializable
data class EngineTurnPayload(
    val state: String? = null,
    val clarify: String? = null,
    val covered: List<String> = emptyList(),
    val questions: List<QuestionPayload> = emptyList(),
    @SerialName("sheet_delta") val sheetDelta: List<SheetDeltaPayload> = emptyList(),
    @SerialName("precision_issues") val precisionIssues: List<PrecisionIssuePayload> = emptyList(),
    val nudge: String? = null,
    @SerialName("block_reason") val blockReason: String? = null,
)

@Serializable
data class QuestionPayload(
    val id: String = "",
    val text: String = "",
    val why: String = "",
    val kind: String? = null,
    val recommended: String? = null,
    val options: List<String> = emptyList(),
)

@Serializable
data class SheetDeltaPayload(
    val col: Int = 0,
    val text: String = "",
    @SerialName("source_message_ids") val sourceMessageIds: List<String> = emptyList(),
)

@Serializable
data class PrecisionIssuePayload(
    val kind: String = "",
    val target: String = "",
    val detail: String = "",
    val quoted: String = "",
)

/**
 * 把模型输出的文本变成结构化 payload。失败返回 [AppError.BadJson]（原文保留，便于回看/重试）。
 */
class TurnParser {

    fun parse(raw: String): AppResult<EngineTurnPayload> {
        if (raw.isBlank()) {
            return AppResult.Err(AppError.BadJson(raw, "模型没有输出任何内容"))
        }
        val cleaned = JsonRepair.clean(raw)
            ?: return AppResult.Err(AppError.BadJson(raw, "找不到完整的 JSON 对象"))

        val payload = runCatching {
            AppJson.decodeFromString(EngineTurnPayload.serializer(), cleaned)
        }.getOrElse { error ->
            return AppResult.Err(AppError.BadJson(raw, error.message ?: "JSON 结构不匹配"))
        }
        return AppResult.Ok(payload)
    }
}