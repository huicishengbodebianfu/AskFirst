package com.askfirst.remote

import com.askfirst.core.AppError
import com.askfirst.core.AppJson
import com.askfirst.core.AppResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `/v1/models` 的解析（纯函数，可 JVM 单测）。
 *
 * 各家 OpenAI 兼容网关的返回形状并不统一，这里按"能捞就捞"的顺序试：
 * 1. `{"data":[{"id":"gpt-4o"},…]}` —— OpenAI 标准
 * 2. `{"models":[{"id":"x"}]}` —— 少数网关
 * 3. `{"models":["x","y"]}` —— 更简单的网关
 * 4. `["x","y"]` —— 直接给数组
 *
 * 一个对象里 `id` 优先、没有就用 `name`（有些网关只有 name）。
 * 一个都认不出来时返回 [AppError.BadJson]（带原文），**不许**静默返回空列表——
 * 那样用户会以为"这台网关上没有模型"，而不是"这个返回我不认识"。
 */
object ModelList {

    fun parse(raw: String): AppResult<List<String>> {
        val body = raw.trim()
        if (body.isEmpty()) return AppResult.Err(AppError.BadJson(raw, "模型列表是空的"))
        val element = runCatching { AppJson.parseToJsonElement(body) }.getOrNull()
            ?: return AppResult.Err(AppError.BadJson(raw, "不是 JSON"))

        val candidates = when (element) {
            is JsonArray -> namesOf(element)
            is JsonObject -> {
                val fromData = (element["data"] as? JsonArray)?.let(::namesOf).orEmpty()
                val fromModels = (element["models"] as? JsonArray)?.let(::namesOf).orEmpty()
                // data 与 models 并存时，谁有内容用谁（空数组不算数）
                if (fromData.isNotEmpty()) fromData else fromModels
            }
            else -> emptyList()
        }

        val ids = candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) {
            return AppResult.Err(
                AppError.BadJson(raw, "这个返回里找不到模型名（data / models 里都没有 id 或 name）"),
            )
        }
        return AppResult.Ok(ids.sorted())
    }

    /** 一个数组里的模型名：对象取 `id` 或 `name`，裸字符串直接用。 */
    private fun namesOf(array: JsonArray): List<String> = array.mapNotNull { item ->
        when (item) {
            is JsonObject -> (item["id"] as? JsonPrimitive)?.contentOrNull
                ?: (item["name"] as? JsonPrimitive)?.contentOrNull

            is JsonPrimitive -> item.contentOrNull
            else -> null
        }
    }
}
