package com.askfirst.remote

import com.askfirst.core.AppError
import com.askfirst.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一个 SSE 帧的语义解析（纯函数，可 JVM 单测）。
 * 兼容 OpenAI 风格的 `choices[0].delta.content`、`finish_reason`、以及 `{"error":{...}}`。
 */
object ChatChunkParser {

    sealed interface Result {
        /** 一段增量文本（可能为空串，例如只带 role 的第一帧） */
        data class Delta(val text: String) : Result

        /** 收尾帧（带 finish_reason，但不一定带 [DONE]） */
        data class Finish(val reason: String?) : Result

        /** 服务端在流里塞了错误 */
        data class Failure(val error: AppError) : Result

        /** 解析不了就忽略：一行脏数据不许打断整条流 */
        data object Ignored : Result
    }

    fun parse(frame: SseFrame): Result {
        if (frame.isDoneSentinel) return Result.Finish("stop")

        val root = runCatching { AppJson.parseToJsonElement(frame.data) as? JsonObject }
            .getOrNull() ?: return Result.Ignored

        (root["error"] as? JsonObject)?.let { error ->
            val message = (error["message"] as? JsonPrimitive)?.content ?: "服务端返回错误"
            val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: 500
            return Result.Failure(HttpErrors.fromStatus(code, frame.data))
        }

        val choices = root["choices"] as? JsonArray ?: return Result.Ignored
        val first = choices.firstOrNull() as? JsonObject ?: return Result.Ignored

        val content = ((first["delta"] as? JsonObject)?.get("content") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            .orEmpty()

        val reason = (first["finish_reason"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

        return when {
            content.isNotEmpty() -> Result.Delta(content)
            reason != null -> Result.Finish(reason)
            else -> Result.Ignored
        }
    }
}