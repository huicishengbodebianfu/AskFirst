package com.askfirst.domain.research

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.core.Clock
import com.askfirst.core.IdSource
import com.askfirst.core.SystemClock
import com.askfirst.domain.model.Evidence
import com.askfirst.domain.model.EvidenceKind
import com.askfirst.domain.model.Research
import com.askfirst.domain.model.ResearchCheck
import com.askfirst.domain.model.ResearchFailure
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.ResearchSource
import com.askfirst.domain.model.ResearchStatus
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.Verdict
import com.askfirst.research.FetchOutcome
import com.askfirst.research.GitHubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次调研的流水线（方案 §3.6 的四步：立题 → 取证 → 对照 → 回写建议）。
 * 立题在外面（要用户能删改），这里从「已经定了的问题」开始跑。
 *
 * 三条工程纪律：
 * - **60 秒硬上限**：到点就用手里的结果出报告，不装进度条（方案 §3.6.3 第 1 条）；
 * - **部分成功是正常态**：每个问题每类源各自记失败原因，最后如实标「证据覆盖 N/M」；
 * - **失败必须可见**：没有任何证据时不许产出「无人区」，只能说「没查成」。
 */
class ResearchPipeline(
    private val github: GitHubClient,
    private val judge: VerdictJudge,
    private val ids: IdSource,
    private val clock: Clock = SystemClock,
    private val budgetMs: Long = BUDGET_MS,
    private val readmePerQuestion: Int = 2,
) {

    fun run(input: ResearchInput): Flow<ResearchEvent> = flow {
        val start = clock.now()
        val evidence = mutableListOf<Evidence>()
        val failures = mutableListOf<ResearchFailure>()
        val rechecked = mutableSetOf<Int>()
        val authenticated = github.hasToken()

        emit(ResearchEvent.Step(if (authenticated) "开始查（用的是你填的 token）" else "开始查（没填 token，走匿名）"))

        // 60 秒是**真的**硬上限：整段取证包在 withTimeoutOrNull 里。
        // 只在问题之间看预算是不够的——一个卡住的请求（重试三次 × 单调用 30s）
        // 能把「最多等 60 秒」拖成 90 秒，用户看到的就是转圈卡死。
        val inTime = withTimeoutOrNull(budgetMs) {
            collectAll(input, evidence, failures, rechecked, start)
            true
        } == true
        if (!inTime) {
            failures += ResearchFailure(0, "全部", "60 秒预算用完了，剩下的没查完（手里这些照样出报告）")
        }

        val elapsedAfterCollect = clock.now() - start
        val remaining = budgetMs - elapsedAfterCollect

        // 6) 对照：让模型拿证据去撞这次的问题（一条证据都没有就别浪费 token）
        val verdicts: List<Verdict>
        var judgeError: AppError? = null
        when {
            evidence.isEmpty() -> verdicts = emptyList()

            remaining <= MIN_STEP_MS -> {
                // 硬上限到了就不再加一次模型调用：报告里如实写「没来得及对照」
                judgeError = AppError.ResearchRule("60 秒预算用完，没来得及对照证据。可以稍后再查一次。")
                verdicts = emptyList()
            }

            else -> {
                emit(ResearchEvent.Step("拿证据对照你的方案"))
                val judgeBudget = minOf(remaining, JUDGE_TIMEOUT_MS)
                val judged = withTimeoutOrNull(judgeBudget) {
                    judge.judge(
                        JudgeInput(
                            intent = input.intent,
                            sceneName = input.sceneName,
                            questions = input.questions,
                            evidence = evidence,
                            sheet = input.sheet,
                            allowUnexplored = input.recheckEnabled,
                        ),
                    )
                }
                when (judged) {
                    null -> {
                        judgeError = AppError.Timeout(judgeBudget)
                        verdicts = emptyList()
                    }

                    is AppResult.Ok -> verdicts = judged.value
                    is AppResult.Err -> {
                        judgeError = judged.error
                        verdicts = emptyList()
                    }
                }
            }
        }
        judgeError?.let { error -> failures += ResearchFailure(0, "对照", error.message.orEmpty()) }

        // 7) 归一化（白名单 + 四条硬规则）与机械检查
        val normalized = ResearchRules.normalize(
            verdicts = verdicts,
            evidence = evidence,
            questions = input.questions,
            rechecked = rechecked,
        )
        // 被驳回的判定必须留痕：`normalize` 的 warnings 就是"为什么不采纳"的人话
        // （"这条反模式没引到原话，我不替你下这个结论"）。原来直接扔掉，
        // 坏消息在报告里凭空消失，读者会以为一条没漏（docs/28 P2-4）。
        failures += normalized.warnings.map { warning ->
            ResearchFailure(warning.questionIdx, "判定没采纳", warning.rationale)
        }
        val research = Research(
            id = ids.newId("rs"),
            sessionId = input.sessionId,
            sheetId = input.sheetId,
            planId = input.planId,
            title = input.title,
            questions = input.questions,
            evidence = evidence,
            verdicts = normalized.verdicts,
            failures = failures,
            status = ResearchRules.statusOf(normalized.verdicts, failures),
            elapsedMs = clock.now() - start,
            source = ResearchSource.GITHUB,
            authenticated = authenticated,
            recheckedQuestions = rechecked.toList().sorted(),
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        val check = ResearchRules.check(research)
        if (research.evidence.isEmpty() && research.verdicts.isEmpty()) {
            emit(
                ResearchEvent.Failed(
                    judgeError ?: AppError.ResearchRule(
                        "这次一条证据都没拿到（可能是网络或限流）。报告里记下了原因，" +
                            "可以稍后再查，或者点「拿去桌面端查」用别的网络跑。",
                    ),
                    partial = research,
                ),
            )
            return@flow
        }
        emit(ResearchEvent.Done(research, check))
    }.flowOn(Dispatchers.IO)

    /**
     * 逐个问题取证。三个源各自记失败原因；命中为空时才做「换词复核」。
     * 被外面的 withTimeoutOrNull 打断是正常路径：已经装进 [evidence] 的照样算数。
     */
    private suspend fun FlowCollector<ResearchEvent>.collectAll(
        input: ResearchInput,
        evidence: MutableList<Evidence>,
        failures: MutableList<ResearchFailure>,
        rechecked: MutableSet<Int>,
        start: Long,
    ) {
        for ((index, question) in input.questions.withIndex()) {
            emit(ResearchEvent.Step("查第 ${index + 1} 个问题：${question.text}"))

            // 1) 仓库（「有人做过吗」）
            val repos = collect(
                question = question,
                source = "仓库检索",
                failures = failures,
                call = { github.searchRepos(question.keywords) },
                parse = { raw -> EvidenceParse.parseRepos(raw, question.idx) },
            )
            // 2) issue（「被抱怨过什么」「坑在哪」）
            val issues = collect(
                question = question,
                source = "issue 检索",
                failures = failures,
                call = { github.searchIssues(question.keywords) },
                parse = { raw -> EvidenceParse.parseIssues(raw, question.idx) },
            )
            // 3) 代码（要 token；没 token 只记一行，不假装查过）
            val code = collect(
                question = question,
                source = "代码检索",
                failures = failures,
                call = { github.searchCode(question.keywords) },
                parse = { raw -> EvidenceParse.parseCode(raw, question.idx) },
            )

            val found = repos + issues + code
            evidence += found
            emit(ResearchEvent.Collected(index + 1, input.questions.size, evidence.size))

            // 4) 读原文：只对前几个仓库抓 README（手机端不搬运全文）
            repos.filter { it.repoFullName != null }
                .sortedByDescending { it.stars }
                .take(readmePerQuestion)
                .forEach { repo ->
                    if (remainingMs(start) < MIN_STEP_MS) return@forEach
                    when (val outcome = github.readme(repo.repoFullName!!)) {
                        is FetchOutcome.Ok -> {
                            val text = EvidenceParse.readmeText(outcome.body)
                            if (!text.isNullOrBlank()) {
                                evidence += Evidence(
                                    id = EvidenceParse.evidenceId(question.idx, EvidenceKind.README, evidence.size + 1),
                                    questionIdx = question.idx,
                                    kind = EvidenceKind.README,
                                    title = "${repo.title} 的 README",
                                    url = repo.url,
                                    snippet = text,
                                    repoFullName = repo.repoFullName,
                                    stars = repo.stars,
                                )
                            }
                        }

                        is FetchOutcome.Failed -> failures += ResearchFailure(question.idx, "README", outcome.reason)
                    }
                }

            // 5) 换词复核：GitHub 内 0 命中时才做，做了才敢说「无人区」
            //
            // 注意顺序：`rechecked` 必须**在复核真的查成之后**才记账。
            // 记在请求之前的话，三条检索全失败 + 复核也失败时，rechecked 里已经有这个 idx，
            // ResearchRules 就会放行一条「无人区」—— 一个请求都没成功过的问题被写成
            // 「同类未检索到」，正好违反本文件头「失败必须可见」那条纪律（docs/28 P1-2）。
            if (found.isEmpty() && input.recheckEnabled && remainingMs(start) > MIN_STEP_MS) {
                emit(ResearchEvent.Step("换个说法再查一次：${question.text}"))
                when (val outcome = github.searchRepos(listOf(question.text.take(40)))) {
                    is FetchOutcome.Ok -> {
                        rechecked += question.idx
                        when (val parsed = EvidenceParse.parseRepos(outcome.body, question.idx)) {
                            is AppResult.Ok -> evidence += parsed.value
                            is AppResult.Err -> failures += ResearchFailure(
                                question.idx,
                                "换词复核",
                                parsed.error.message.orEmpty(),
                            )
                        }
                    }

                    is FetchOutcome.Failed -> failures += ResearchFailure(
                        question.idx,
                        "换词复核",
                        outcome.reason,
                    )
                }
            }
        }
    }

    private fun remainingMs(start: Long): Long = budgetMs - (clock.now() - start)

    /** 预算还剩多少（页面/诊断用；测试里直接看剩余毫秒）。 */
    fun remainingOf(start: Long): Long = remainingMs(start)

    private suspend fun collect(
        question: ResearchQuestion,
        source: String,
        failures: MutableList<ResearchFailure>,
        call: suspend () -> FetchOutcome,
        parse: (String) -> AppResult<List<Evidence>>,
    ): List<Evidence> = when (val outcome = call()) {
        is FetchOutcome.Ok -> when (val parsed = parse(outcome.body)) {
            is AppResult.Ok -> parsed.value
            is AppResult.Err -> {
                failures += ResearchFailure(question.idx, source, parsed.error.message.orEmpty())
                emptyList()
            }
        }

        is FetchOutcome.Failed -> {
            failures += ResearchFailure(question.idx, source, outcome.reason)
            emptyList()
        }
    }

    companion object {
        /** 一轮调研的硬上限（方案 §3.6.3：超了就出结果，不出进度条） */
        const val BUDGET_MS = 60_000L

        /** 模型对照这一步最多给 30s，剩下的留给取证 */
        const val JUDGE_TIMEOUT_MS = 30_000L

        /** 剩余预算低于这个值就不再发起新请求（省得半截超时） */
        const val MIN_STEP_MS = 4_000L
    }
}

/** 管道输入：问题清单已经在外面定好了（用户可删改）。 */
data class ResearchInput(
    val sessionId: String,
    val sheetId: String,
    val planId: String?,
    val intent: String,
    val sceneName: String?,
    val title: String,
    val questions: List<ResearchQuestion>,
    val sheet: Sheet?,
    /** 允许「换词复核」：关掉之后 GitHub 内没命中也只能说「未取证」 */
    val recheckEnabled: Boolean = true,
)

/** 对照这一步的能力抽象：真实实现调模型（[ResearchBuilder]），单测给假实现。 */
interface VerdictJudge {
    suspend fun judge(input: JudgeInput): AppResult<List<Verdict>>
}

data class JudgeInput(
    val intent: String,
    val sceneName: String?,
    val questions: List<ResearchQuestion>,
    val evidence: List<Evidence>,
    val sheet: Sheet?,
    val allowUnexplored: Boolean,
)

/** 管道事件：页面只认这些（进度 / 完成 / 失败）。 */
sealed interface ResearchEvent {
    data class Step(val text: String) : ResearchEvent

    data class Collected(val done: Int, val total: Int, val evidenceCount: Int) : ResearchEvent

    data class Done(val research: Research, val check: ResearchCheck) : ResearchEvent

    /** `partial` 非空说明「虽然失败了，但手里有点东西」，页面可以展示残本 */
    data class Failed(val error: AppError, val partial: Research?) : ResearchEvent
}
