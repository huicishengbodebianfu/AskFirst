package com.askfirst.domain.research

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.core.Text
import com.askfirst.domain.model.Evidence
import com.askfirst.domain.model.EvidenceKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * GitHub 检索结果 → [Evidence]（纯函数，不碰网络也不碰 Android）。
 *
 * 为什么不用 data class 反序列化：GitHub 的字段会加减（`items` 里有的仓库没有 `description`，
 * issue 的 `body` 可能是 null），用 JsonElement 手动取字段比维护一堆可空模型更能容忍。
 */
object EvidenceParse {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** repo 检索：`/search/repositories`。 */
    fun parseRepos(raw: String, questionIdx: Int): AppResult<List<Evidence>> =
        parseItems(raw, questionIdx, EvidenceKind.REPO) { item, seq ->
            val fullName = item.str("full_name").orEmpty()
            val url = item.str("html_url") ?: item.str("url")
            if (fullName.isBlank() || url.isNullOrBlank()) return@parseItems null
            Evidence(
                id = evidenceId(questionIdx, EvidenceKind.REPO, seq),
                questionIdx = questionIdx,
                kind = EvidenceKind.REPO,
                title = fullName,
                url = url,
                snippet = item.str("description").orEmpty().trim(),
                repoFullName = fullName,
                stars = item.int("stargazers_count"),
                pushedAt = item.str("pushed_at"),
            )
        }

    /** issue 检索：`/search/issues`。「已知坑」「反模式」都靠它。 */
    fun parseIssues(raw: String, questionIdx: Int): AppResult<List<Evidence>> =
        parseItems(raw, questionIdx, EvidenceKind.ISSUE) { item, seq ->
            val url = item.str("html_url")
            val title = item.str("title").orEmpty().trim()
            if (url.isNullOrBlank() || title.isBlank()) return@parseItems null
            Evidence(
                id = evidenceId(questionIdx, EvidenceKind.ISSUE, seq),
                questionIdx = questionIdx,
                kind = EvidenceKind.ISSUE,
                title = title,
                url = url,
                // issue 正文可能很长，也只留一段：手机端不搬运全文
                snippet = Text.truncate(item.str("body").orEmpty().trim().replace(Regex("\\s+"), " "), 320),
                repoFullName = item.str("repository_url")?.substringAfter("/repos/", "")?.takeIf { it.isNotBlank() },
                pushedAt = item.str("created_at"),
            )
        }

    /** code 检索：`/search/code`（要 token）。 */
    fun parseCode(raw: String, questionIdx: Int): AppResult<List<Evidence>> =
        parseItems(raw, questionIdx, EvidenceKind.CODE) { item, seq ->
            val url = item.str("html_url")
            if (url.isNullOrBlank()) return@parseItems null
            val repo = (item["repository"] as? JsonObject)?.str("full_name")
            Evidence(
                id = evidenceId(questionIdx, EvidenceKind.CODE, seq),
                questionIdx = questionIdx,
                kind = EvidenceKind.CODE,
                title = item.str("name") ?: item.str("path").orEmpty(),
                url = url,
                snippet = item.str("path").orEmpty(),
                repoFullName = repo,
            )
        }

