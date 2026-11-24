package com.askfirst.remote

/** 模型调用配置（来自 SettingsStore + SecretStore，不落任何持久层）。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double = 0.6,
    val maxTokens: Int = 1200,
)

/** 一条对话消息。role 只能是 system / user / assistant。 */
data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}

/** 一次请求。stream=true 时走 SSE；callTimeoutMs>0 时用 OkHttp 的 socket 级总超时（协程取消打断不了阻塞读）。 */
data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String,
    val temperature: Double = 0.6,
    val maxTokens: Int = 1200,
    val stream: Boolean = false,
    val jsonObject: Boolean = false,
    val callTimeoutMs: Long = 0L,
)

/** 流式事件（S3 用），先定义好，避免 S3 改接口签名时牵动 UI。 */
sealed interface LlmEvent {
    data class Delta(val text: String) : LlmEvent
    data class Done(val fullText: String, val finishReason: String?) : LlmEvent
    data class Failed(val error: com.askfirst.core.AppError) : LlmEvent
}