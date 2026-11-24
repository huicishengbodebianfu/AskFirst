package com.askfirst.domain.citation

/**
 * 跨场景引用的硬规则（方案 §3.4）：
 * - 最多 3 份，超过强制截断（UI 和门面双拦，防止别的入口绕过 UI）；
 * - 关键词搜索是 LIKE 全表扫，`%` / `_` 是通配符，直接删掉
 *   （这两个字符在中文输入里没有正当用途，转义不如删除简单可靠）。
 * 纯函数，可单测。
 */
object CitationPolicy {
    const val MAX_CITATIONS = 3
    const val RECENT_LIMIT = 30

    /** 清洗搜索关键词：去 LIKE 通配符 + trim。空串 = 走「最近」列表。 */
    fun sanitizeKeyword(raw: String): String = raw.replace("%", "").replace("_", "").trim()

    /** 勾选列表截断到上限（保持原顺序）。 */
    fun <T> cap(list: List<T>): List<T> = list.take(MAX_CITATIONS)

    /** 还能不能再勾一份。 */
    fun canSelectMore(selectedCount: Int): Boolean = selectedCount < MAX_CITATIONS

    /**
     * 摘要兜底文案（M4 核查补的）：库里的 `sheet.summary` 可能是**空串**而不是 null，
     * `COALESCE` 只在 NULL 时才会往后取，空串会让「这份旧结论」变成一行空白——
     * 查询里已用 `NULLIF(x, '')` 兜住，这里再做最后一道：空白一律给可读占位。
     */
    fun summaryText(raw: String?, placeholder: String = "（这份旧结论没留下摘要）"): String =
        raw?.trim()?.takeIf { it.isNotEmpty() } ?: placeholder
}
