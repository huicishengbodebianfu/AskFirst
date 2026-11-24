package com.askfirst.domain.engine

import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.PrecisionIssue
import com.askfirst.domain.model.PrecisionKind
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import com.askfirst.domain.prompt.Lexicon

/**
 * 精确度四项检查（改动 #4）。**离线可跑，不依赖模型**：
 * 1 目标一致性（GOAL_DRIFT）  2 隐藏默认值（HIDDEN_DEFAULT）
 * 3 场景走查（WALKTHROUGH_GAP）4 矛盾扫描（CONTRADICTION）
 *
 * 这些东西单测能覆盖，是因为它们不"觉得差不多"，只按规则判。
 */
class PrecisionGate(
    private val lexicon: Lexicon = Lexicon,
    /**
     * 文本类缺口（隐藏默认值 / 矛盾）只看他**最近几轮**的原话。
     *
     * 为什么必须限窗：这两类缺口是从他的原话里扫出来的，而原话只追加、从不删除。
     * 全历史重扫 = 他早期只要说过一个歧义词（"用户"/"保存"/"记录"…）或一次矛盾，
     * 缺口就永远存在 → `issues.isNotEmpty()` 恒真 → READY 不可达；矛盾更会把状态永久钉在
     * BLOCKED，连"哪条是你真正要的"那条提示都消不掉（docs/28 P1-1）。
     * 限窗的语义就是"追问过一次就算处理过"：他再答一句，窗口就推过去了。
     * 2 轮 = 抓得住"这一句和上一句打架"，又不会把陈年旧话翻出来重算。
     */
    private val textScanTurns: Int = DEFAULT_TEXT_SCAN_TURNS,
) {

    fun check(input: GateInput): List<PrecisionIssue> {
        val issues = mutableListOf<PrecisionIssue>()
        val texts = input.recentUserTexts(textScanTurns)
        issues += hiddenDefaults(texts)
        issues += contradictions(texts)
        issues += walkthrough(input.draft)
        goalDrift(input)?.let { issues += it }
        return issues.distinctBy { it.kind to it.detail }
    }

    private fun hiddenDefaults(userTexts: List<String>): List<PrecisionIssue> =
        userTexts.flatMap { text ->
            lexicon.ambiguousHits(text).map { hit ->
                PrecisionIssue(
                    kind = PrecisionKind.HIDDEN_DEFAULT,
                    target = "col2",
                    detail = "「${hit.term}」没说清是哪种：${hit.options.joinToString(" / ")}",
                    quoted = hit.quote,
                )
            }
        }

    private fun contradictions(userTexts: List<String>): List<PrecisionIssue> =
        lexicon.contradictions.mapNotNull { rule ->
            val aQuote = userTexts.firstOrNull { rule.a.containsMatchIn(it) }
            val bQuote = userTexts.firstOrNull { rule.b.containsMatchIn(it) }
            if (aQuote == null || bQuote == null) return@mapNotNull null
            PrecisionIssue(
                kind = PrecisionKind.CONTRADICTION,
                target = "col2",
                detail = rule.question,
                quoted = "「${aQuote.take(40)}」/「${bQuote.take(40)}」",
            )
        }

    private fun walkthrough(draft: Map<Int, List<String>>): List<PrecisionIssue> {
        val steps = draft[Cols.FIRST_STEPS].orEmpty().filter { it.isNotBlank() }
        if (steps.size >= 2) return emptyList()
        return listOf(
            PrecisionIssue(
                kind = PrecisionKind.WALKTHROUGH_GAP,
                target = "col4",
                detail = "第 4 栏只有 ${steps.size} 条动作，还不够能开工（至少 2 条具体动作）",
                quoted = "",
            ),
        )
    }

    private fun goalDrift(input: GateInput): PrecisionIssue? {
        val intentBigrams = bigrams(input.intent)
        if (intentBigrams.isEmpty()) return null
        val goalText = input.draft[Cols.GOAL].orEmpty().joinToString(" ")
        if (goalText.isBlank()) {
            return PrecisionIssue(
                kind = PrecisionKind.GOAL_DRIFT,
                target = "col1",
                detail = "第 1 栏还是空的：目标与成功标准一条都没落地",
                quoted = input.intent.take(40),
            )
        }
        val overlap = bigrams(goalText) intersect intentBigrams
        if (overlap.isNotEmpty()) return null
        return PrecisionIssue(
            kind = PrecisionKind.GOAL_DRIFT,
            target = "col1",
            detail = "你一开始说的是「${input.intent.take(14)}」，单子里写的是「${goalText.take(14)}」——这是同一件事吗？",
            quoted = input.intent.take(40),
        )
    }

    /**
     * 中文没有空格，用**相邻二字组**做相似度就够了（比"分词"稳，不依赖词典）。
     * 只保留文字/数字，标点与空格直接去掉，避免被格式差异干扰。
     */
    fun bigrams(text: String): Set<String> {
        val compact = text.filter { it.isLetterOrDigit() }
        if (compact.length < 2) return emptySet()
        return (0 until compact.length - 1)
            .map { compact.substring(it, it + 2) }
            .toSet()
    }

    companion object {
        /** 文本类缺口的默认观察窗口（轮）。见构造参数注释。 */
        const val DEFAULT_TEXT_SCAN_TURNS = 2
    }
}

data class GateInput(
    val scene: Scene,
    val intent: String,
    val turns: List<Turn>,
    val draft: Map<Int, List<String>>,
)

fun GateInput.userTexts(): List<String> =
    turns.filter { it.role == TurnRole.USER }.map { it.text }.filter { it.isNotBlank() }

/**
 * 他最近 [turns] 轮说过的话（正序）。文本类缺口只在这个窗口里扫——
 * 全历史重扫会让缺口永不消解，详见 [PrecisionGate] 构造参数。
 */
fun GateInput.recentUserTexts(turns: Int = PrecisionGate.DEFAULT_TEXT_SCAN_TURNS): List<String> =
    userTexts().takeLast(turns.coerceAtLeast(1))