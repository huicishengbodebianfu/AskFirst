package com.askfirst.domain.usecase

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.core.Clock
import com.askfirst.core.IdPrefix
import com.askfirst.core.IdSource
import com.askfirst.core.Text
import com.askfirst.core.valueOrNull
import com.askfirst.data.repo.CitationRepository
import com.askfirst.data.repo.HistoryRepository
import com.askfirst.data.repo.PlanRepository
import com.askfirst.data.repo.ResearchRepository
import com.askfirst.data.repo.SceneRepository
import com.askfirst.data.repo.SessionRepository
import com.askfirst.data.repo.SheetRepository
import com.askfirst.domain.engine.EngineStreamEvent
import com.askfirst.domain.engine.QuestionEngine
import com.askfirst.domain.engine.StateInput
import com.askfirst.domain.engine.StateMachine
import com.askfirst.domain.model.AskSession
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.EngineDecision
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.model.HistoryEntry
import com.askfirst.domain.model.HistoryFilter
import com.askfirst.domain.model.Plan
import com.askfirst.domain.model.PlanCheck
import com.askfirst.domain.model.PlanSectionDraft
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.SceneSummary
import com.askfirst.domain.model.SessionStatus
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetCheck
import com.askfirst.domain.model.SheetItem
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import com.askfirst.domain.plan.PlanBuilder
import com.askfirst.domain.plan.PlanEvent
import com.askfirst.domain.plan.PlanMarkdown
import com.askfirst.domain.plan.PlanMerge
import com.askfirst.domain.plan.PlanSkeleton
import com.askfirst.domain.plan.PlanValidator
import com.askfirst.domain.model.AppliedTo
import com.askfirst.domain.model.Research
import com.askfirst.domain.model.ResearchCheck
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.ResearchSource
import com.askfirst.domain.model.ResearchStatus
import com.askfirst.domain.model.Verdict
import com.askfirst.domain.prompt.ResearchQuestionContext
import com.askfirst.domain.research.OfflineResearch
import com.askfirst.domain.research.QuestionDrafting
import com.askfirst.domain.research.ResearchEvent
import com.askfirst.domain.research.ResearchInput
import com.askfirst.domain.research.ResearchMarkdown
import com.askfirst.domain.research.ResearchPipeline
import com.askfirst.domain.research.ResearchQuestionBuilder
import com.askfirst.domain.research.ResearchRules
import com.askfirst.domain.prompt.EngineContext
import com.askfirst.domain.prompt.PlanContext
import com.askfirst.domain.prompt.SheetContext
import com.askfirst.domain.sheet.PromptExporter
import com.askfirst.domain.sheet.MarkdownExporter
import com.askfirst.domain.sheet.SheetBuilder
import com.askfirst.domain.sheet.SheetEvent
import com.askfirst.domain.sheet.SheetValidator
import com.askfirst.domain.sheet.SourceCollector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

/**
 * 唯一对外门面：ViewModel 只认它，不碰 repository / provider / db。
 * `ask()` 内部完成"落用户消息 → 组上下文 → 引擎流式 → 落 AI 消息 → 生长确认单 → 回最新状态"。
 */
