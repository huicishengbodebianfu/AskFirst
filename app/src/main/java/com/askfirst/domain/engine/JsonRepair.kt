package com.askfirst.domain.engine

/**
 * JSON 修复（纯函数，可单测）。LLM 的输出必然是"差不多是 JSON"：
 * 带代码围栏、前后有解释、尾逗号、被截断。这里只做**安全**的修复，不做智能猜测。
 */
object JsonRepair {

    /**
     * 剥代码围栏。**实现只有一份**（[com.askfirst.core.Text.stripCodeFence]）：
     * 之前这里和 Text 各抄了一份，单行围栏把整份 JSON 吃成空串的 bug 就得修两遍（docs/28 P2-2）。
     */
    fun stripFence(raw: String): String = com.askfirst.core.Text.stripCodeFence(raw)

    /** 取第一个括号配平的 `{...}`：跳过字符串里的引号与转义，避免被 content 里的 `}` 骗到。 */
    fun firstBalancedObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /** 去掉 `,}` / `,]` 这种尾逗号（字符串内的逗号不动）。 */
    fun removeTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                out.append(ch)
                index++
                continue
            }
            when (ch) {
                '"' -> {
                    inString = true
                    out.append(ch)
                }

                ',' -> {
                    var lookahead = index + 1
                    while (lookahead < text.length && text[lookahead].isWhitespace()) lookahead++
                    val next = text.getOrNull(lookahead)
                    if (next == '}' || next == ']') {
                        // 跳过这个逗号
                    } else {
                        out.append(ch)
                    }
                }

                else -> out.append(ch)
            }
            index++
        }
        return out.toString()
    }

    /** 完整清洗：剥围栏 → 取配平对象 → 去尾逗号。失败返回 null。 */
    fun clean(raw: String): String? {
        val stripped = stripFence(raw)
        val candidate = firstBalancedObject(stripped) ?: stripped.takeIf { it.trimStart().startsWith("{") }
        return candidate?.let { removeTrailingCommas(it) }
    }
}