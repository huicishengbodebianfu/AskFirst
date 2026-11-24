package com.askfirst.domain.sheet

import com.askfirst.core.AppError
import com.askfirst.core.AppJson
import com.askfirst.core.AppResult
import com.askfirst.domain.engine.JsonRepair
import kotlinx.serialization.Serializable

/** 出单的 JSON 契约（docs/04 §3）。 */
@Serializable
data class SheetPayload(
    val summary: String = "",
    val columns: Map<String, List<String>> = emptyMap(),
) {
    /** 把 "1".."5" 的键规范成 Int 栏号，顺手丢掉越界栏与空条目。 */
    fun normalizedColumns(): Map<Int, List<String>> = buildMap {
        columns.forEach { (key, items) ->
            val col = key.trim().toIntOrNull() ?: return@forEach
            if (col !in 1..5) return@forEach
            val cleaned = items.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (cleaned.isNotEmpty()) put(col, cleaned)
        }
    }
}

class SheetParser {

    fun parse(raw: String): AppResult<SheetPayload> {
        if (raw.isBlank()) return AppResult.Err(AppError.BadJson(raw, "模型没有输出任何内容"))
        val cleaned = JsonRepair.clean(raw)
            ?: return AppResult.Err(AppError.BadJson(raw, "找不到完整的 JSON 对象"))
        val payload = runCatching {
            AppJson.decodeFromString(SheetPayload.serializer(), cleaned)
        }.getOrElse { error ->
            return AppResult.Err(AppError.BadJson(raw, error.message ?: "出单 JSON 结构不匹配"))
        }
        if (payload.normalizedColumns().isEmpty()) {
            return AppResult.Err(AppError.BadJson(raw, "五栏一条内容都没有"))
        }
        return AppResult.Ok(payload)
    }
}