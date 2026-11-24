package com.askfirst.remote

import com.askfirst.core.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * 模型通道抽象。S2 只有连通性检查；S3 加 `stream(req)` 做 SSE 流式。
 * 换成别的协议（自建网关 / 其他厂商）只改实现，不改调用方。
 */
interface LlmProvider {

    /** 读当前配置并校验完整性。缺 Key / 缺 model 时返回 Config 错误（文案可直接给用户看）。 */
    suspend fun config(): AppResult<LlmConfig>

    /** 设置页「测试连通」：一次极小请求，返回一句人话。 */
    suspend fun ping(): AppResult<String>

    /**
     * 拉取网关的模型列表（`GET {base_url}/models`），给设置页的「换模型」用。
     * 返回排好序、去过重的模型名；认不出来的返回体要报 BadJson，**不许静默给空列表**。
     */
    suspend fun listModels(): AppResult<List<String>>

    /**
     * 流式补全：边收边吐 [LlmEvent.Delta]，最后给一次 [LlmEvent.Done]（带全文），
     * 任何失败给一次 [LlmEvent.Failed] 并结束。**不许在失败时静默结束**。
     */
    fun stream(request: ChatRequest): Flow<LlmEvent>
}