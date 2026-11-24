package com.askfirst.core

/** 文本处理。全部纯 Kotlin，可单测。 */
object Text {

    private val multipleSpace = Regex("[ \\t\\u00A0]+")

    /** 开头的语言标记可有可无，换行可有可无（单行围栏也要吃得下）。 */
    private val fenceOpen = Regex("^```[A-Za-z0-9_+.-]*[ \\t]*\\r?\\n?")

    /** 结尾的围栏（允许后面还有空白）。 */
    private val fenceClose = Regex("```[ \\t]*$")

    /** 去首尾空白、折叠连续空白、去零宽字符（LLM 输出常带）。 */
    fun normalize(input: String): String = input
        .replace('\u200B', ' ')
        .replace('\u00A0', ' ')
        .trim()
        .replace(multipleSpace, " ")

    /** 超长截断，保留省略号（用于日志、摘要、prompt 里的长文本）。 */
    fun truncate(input: String, max: Int): String {
        if (max <= 1) return "…"
        return if (input.length <= max) input else input.take(max - 1) + "…"
    }

    /** 只显示 Key 的后 4 位：任何 UI / 日志 / 导出都不许出现明文。 */
    fun maskToken(raw: String?): String = when {
        raw.isNullOrBlank() -> ""
        raw.length <= 4 -> "••••"
        else -> "••••" + raw.takeLast(4)
    }

    /** 把条目数组拼成一段（空则给占位），提示词导出与 prompt 组装共用。 */
    fun joinForPrompt(items: List<String>, empty: String = "（还没定）"): String =
        if (items.isEmpty()) empty else items.joinToString("\n")

    /**
     * 去掉 Markdown 代码围栏——LLM 最爱在 JSON 外面套一层 ```json。
     *
     * 必须同时兜住两种写法：
     * - 多行：` ```json\n{...}\n``` `
     * - 单行：` ```json{"a":1}``` `（模型偶尔这么吐；老实现按换行切，会把整份 JSON 变成空串，
     *   然后判成"找不到 JSON"白烧一次重试，docs/28 P2-2）
     */
    fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .replaceFirst(fenceOpen, "")
            .replace(fenceClose, "")
            .trim()
    }
}