class AskFirstFacade(
    private val scenes: SceneRepository,
    private val sessions: SessionRepository,
    private val sheets: SheetRepository,
    private val historyRepo: HistoryRepository,
    private val citations: CitationRepository,
    private val engine: QuestionEngine,
    private val stateMachine: StateMachine,
    private val sheetBuilder: SheetBuilder,
    private val sheetValidator: SheetValidator,
    private val exporter: PromptExporter,
    private val markdownExporter: MarkdownExporter,
    private val plans: PlanRepository,
    private val planBuilder: PlanBuilder,
    private val planValidator: PlanValidator,
    private val planMarkdown: PlanMarkdown,
    // ---- M6 调研 ----
    private val researchRepo: ResearchRepository,
    private val researchQuestions: ResearchQuestionBuilder,
    private val researchPipeline: ResearchPipeline,
    private val researchMarkdown: ResearchMarkdown,
    /** M6：起调前看一眼有没有网（没网直接让用户走离线外包，别等三次超时） */
    private val network: com.askfirst.domain.research.NetworkGuard =
        com.askfirst.domain.research.NetworkGuard.Always,
    private val sourceCollector: SourceCollector = SourceCollector,
    private val ids: IdSource,
    private val clock: Clock,
) {

    suspend fun sceneSummaries(): List<SceneSummary> = scenes.summaries()

    suspend fun listScenes(): List<Scene> = scenes.summaries().mapNotNull { scenes.scene(it.id) }

    /** 首页「上次未完成的思路」 */
    suspend fun resumable(): AskSession? = sessions.resumable()

    /** M2 历史列表（按场景筛选 + 时间倒序 + 摘要）。 */
    fun history(filter: HistoryFilter): Flow<List<HistoryEntry>> = historyRepo.observe(filter)

    // ---------- M3：场景编辑 / 导入导出 ----------

    suspend fun sceneOf(sceneId: String): Scene? = scenes.scene(sceneId)

    suspend fun createScene(draft: com.askfirst.domain.model.SceneDraft): AppResult<Scene> = scenes.create(draft)

    suspend fun updateScene(sceneId: String, draft: com.askfirst.domain.model.SceneDraft): AppResult<Scene> =
        scenes.update(sceneId, draft)

    /** 复制一份可编辑的自建场景（内置场景的唯一修改路径）。 */
    suspend fun duplicateScene(sceneId: String): AppResult<Scene> = scenes.duplicate(sceneId)

    suspend fun deleteScene(sceneId: String): AppResult<Unit> {
        val scene = scenes.scene(sceneId) ?: return AppResult.Err(AppError.Storage("这套场景不见了"))
        val usedBy = sessions.countByScene(sceneId)
        if (usedBy > 0) {
            return AppResult.Err(
                AppError.SceneRule("还有 $usedBy 场思路用过这套场景，删了它们就回看不全了。留着，或者先删那些会话。"),
            )
        }
        return scenes.delete(sceneId)
    }

    suspend fun importSceneJson(text: String): AppResult<Scene> = scenes.importScene(text)

    /** 导出场景：文件名 + JSON 正文（分享面板用，与 M2 导出 MD 同一套路）。 */
    suspend fun exportSceneJson(sceneId: String): Pair<String, String> {
        val scene = scenes.scene(sceneId) ?: error("这套场景不见了")
        val base = scene.name.trim()
            .take(24)
            .replace(Regex("""[\\/:*?"<>|\s，。；：？！、（）【】《》]+"""), "_")
            .ifBlank { "scene" }
        return "askfirst-scene-$base.json" to scenes.exportJson(scene)
    }

    // ---------- M4：跨场景引用 ----------

    /** 引用选择页：关键词搜旧确认单（空 = 最近 30 份）。 */
    suspend fun searchCitations(keyword: String): List<com.askfirst.domain.model.CitationCandidate> =
        citations.search(keyword)

    /** 历史详情溯源：这份结论基于哪几份旧结论。 */
    suspend fun citationsOf(sessionId: String): List<com.askfirst.domain.model.CitationEntry> =
        citations.citedEntries(sessionId)

    /** 提问流顶部「带了 N 份旧结论」。 */
    suspend fun citationCountOf(sessionId: String): Int = citations.countOf(sessionId)

    /** 开新会话（可带旧结论，≤3 份，超出截断——UI 已拦，这里再拦一次）。 */
    suspend fun start(sceneId: String, intent: String, citedSheetIds: List<String> = emptyList()): AskSession {
        val session = sessions.start(sceneId, intent)
        if (citedSheetIds.isNotEmpty()) citations.cite(session.id, citedSheetIds)
        return session
    }

    suspend fun sessionView(sessionId: String): SessionView? {
        val session = sessions.byId(sessionId) ?: return null
        val scene = scenes.scene(session.sceneId) ?: return null
        val turns = sessions.transcript(sessionId)
        val sheet = sheets.latest(sessionId) ?: sheets.ensureSheet(sessionId)
        return SessionView(session, scene, turns, sheet)
    }

    /**
     * M5 生成方案文档。`resume = true` 时只补骨架里还缺的章节（已写好的原样保留）。
     * 流里给进度；最后给「落库后的方案 + 本地检查」。
     */
    fun generatePlan(sessionId: String, resume: Boolean = false): Flow<PlanOutcome> = flow {
        val session = sessions.byId(sessionId)
        val scene = session?.let { scenes.scene(it.sceneId) }
        if (session == null || scene == null) {
            emit(PlanOutcome.Failed(AppError.Storage("会话或场景数据丢了，没法生成方案"), canResume = false))
            return@flow
        }
        val turns = sessions.transcript(sessionId)
        if (turns.isEmpty()) {
            // 没有原话就没有出处，硬生成只会得到一份没法溯源的文档
            emit(
                PlanOutcome.Failed(
                    AppError.PlanRule("这场还没聊过，没有可溯源的原话。先聊两轮，再来生成方案。"),
                    canResume = false,
                ),
            )
            return@flow
        }
        val sheet = sheets.latest(sessionId) ?: sheets.ensureSheet(sessionId)
        val skeleton = PlanSkeleton.resolve(scene)
        val saved = plans.of(sessionId)
        val existing = if (resume) saved?.sorted().orEmpty() else emptyList()
        val existingDrafts = existing.map {
            PlanSectionDraft(heading = it.heading, body = it.body, sourceMessageIds = it.sourceMessageIds)
        }
        val missing = PlanMerge.missingHeadings(existingDrafts, skeleton)

        if (resume && missing.isEmpty()) {
            // 骨架已经齐了，再点「接着写」没有意义
            val check = planValidator.check(saved!!, skeleton, factsOf(turns, sheet))
            emit(PlanOutcome.Done(saved, check))
            return@flow
        }

        val context = PlanContext(
            scene = scene,
            intent = session.intent,
            turns = turns,
            sheet = sheets.latest(sessionId),
            skeleton = skeleton,
            existing = existing,
            missingHeadings = missing,
        )

        planBuilder.build(context, knownSourceIds = turns.map { it.id }.toSet()).collect { event ->
            when (event) {
                is PlanEvent.Progress -> emit(PlanOutcome.Progress(event.receivedChars))

                is PlanEvent.Drafted -> {
                    val drafts = if (resume) {
                        PlanMerge.merge(existingDrafts, event.draft.sections, skeleton)
                    } else {
                        event.draft.sections
                    }
                    if (drafts.isEmpty()) {
                        emit(PlanOutcome.Failed(AppError.Storage("这次一段都没生成出来"), canResume = resume))
                        return@collect
                    }
                    val title = event.draft.title.ifBlank {
                        saved?.title?.takeIf { it.isNotBlank() } ?: Text.truncate(session.intent, 20)
                    }
                    val plan = plans.save(
                        sessionId = sessionId,
                        sheetId = sheet.id,
                        title = title,
                        sections = drafts,
                    )
                    emit(PlanOutcome.Done(plan, planValidator.check(plan, skeleton, factsOf(turns, sheet))))
                }

                // 方案页没有「重试」按钮，换成它上面那三个按钮的说法
                is PlanEvent.Failed -> {
                    val friendly = if (event.error is AppError.BadJson) {
                        AppError.PlanRule(
                            "模型这次没按格式回：${event.error.reason}。" +
                                "已有的章节我留着，可以点「接着写剩下的」，或者重新生成整份。",
                        )
                    } else {
                        event.error
                    }
                    emit(PlanOutcome.Failed(friendly, event.canResume))
                }
            }
        }
    }

    /** 方案页初始化：方案 + 本地检查 + 场景名 + 骨架（页面要显示「还差哪几节」）。 */
    suspend fun planViewOf(sessionId: String): PlanView? {        val plan = plans.of(sessionId) ?: return null
        val session = sessions.byId(sessionId) ?: return null
        val scene = scenes.scene(session.sceneId) ?: return null
        val turns = sessions.transcript(sessionId)
        val sheet = sheets.latest(sessionId)
        val skeleton = PlanSkeleton.resolve(scene)
        return PlanView(
            plan = plan,
            check = planValidator.check(plan, skeleton, factsOf(turns, sheet)),
            sceneName = scene.name,
            skeleton = skeleton,
            missingHeadings = PlanMerge.missingHeadings(
                plan.sorted().map {
                    PlanSectionDraft(it.heading, it.body, it.sourceMessageIds)
                },
                skeleton,
            ),
        )
    }

    /** 还没生成过方案时，方案页也要能显示「这场是哪个场景」。 */
    suspend fun sceneNameOf(sessionId: String): String? {
        val session = sessions.byId(sessionId) ?: return null
        return scenes.scene(session.sceneId)?.name
    }

    /** 逐段删（写完立刻落库，退出重进不会长回来）。 */
    suspend fun deletePlanSection(planId: String, sectionId: String): Plan? =
        plans.deleteSection(planId, sectionId)

    /** 这段从哪来：把这一节的出处还原成当时的原话。 */
    suspend fun planSectionSources(sessionId: String, sectionId: String): List<Turn> {
        val plan = plans.of(sessionId) ?: return emptyList()
        val section = plan.sections.firstOrNull { it.id == sectionId } ?: return emptyList()
        val turns = sessions.transcript(sessionId)
        return sourceCollector.collect(turns, section.sourceMessageIds)
    }

    /** 落盘/分享用：文件名 + Markdown 正文。 */
    suspend fun exportPlan(planId: String): Pair<String, String> {
        val plan = plans.byId(planId) ?: error("这份方案不存在了")
        val view = sessionView(plan.sessionId)
        val turns = view?.turns.orEmpty()
        val now = clock.now()
        return planMarkdown.fileName(plan, now) to planMarkdown.export(plan, view?.scene?.name, turns, now)
    }

    /** 硬答案保护的语料：这场问答 + 已确认的条目（文档里的数字得在这里能找到）。 */
    private fun factsOf(turns: List<Turn>, sheet: Sheet?): String = buildString {
        turns.forEach { appendLine(it.text) }
        sheet?.allItems()?.forEach { appendLine(it.text) }
    }

    // ------------------------------------------------------------------ M6 调研

    /**
     * 立题：模型起草 3~6 个可检索问题（用户可删可改）。
     * 模型不可用就退到 [QuestionDrafting.fallback] —— 宁可问题粗糙，也不让流程空手
     * （「宁可残缺也不空手」是本功能的设计目标，docs/26 §3.10）。
     */
    suspend fun draftResearchQuestions(sessionId: String): AppResult<ResearchDraft> {
        val session = sessions.byId(sessionId)
            ?: return AppResult.Err(AppError.ResearchRule("这场会话不见了，回历史页重新进一次。"))
        val scene = scenes.scene(session.sceneId)
            ?: return AppResult.Err(AppError.ResearchRule("这套场景被删了，没法立题。"))
        val turns = sessions.transcript(sessionId)
        val sheet = sheets.latest(sessionId)
        val plan = plans.of(sessionId)

        val context = ResearchQuestionContext(
            scene = scene,
            intent = session.intent,
            turns = turns,
            sheet = sheet,
            planHeadings = plan?.sorted()?.map { it.heading }.orEmpty(),
        )

        return when (val drafted = researchQuestions.draft(context)) {
            is AppResult.Ok -> AppResult.Ok(
                ResearchDraft(
                    questions = QuestionDrafting.merge(
                        drafted.value,
                        QuestionDrafting.fallback(session.intent, sheet),
                    ),
                    usedFallback = drafted.value.size < QuestionDrafting.MIN_QUESTIONS,
                    note = null,
                ),
            )

            is AppResult.Err -> {
                val fallback = QuestionDrafting.fallback(session.intent, sheet)
                if (fallback.isEmpty()) {
                    AppResult.Err(
                        AppError.ResearchRule(
                            "这场还没聊出可查的词，模型这次也没帮上忙（${drafted.error.message}）。" +
                                "先再聊两轮，或者回来手动加两个问题。",
                        ),
                    )
                } else {
                    AppResult.Ok(
                        ResearchDraft(
                            questions = fallback,
                            usedFallback = true,
                            note = "模型这次没帮上忙，我先按你确认单里的词凑了 ${fallback.size} 个问题，你可以改。",
                        ),
                    )
                }
            }
        }
    }

    /**
     * 跑一次调研（立题已经定好）。60 秒硬上限在管道里，这里只负责落库与错误文案。
     * `recheck` 来自设置页：关掉之后 GitHub 内没命中也只能说「未取证」。
     */
    fun runResearch(
        sessionId: String,
        questions: List<ResearchQuestion>,
        recheck: Boolean = true,
    ): Flow<ResearchOutcome> = flow {
        val session = sessions.byId(sessionId)
        val scene = session?.let { scenes.scene(it.sceneId) }
        if (session == null || scene == null) {
            emit(
                ResearchOutcome.Failed(
                    AppError.ResearchRule("会话或场景数据丢了，没法查。回历史页重进一次。"),
                    partial = null,
                ),
            )
            return@flow
        }
        if (questions.isEmpty()) {
            emit(ResearchOutcome.Failed(AppError.ResearchRule("先立两个要查的问题。"), partial = null))
            return@flow
        }
        if (!network.isOnline()) {
            // 方案 §3.6.3 第 6 条：没网就别让用户干等三次超时重试
            emit(
                ResearchOutcome.Failed(
                    AppError.ResearchRule(
                        "手机现在没有网络。可以点下面「拿去桌面端查」，把这几个问题复制到能上网的电脑上跑；" +
                            "连上网之后再回来点「开始查」也行。",
                    ),
                    partial = null,
                ),
            )
            return@flow
        }

        val turns = sessions.transcript(sessionId)
        if (turns.isEmpty()) {
            emit(
                ResearchOutcome.Failed(
                    AppError.ResearchRule("这场还没聊过，没有可对照的原话。先聊两轮再来查。"),
                    partial = null,
                ),
            )
            return@flow
        }

        val sheet = sheets.latest(sessionId) ?: sheets.ensureSheet(sessionId)
        val plan = plans.of(sessionId)
        researchRepo.evictExpiredCache()

        val input = ResearchInput(
            sessionId = sessionId,
            sheetId = sheet.id,
            planId = plan?.id,
            intent = session.intent,
            sceneName = scene.name,
            title = Text.truncate(session.intent.ifBlank { scene.name }, 24),
            questions = questions,
            sheet = sheets.latest(sessionId),
            recheckEnabled = recheck,
        )

        researchPipeline.run(input).collect { event ->
            when (event) {
                is ResearchEvent.Step -> emit(ResearchOutcome.Progress(event.text))

                is ResearchEvent.Collected -> emit(
                    ResearchOutcome.Progress("查完 ${event.done}/${event.total} 个问题，手上有 ${event.evidenceCount} 条证据"),
                )

                is ResearchEvent.Done -> {
                    when (val saved = researchRepo.save(event.research)) {
                        is AppResult.Ok -> emit(ResearchOutcome.Done(saved.value, event.check))
                        // 存不下来就得说存不下来，不许拿残值冒充"存好了"
                        is AppResult.Err -> emit(ResearchOutcome.Failed(saved.error, partial = null))
                    }
                }

                is ResearchEvent.Failed -> {
                    // 残本尽力存：存不下也不影响"这次失败了"这个结论本身
                    val partial = event.partial?.let { researchRepo.save(it).valueOrNull() }
                    emit(ResearchOutcome.Failed(event.error, partial))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun researchOf(sessionId: String): Research? = researchRepo.latest(sessionId)

    /** 报告页要的机械检查（每次重新算，不落库：规则改了立刻生效）。 */
    suspend fun researchCheck(research: Research): ResearchCheck = ResearchRules.check(research)

    /** 确认单页第 2/5 栏下面那两块「调研补充」。 */
    suspend fun researchAppends(sessionId: String): List<Verdict> = researchRepo.appliedVerdicts(sessionId)

    /** 「这条结论放哪一栏」：只有 col2 / col5 / 附录三个选项（第 3 栏没有入口）。 */
    suspend fun moveVerdict(verdictId: String, target: AppliedTo): AppResult<Unit> =
        researchRepo.moveVerdict(verdictId, target)

    /** 报告落盘/分享：文件名 + Markdown 正文。 */
    suspend fun exportResearch(researchId: String): Pair<String, String> {
        val research = researchRepo.byId(researchId) ?: error("这次调研的记录不见了")
        val session = sessions.byId(research.sessionId)
        val scene = session?.let { scenes.scene(it.sceneId) }
        val now = clock.now()
        return researchMarkdown.fileName(research, session?.intent.orEmpty(), now) to
            researchMarkdown.export(research, scene?.name, session?.intent.orEmpty(), now)
    }

    /** 离线外包：把立题结果打包成一段提示词（复制/分享到桌面端跑）。 */
    suspend fun exportResearchPrompt(sessionId: String, questions: List<ResearchQuestion>): String {
        val session = sessions.byId(sessionId)
        return OfflineResearch.promptOf(session?.intent.orEmpty(), questions)
    }

    /** 离线外包回来：粘回的 JSON 走同一套解析与四条硬规则，落库成一次「离线调研」。 */
    suspend fun importResearchResult(
        sessionId: String,
        questions: List<ResearchQuestion>,
        raw: String,
    ): AppResult<Research> {
        val session = sessions.byId(sessionId)
            ?: return AppResult.Err(AppError.ResearchRule("这场会话不见了。"))
        val sheet = sheets.latest(sessionId) ?: sheets.ensureSheet(sessionId)
        val bundle = when (val parsed = OfflineResearch.parseBundle(raw, questions)) {
            is AppResult.Ok -> parsed.value
            is AppResult.Err -> return AppResult.Err(
                AppError.ResearchRule(
                    "粘回来的内容用不了：${parsed.error.message}。要的是那段 JSON（evidence + verdicts）。",
                ),
            )
        }
        val normalized = ResearchRules.normalize(
            verdicts = bundle.verdicts,
            evidence = bundle.evidence,
            questions = questions,
            // 离线包是"在桌面端查完粘回来的"：复核这一步在那边做过，本机无法再验。
            // 所以四个硬规则里"换词复核"这一条按已完成处理，但报告的来源行会如实写
            // 「源：离线跑完粘回来的结果」，读者能看出这份结论没在本机复核过。
            rechecked = questions.map { it.idx }.toSet(),
        )
        // 被驳回的判定留痕（同 ResearchPipeline：糟糕消息不许静默消失）
        val warningsAsFailures = normalized.warnings.map { warning ->
            com.askfirst.domain.model.ResearchFailure(warning.questionIdx, "判定没采纳", warning.rationale)
        }
        val now = clock.now()
        val research = Research(
            id = ids.newId(IdPrefix.RESEARCH),
            sessionId = sessionId,
            sheetId = sheet.id,
            planId = plans.of(sessionId)?.id,
            title = Text.truncate(session.intent.ifBlank { "这次调研" }, 24),
            questions = questions,
            evidence = bundle.evidence,
            verdicts = normalized.verdicts,
            failures = warningsAsFailures,
            status = ResearchStatus.DONE,
            elapsedMs = 0,
            source = ResearchSource.OFFLINE,
            authenticated = false,
            recheckedQuestions = questions.map { it.idx }.toSet().toList().sorted(),
            createdAt = now,
            updatedAt = now,
        )
        return researchRepo.save(research)
    }

    /** 只要当前确认单（提问流里的"看一眼单子"）。 */
    suspend fun sheetOf(sessionId: String): Sheet = sheets.latest(sessionId) ?: sheets.ensureSheet(sessionId)

    /**
     * 提问一轮。`answer == null` 表示跳过这题（也落库，AI 看到跳过标记会换题）。
     */
    fun ask(sessionId: String, answer: String?): Flow<TurnOutcome> =
        askInternal(sessionId, answer, appendUser = true)

    /** 上一轮失败后重试：**不重复写用户消息**，直接拿现有会话再问一次。 */
    fun retry(sessionId: String): Flow<TurnOutcome> =
        askInternal(sessionId, null, appendUser = false)

    private fun askInternal(
        sessionId: String,
        answer: String?,
        appendUser: Boolean,
    ): Flow<TurnOutcome> = flow {
        val session = sessions.byId(sessionId)
        if (session == null) {
            emit(TurnOutcome.Failed(AppError.Storage("这个会话不存在了"), canRetry = false))
            return@flow
        }
        val scene = scenes.scene(session.sceneId)
        if (scene == null) {
            emit(TurnOutcome.Failed(AppError.Storage("场景数据丢失，无法继续"), canRetry = false))
            return@flow
        }

        if (appendUser) {
            sessions.appendUser(sessionId, answer ?: SKIP_MARKER)
        }

        val turns = sessions.transcript(sessionId)
        val covered = turns.flatMap { it.covered }.toSet()
        val sheet = sheets.latest(sessionId) ?: sheets.ensureSheet(sessionId)
        val draft = sheet.items.mapValues { (_, items) -> items.map { it.text } }

        val context = EngineContext(
            scene = scene,
            intent = session.intent,
            state = session.state,
            covered = covered,
            turns = turns,
            draftColumns = draft,
            // M4：把引用的旧确认单摘要注入（只给摘要，不给全文）
            referencedSheets = citations.digestsOf(sessionId),
        )

        var settled = false
        engine.nextTurn(context).collect { event ->
            when (event) {
                is EngineStreamEvent.Progress -> emit(TurnOutcome.Progress(event.receivedChars))

                is EngineStreamEvent.Decided -> {
                    val grown = sheets.appendItems(sheet.id, event.decision.sheetDeltas)
                    // 状态要在"这一轮条目落进单子之后"再判：不然刚填满第 4 栏的这一轮
                    // 还会被判成"动作不够"（引擎侧是拿本轮之前的草稿算的）。
                    val grownDraft = grown.items.mapValues { (_, items) -> items.map { it.text } }
                    val turnsAfterAi = sessions.transcript(sessionId)
                    val verdict = stateMachine.evaluate(
                        StateInput(
                            scene = scene,
                            intent = session.intent,
                            turns = turnsAfterAi,
                            draft = grownDraft,
                            // 覆盖度要用**累计**值：模型每轮"重列全部已答 id"只是提示词里的请求，
                            // 代码没保证。这里传并集，模型偶尔漏列一个必问题也不会把状态打回 GATHERING。
                            covered = covered + event.decision.covered,
                            // 引擎的 state 是在"本轮条目落库前"算的，已经过时；
                            // 这里只按落库后的真实草稿裁决（modelSuggested = null）。
                            modelSuggested = null,
                        ),
                    )
                    val decision = event.decision.copy(
                        state = verdict.state,
                        questions = if (verdict.state == EngineState.READY) emptyList() else event.decision.questions,
                        issues = (event.decision.issues + verdict.issues).distinctBy { it.kind to it.detail },
                        blockReason = if (verdict.state == EngineState.BLOCKED) {
                            event.decision.blockReason
                                ?: verdict.issues.firstOrNull { it.kind == com.askfirst.domain.model.PrecisionKind.CONTRADICTION }?.detail
                        } else {
                            null
                        },
                    )
                    sessions.appendAi(
                        sessionId = sessionId,
                        text = decision.toAssistantText(),
                        questions = decision.questions,
                        covered = decision.covered,
                        stateAfter = decision.state,
                    )
                    sessions.patchState(sessionId, decision.state, SessionStatus.OPEN)
                    settled = true
                    emit(
                        TurnOutcome.Done(
                            turns = sessions.transcript(sessionId),
                            decision = decision,
                            sheet = grown,
                        ),
                    )
                }

                is EngineStreamEvent.Failed -> {
                    settled = true
                    emit(TurnOutcome.Failed(event.error, canRetry = true))
                }
            }
        }

        if (!settled) {
            emit(TurnOutcome.Failed(AppError.Network("模型没有给出任何结果"), canRetry = true))
        }
    }

    /**
     * 出单（一次调用生成整份五栏，模型出的条目 + 用户改过的条目合并）。
     */
    fun buildSheet(sessionId: String): Flow<SheetOutcome> = flow {
        val session = sessions.byId(sessionId)
        val scene = session?.let { scenes.scene(it.sceneId) }
        if (session == null || scene == null) {
            emit(SheetOutcome.Failed(AppError.Storage("会话或场景数据丢了，没法出单"), canFallback = false))
            return@flow
        }
        val turns = sessions.transcript(sessionId)
        val sheet = sheets.latest(sessionId) ?: sheets.ensureSheet(sessionId)
        val context = SheetContext(
            scene = scene,
            intent = session.intent,
            turns = turns,
            draftColumns = sheet.items.mapValues { (_, items) -> items.map { it.text } },
        )

        sheetBuilder.build(context, sheet).collect { event ->
            when (event) {
                is SheetEvent.Progress -> emit(SheetOutcome.Progress(event.receivedChars))

                is SheetEvent.Built -> {
                    val saved = sheets.replaceGenerated(
                        sheetId = sheet.id,
                        items = event.sheet.allItems(),
                        summary = event.summary.ifBlank { Text.truncate(session.intent, 24) },
                    )
                    emit(SheetOutcome.Done(saved, sheetValidator.check(saved)))
                }

                is SheetEvent.Failed -> emit(SheetOutcome.Failed(event.error, event.canFallback))
            }
        }
    }

    /** 模型不可用时的降级：用手里的内容直接出单，并在第 5 栏如实写明"这栏是空的"。 */
    suspend fun fallbackSheet(sessionId: String): SheetOutcome.Done {
        val session = sessions.byId(sessionId)
            ?: return SheetOutcome.Done(
                sheets.ensureSheet(sessionId),
                sheetValidator.check(sheets.ensureSheet(sessionId)),
            )
        val sheet = sheets.latest(sessionId) ?: sheets.ensureSheet(sessionId)
        val items = sheet.allItems().toMutableList()
        val lastUser = sessions.transcript(sessionId).lastOrNull { it.role == TurnRole.USER }
        if (sheet.column(Cols.OPEN_QUESTIONS).isEmpty() && lastUser != null) {
            items += SheetItem(
                id = ids.newId(IdPrefix.ITEM),
                sheetId = sheet.id,
                col = Cols.OPEN_QUESTIONS,
                order = 0,
                text = "模型这次没连上，这一栏只按已说过的话拼的，还没补全。",
                sourceMessageIds = listOf(lastUser.id),
                edited = false,
            )
        }
        val saved = sheets.replaceGenerated(sheet.id, items, Text.truncate(session.intent, 24))
        return SheetOutcome.Done(saved, sheetValidator.check(saved))
    }

    /**
     * 用户手改条目：记一笔"这条改过"，并且**把已经可以出单的会话退回收集状态**。
     *
     * 文档承诺的 `ready → gathering`（改了已确认内容就要重新确认）原来挂在
     * `StateInput.userEditedConfirmed` 上，而生产代码从来没把它置 true，
     * 那条规则等于不存在（docs/28 P2-8）。这里改在真正发生编辑的地方执行，天然只触发一次。
     *
     * 只回退「还能出单（READY+OPEN）」的会话；已经收单（DONE）的不动——
     * 改个错别字不该把整场会话重新打开。
     */
    suspend fun editItem(itemId: String, text: String) {
        val item = sheets.itemById(itemId) ?: return
        sheets.editItem(itemId, text)
        val sheet = sheets.byId(item.sheetId) ?: return
        val session = sessions.byId(sheet.sessionId) ?: return
        if (session.state == EngineState.READY && session.status == SessionStatus.OPEN) {
            sessions.patchState(sheet.sessionId, EngineState.GATHERING, SessionStatus.OPEN)
        }
    }

    /** 「复制成提示词」的正文（逐字模板见 docs/04 §3.2）。 */
    suspend fun exportPrompt(sheetId: String): String {
        val sheet = sheets.byId(sheetId) ?: error("这张确认单不存在了")
        val session = sessions.byId(sheet.sessionId)
        return exporter.export(session?.intent.orEmpty(), sheet)
    }

    /** 「点条目看出处」：把这条的 sourceMessageIds 还原成当时的原话。 */
    suspend fun sourceTurns(sessionId: String, itemId: String): List<Turn> {
        val item = sheets.itemById(itemId) ?: return emptyList()
        val turns = sessions.transcript(sessionId)
        return sourceCollector.collect(turns, item.sourceMessageIds)
    }

    suspend fun markDone(sessionId: String) {
        sessions.patchState(sessionId, EngineState.READY, SessionStatus.DONE)
    }

    /** M2「接着深挖」：已存下的会话回退 open，回提问流继续问（条目/手改保留）。 */
    suspend fun reopen(sessionId: String) {
        val session = sessions.byId(sessionId) ?: return
        val state = if (session.status == SessionStatus.DONE) EngineState.GATHERING else session.state
        sessions.patchState(sessionId, state, SessionStatus.OPEN)
    }

    /** M2 导出：整场会话（五栏 + 问答）转 Markdown 正文。 */
    suspend fun exportMarkdown(sessionId: String): Pair<String, String> {
        val view = sessionView(sessionId) ?: error("这个会话不存在了")
        val body = markdownExporter.export(view.scene, view.session, view.sheet, view.turns)
        return markdownExporter.fileName(view.session) to body
    }

    suspend fun patchState(sessionId: String, state: EngineState) {
        sessions.patchState(sessionId, state, SessionStatus.OPEN)
    }

    companion object {
        /** 跳过标记：AI 看到它就知道"这题他没答，别重复问"。 */
        const val SKIP_MARKER = "（跳过了这个问题）"
    }
}

data class SessionView(
    val session: AskSession,
    val scene: Scene,
    val turns: List<Turn>,
    val sheet: Sheet,
)

/** 方案页要的一切（方案 + 检查 + 场景名 + 骨架 + 还差哪几节）。 */
data class PlanView(
    val plan: Plan,
    val check: PlanCheck,
    val sceneName: String,
    val skeleton: List<String>,
    val missingHeadings: List<String>,
)

sealed interface PlanOutcome {
    data class Progress(val receivedChars: Int) : PlanOutcome

    data class Done(val plan: Plan, val check: PlanCheck) : PlanOutcome

    data class Failed(val error: AppError, val canResume: Boolean) : PlanOutcome
}

/** 立题结果。`usedFallback` 为真时页面上要说一句「模型没帮上忙，我先按你确认单里的词凑的」。 */
data class ResearchDraft(
    val questions: List<ResearchQuestion>,
    val usedFallback: Boolean,
    val note: String?,
)

/** 调研页的流事件（只给人话，别把内部阶段名透出去）。 */
sealed interface ResearchOutcome {
    data class Progress(val text: String) : ResearchOutcome

    data class Done(val research: Research, val check: ResearchCheck) : ResearchOutcome

    /** `partial` 非空 = 虽然失败了但手里有半份证据，页面可以让他先看着 */
    data class Failed(val error: AppError, val partial: Research?) : ResearchOutcome
}

sealed interface SheetOutcome {
    data class Progress(val receivedChars: Int) : SheetOutcome
    data class Done(val sheet: Sheet, val check: SheetCheck) : SheetOutcome
    data class Failed(val error: AppError, val canFallback: Boolean) : SheetOutcome
}

sealed interface TurnOutcome {
    /** 只有进度（字符数），从来不是要显示的正文 */
    data class Progress(val receivedChars: Int) : TurnOutcome

    data class Done(
        val turns: List<Turn>,
        val decision: EngineDecision,
        val sheet: Sheet,
    ) : TurnOutcome

    data class Failed(val error: AppError, val canRetry: Boolean) : TurnOutcome
}


