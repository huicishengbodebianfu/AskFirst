package com.askfirst.remote

/**
 * server-sent-events 的**行级**解析（纯 Kotlin，可 JVM 单测）。
 * 只管分帧，不管 JSON 语义——语义在 [ChatChunkParser]。
 *
 * 规则（W3C EventSource）：
 * - `data:` 后可选一个空格，多行 data 用 `\n` 拼接
 * - 空行 dispatch 一个帧；注释行（`:` 开头）忽略
 * - `event:` / `id:` / `retry:` 当前用不到，保留 event 字段
 */
class SseParser {

    private val dataLines = StringBuilder()
    private var eventName: String? = null

    /** 喂一行（不含换行符）。返回非 null 表示攒够了一帧。 */
    fun feed(line: String): SseFrame? {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isEmpty()) return dispatch()
        if (trimmed.startsWith(":")) return null

        val colon = trimmed.indexOf(':')
        val field = if (colon >= 0) trimmed.substring(0, colon) else trimmed
        val rawValue = if (colon >= 0) trimmed.substring(colon + 1) else ""
        val value = rawValue.removePrefix(" ")

        when (field) {
            "data" -> {
                if (dataLines.isNotEmpty()) dataLines.append('\n')
                dataLines.append(value)
            }

            "event" -> eventName = value
            else -> Unit // id / retry / 未知字段：忽略
        }
        return null
    }

    fun flush(): SseFrame? = dispatch()

    fun reset() {
        dataLines.setLength(0)
        eventName = null
    }

    private fun dispatch(): SseFrame? {
        if (dataLines.isEmpty()) {
            eventName = null
            return null
        }
        val frame = SseFrame(event = eventName, data = dataLines.toString())
        dataLines.setLength(0)
        eventName = null
        return frame
    }

    /** 测试与离线回放用：一次喂整段文本。 */
    fun feedAll(text: String): List<SseFrame> {
        val frames = mutableListOf<SseFrame>()
        text.split('\n').forEach { line ->
            feed(line)?.let { frames.add(it) }
        }
        flush()?.let { frames.add(it) }
        return frames
    }
}

data class SseFrame(val event: String?, val data: String) {
    val isDoneSentinel: Boolean get() = data.trim() == "[DONE]"
}