    /**
     * README 原文（`/repos/{owner}/{repo}/readme`）：base64 解出来，
     * 去 markdown 噪音后留一段——「读原文，不读简介」的落点。
     */
    fun readmeText(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(Text.stripCodeFence(raw)).jsonObject }.getOrNull() ?: return null
        val content = root.str("content") ?: return null
        val bytes = runCatching {
            Base64.getMimeDecoder().decode(content.replace("\n", "").replace("\r", ""))
        }.getOrNull() ?: return null
        // MIME 解码器很宽容：坏 base64 也能吐出一堆乱码。乱码不是「原文」，
        // 拿它去判断「常见做法」比没有证据更糟，所以这里要判一次「像不像人话」。
        if (!looksLikeText(bytes)) return null
        return clean(bytes.toString(Charsets.UTF_8))
    }

    /** 可打印字符（含中文/标点）占多数才算正文；二进制乱码一律不算。 */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return false
        if (text.any { it == '\uFFFD' }) return false
        val readable = text.count { it.isLetterOrDigit() || it.isWhitespace() || it in "，。：；、！？（）「」“”—-…·/.,:;!?()[]{}<>#*`'\"=+|&%$@~" }
        return readable.toDouble() / text.length >= 0.9
    }

    /** 去掉徽章/标题符号/多余空行，只留能读的正文。 */
    fun clean(readme: String): String {
        val cleaned = readme
            // 徽章要排在图片前面：先剥图片会把 `[![alt](img)](link)` 拆成 `[  ](link)`，链接壳留在正文里
            .replace(BADGE, " ")
            .replace(IMAGE, " ")
            .replace(TAG) { match ->
                // 只剥"确实像 HTML 标签"的：`Map<String, List<Int>>` 和 `Vec<T>` 里的尖括号
                // 必须留着，那是「读原文不读简介」要的代码（docs/28 P2-7）
                if (match.groupValues[1].lowercase() in HTML_TAGS) " " else match.value
            }
            .replace(Regex("(?m)^#{1,6}\\s*"), "") // 标题符号
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "") // 列表符
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return Text.truncate(cleaned, 600)
    }

    private val BADGE = Regex("\\[!\\[[^\\]]*]\\([^)]*\\)]\\([^)]*\\)")
    private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")

    /** 标签名 + 可选属性；属性里不许出现尖括号，避免跨行吞掉正文。 */
    private val TAG = Regex("<\\/?([a-zA-Z][a-zA-Z0-9]*)(\\s[^<>]{0,200})?\\/?>")

    /** 只认这些真的 HTML 标签（小写）。单字母大写泛型 `<T>`、`<String, Int>` 因此不会被吃掉。 */
    private val HTML_TAGS = setOf(
        "a", "abbr", "b", "big", "blockquote", "br", "caption", "center", "cite", "code", "col",
        "colgroup", "dd", "del", "details", "dfn", "div", "dl", "dt", "em", "embed", "figcaption",
        "figure", "font", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr",
        "i", "iframe", "img", "input", "ins", "kbd", "label", "li", "main", "map", "mark", "nav",
        "object", "ol", "option", "p", "param", "picture", "pre", "q", "s", "samp", "script",
        "section", "select", "small", "source", "span", "strong", "style", "sub", "summary", "sup",
        "table", "tbody", "td", "textarea", "tfoot", "th", "thead", "time", "tr", "u", "ul", "var",
        "video", "audio", "wbr", "area", "article", "aside", "button", "option",
    )

    private fun <T> parseItems(
        raw: String,
        questionIdx: Int,
        kind: EvidenceKind,
        map: (JsonObject, Int) -> T?,
    ): AppResult<List<T>> {
        val root = runCatching { json.parseToJsonElement(Text.stripCodeFence(raw)).jsonObject }.getOrElse {
            return AppResult.Err(AppError.BadJson(raw, "GitHub 返回的不是 JSON"))
        }
        val items: JsonArray = when (val element = root["items"]) {
            null -> return AppResult.Err(AppError.BadJson(raw, "GitHub 的回应里没有 items"))
            else -> runCatching { element.jsonArray }.getOrElse {
                return AppResult.Err(AppError.BadJson(raw, "items 不是数组"))
            }
        }
        val out = mutableListOf<T>()
        items.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            map(obj, index + 1)?.let { out += it }
        }
        // 0 命中是合法结果（这正是「无人区」的来源），不是错误
        return AppResult.Ok(out).also { if (kind == EvidenceKind.REPO && out.isEmpty()) Unit }
    }

    /** 证据 id 要稳定可复算：同一问题同一类同样的序号 → 同样的 id，单测才好断言。 */
    fun evidenceId(questionIdx: Int, kind: EvidenceKind, seq: Int): String =
        "ev_q${questionIdx}_${kind.db}_$seq"

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(key: String): Int =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() } ?: 0

    @Suppress("unused")
    private fun JsonElement.asObj(): JsonObject? = this as? JsonObject
}
