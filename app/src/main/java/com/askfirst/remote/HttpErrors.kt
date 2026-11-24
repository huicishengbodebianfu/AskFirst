package com.askfirst.remote

import com.askfirst.core.AppError
import com.askfirst.core.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HTTP 错误码 → 用户能看懂的错误。纯函数（docs/prompts/glossary_ban.md §3 的文案规范）。
 */
object HttpErrors {

    fun fromStatus(code: Int, body: String, retryAfterSec: Int? = null): AppError {
        val detail = serverMessage(body)?.take(160) ?: "（没给原因）"
        return when (code) {
            401, 403 -> AppError.Auth(detail)
            429 -> AppError.RateLimited(retryAfterSec)
            // 404 基本都是"model 名填错"或"base_url 少/多了一段"——这是用户能自己修的，
            // 别套用「这不是你能修的问题」那句通用文案（docs/28 修复后新增「拉取模型列表」，
            // 有了列表就更容易踩到 outline 的模型名）
            404 -> AppError.Config(
                "模型服务说这儿没有这个接口或模型：$detail。" +
                    "八成是 model 名不对——点「拉取模型列表」从里面选一个；也可能是 base_url 多了或少了一段。",
            )
            in 400..499 -> AppError.Server(code, detail)
            in 500..599 -> AppError.Server(code, detail)
            else -> AppError.Server(code, detail)
        }
    }

    /** 从 OpenAI 风格的 `{"error":{"message":"…"}}` 里抠出人话；抠不出就返回 null。 */
    fun serverMessage(body: String): String? = runCatching {
        val root = AppJson.parseToJsonElement(body) as? JsonObject ?: return null
        val error = root["error"] as? JsonObject
        error?.get("message")?.jsonPrimitive?.content
            ?: root["message"]?.jsonPrimitive?.content
    }.getOrNull()
}