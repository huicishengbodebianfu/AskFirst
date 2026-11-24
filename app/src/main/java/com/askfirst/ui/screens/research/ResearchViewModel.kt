package com.askfirst.ui.screens.research

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.core.AppResult
import com.askfirst.data.prefs.SettingsStore
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.AppliedTo
import com.askfirst.domain.model.Research
import com.askfirst.domain.model.ResearchCheck
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.research.QuestionDrafting
import com.askfirst.domain.usecase.AskFirstFacade
import com.askfirst.domain.usecase.ResearchOutcome
import com.askfirst.ui.SafExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 调研页的四种状态：立题 / 进度 / 报告 / 失败。 */
enum class ResearchPhase { DRAFT, RUNNING, REPORT, FAILED }

/**
 * 调研页的全部状态。
 *
 * 三条界面纪律写在字段上：
 * 1. [progress] 只是几行进度，页面只显示最后三行（进度不是结果，别堆满屏）；
 * 2. [partial] 为真时报告照渲染，但顶部必须标「残缺的报告」；
 * 3. 第 3 栏没有任何落点字段——[AppliedTo] 里就没有第 3 栏。
 */
data class ResearchUiState(
    val loading: Boolean = true,
    val phase: ResearchPhase = ResearchPhase.DRAFT,
    /** 立题：页面上那几张问题卡（可改、可删、可加） */
    val questions: List<ResearchQuestion> = emptyList(),
    val drafting: Boolean = false,
    /** 模型没帮上忙 / 用了兜底时的说明（顶部那句 note） */
    val draftNote: String? = null,
    val draftError: String? = null,
    /** 进度：一行行往下堆，页面只显示最后三行 */
    val progress: List<String> = emptyList(),
    /** 这一次（或上一次）查出来的那份报告 */
    val research: Research? = null,
    val check: ResearchCheck? = null,
    /** 上次进来查过的那份：立题页给一个「看上次那份报告」的入口 */
    val previous: Research? = null,
    /** 残缺的报告：失败了但手里有半份证据 */
    val partial: Boolean = false,
    val failure: String? = null,
    val movingVerdictId: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val savedNote: String? = null,
    /** 上次落盘的目录（和方案页共用一份记忆） */
    val exportDir: String? = null,
    /** 拉起分享面板用的正文（消费后清空） */
    val share: Pair<String, String>? = null,
    /** 桌面端离线外包弹层 */
    val offlineOpen: Boolean = false,
    val offlinePrompt: String? = null,
    val offlineInput: String = "",
    val offlineBusy: Boolean = false,
    val offlineError: String? = null,
) {
    val running: Boolean get() = phase == ResearchPhase.RUNNING

    val canStart: Boolean get() = questions.isNotEmpty() && !running

    val atQuestionLimit: Boolean get() = questions.size >= QuestionDrafting.MAX_QUESTIONS
}

/**
 * M6 调研页：立题（可改可删）→ 查（可取消）→ 报告（分组 + 放哪 + 保存/分享/再查/拿去桌面端查）。
 * 落盘复用 [SafExport]，ContentResolver 由屏幕传进来，ViewModel 不碰 Context。
 */
class ResearchViewModel(
    private val facade: AskFirstFacade,
    private val settingsStore: SettingsStore,
    private val sessionId: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(ResearchUiState())
    val ui: StateFlow<ResearchUiState> = _ui.asStateFlow()

    private var runJob: Job? = null

    /** 每次开始/取消都换号：旧的那次就算晚一步回来，也不许再改界面。 */
    private var runId = 0

    init {
        viewModelScope.launch {
            val dir = settingsStore.current().planExportDir
            val previous = facade.researchOf(sessionId)
            _ui.update { it.copy(exportDir = dir, previous = previous) }
        }
        refreshDraft()
    }

    // ------------------------------------------------------------------ 立题

    /** 让模型起草问题；模型不可用时门面自己会给兜底问题（页面照常可用）。 */
    fun refreshDraft() {
        if (_ui.value.drafting) return
        viewModelScope.launch {
            _ui.update { it.copy(drafting = true, draftError = null, loading = true) }
            when (val result = facade.draftResearchQuestions(sessionId)) {
                is AppResult.Ok -> {
                    val draft = result.value
                    _ui.update {
                        it.copy(
                            loading = false,
                            drafting = false,
                            phase = ResearchPhase.DRAFT,
                            questions = draft.questions,
                            draftNote = draft.note ?: if (draft.usedFallback) {
                                "这次凑得比较粗：问题是从你确认单里的词拼的，你可以改了再查。"
                            } else {
                                null
                            },
                            draftError = null,
                        )
                    }
                }

                is AppResult.Err -> _ui.update {
                    it.copy(
                        loading = false,
                        drafting = false,
                        phase = ResearchPhase.DRAFT,
                        draftError = result.error.message,
                    )
                }
            }
        }
    }

    /**
     * 改一个问题（关键词保留：换说法不该把已经想好的检索词弄丢）。
     * 返回 false = 没改成（太短），调用方**不许**关弹窗，否则用户敲的字就白丢了（docs/28 P2-16）。
     */
    fun updateQuestion(idx: Int, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 6) {
            message("问题再写具体一点（太笼统的词搜出来都是噪音）。", isError = true)
            return false
        }
        _ui.update { state ->
            state.copy(
                questions = state.questions.map { question ->
                    if (question.idx == idx) question.copy(text = trimmed) else question
                },
                message = "改好了。",
                messageIsError = false,
            )
        }
        return true
    }

    fun removeQuestion(idx: Int) {
        _ui.update { state ->
            val left = state.questions.filterNot { it.idx == idx }
            state.copy(
                questions = renumber(left),
                message = if (left.isEmpty()) "问题都删光了，至少留一个才能查。" else "删掉了一个问题。",
                messageIsError = left.isEmpty(),
            )
        }
    }

    /** 手动加一个问题：关键词留空就按问题文字自己抽（[QuestionDrafting.keywordsOf]）。加了返回 true。 */
    fun addQuestion(text: String, keywordsDraft: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 6) {
            message("问题太短了：写成一句能被搜出来的话（比如「首屏图片缓存一般怎么分层」）。", isError = true)
            return false
        }
        if (_ui.value.atQuestionLimit) {
            message("最多 ${QuestionDrafting.MAX_QUESTIONS} 个问题，先删一个再加。", isError = true)
            return false
        }
        val keywords = keywordsDraft
            .split(Regex("""[，,、/|;；\s]+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { QuestionDrafting.keywordsOf(trimmed) }
        if (keywords.isEmpty()) {
            message("这个问题抽不出能搜的词。改个说法，或者在关键词那格里手填一个。", isError = true)
            return false
        }
        _ui.update { state ->
            val added = state.questions + ResearchQuestion(
                idx = 0,
                text = trimmed,
                keywords = keywords.take(4),
                why = "你自己加的问题",
            )
            state.copy(
                questions = renumber(added),
                message = "加上了。",
                messageIsError = false,
            )
        }
        return true
    }

    // ------------------------------------------------------------------ 查

    /** 开始查：60 秒硬上限在管道里，这里只负责进度与「能取消」。 */
    fun start() {
        val state = _ui.value
        if (state.running) return
        if (state.questions.isEmpty()) {
            message("先立两个要查的问题。", isError = true)
            return
        }
        val questions = state.questions
        runId += 1
        val myRun = runId
        runJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    phase = ResearchPhase.RUNNING,
                    progress = emptyList(),
                    partial = false,
                    failure = null,
                    message = null,
                    messageIsError = false,
                    savedNote = null,
                )
            }
            val recheck = settingsStore.current().researchRecheck
            try {
                facade.runResearch(sessionId, questions, recheck).collect { outcome ->
                    if (myRun != runId) return@collect
                    when (outcome) {
                        is ResearchOutcome.Progress -> _ui.update { current ->
                            current.copy(progress = (current.progress + outcome.text).takeLast(PROGRESS_KEEP))
                        }

                        is ResearchOutcome.Done -> {
                            val dir = settingsStore.current().planExportDir
                            _ui.update {
                                it.copy(
                                    phase = ResearchPhase.REPORT,
                                    partial = false,
                                    failure = null,
                                    research = outcome.research,
                                    check = outcome.check,
                                    previous = outcome.research,
                                    questions = outcome.research.questions.ifEmpty { questions },
                                    progress = emptyList(),
                                    exportDir = dir,
                                )
                            }
                        }

                        is ResearchOutcome.Failed -> {
                            val partialReport = outcome.partial
                            val partialCheck = partialReport?.let { facade.researchCheck(it) }
                            val dir = if (partialReport != null) settingsStore.current().planExportDir else null
                            _ui.update {
                                it.copy(
                                    phase = if (partialReport != null) ResearchPhase.REPORT else ResearchPhase.FAILED,
                                    partial = partialReport != null,
                                    failure = outcome.error.message,
                                    research = partialReport ?: it.research,
                                    check = partialCheck ?: it.check,
                                    previous = partialReport ?: it.previous,
                                    progress = emptyList(),
                                    exportDir = dir ?: it.exportDir,
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (myRun == runId) {
                    _ui.update {
                        it.copy(
                            phase = ResearchPhase.FAILED,
                            partial = false,
                            progress = emptyList(),
                            failure = "查到一半断掉了（${t.message ?: "手机这边出了点状况"}）。" +
                                "再点一次「再查一次」，或者改用桌面端查。",
                        )
                    }
                }
            }
        }
    }

    /** 取消：用户不该被 60 秒锁在进度页上。手里没存下来的半份证据就放弃。 */
    fun cancelRun() {
        if (!_ui.value.running) return
        runId += 1
        runJob?.cancel()
        runJob = null
        _ui.update {
            it.copy(
                phase = ResearchPhase.DRAFT,
                progress = emptyList(),
                partial = false,
                failure = null,
                message = "已经停了。再查一次会从头开始。",
                messageIsError = false,
            )
        }
    }

    /** 「再查一次」：回立题，问题全留着（改哪条改哪条，改完再查）。 */
    fun backToDraft() {
        runId += 1
        runJob?.cancel()
        runJob = null
        _ui.update {
            it.copy(
                phase = ResearchPhase.DRAFT,
                progress = emptyList(),
                partial = false,
                failure = null,
                message = null,
                messageIsError = false,
                savedNote = null,
            )
        }
    }

    /** 立题页「看上次那份报告」：不重查，把上一次的记录调出来看。 */
    fun showPrevious() {
        val previous = _ui.value.previous ?: return
        viewModelScope.launch {
            val check = facade.researchCheck(previous)
            _ui.update {
                it.copy(
                    phase = ResearchPhase.REPORT,
                    partial = false,
                    failure = null,
                    research = previous,
                    check = check,
                    questions = previous.questions.ifEmpty { it.questions },
                )
            }
        }
    }

    // ------------------------------------------------------------------ 报告上的操作

    /** 「放哪」：只可能去第 2 栏、第 5 栏或附录（第 3 栏没有入口）。成功后重新读一遍。 */
    fun moveVerdict(verdictId: String, target: AppliedTo) {
        viewModelScope.launch {
            _ui.update { it.copy(movingVerdictId = verdictId, message = null) }
            when (val result = facade.moveVerdict(verdictId, target)) {
                is AppResult.Ok -> {
                    val fresh = facade.researchOf(sessionId)
                    val check = fresh?.let { facade.researchCheck(it) }
                    _ui.update {
                        it.copy(
                            movingVerdictId = null,
                            research = fresh ?: it.research,
                            previous = fresh ?: it.previous,
                            check = check ?: it.check,
                            message = when (target) {
                                AppliedTo.COL2 -> "这一条挪到确认单第 2 栏下面了。"
                                AppliedTo.COL5 -> "这一条挪到确认单第 5 栏下面了。"
                                AppliedTo.APPENDIX -> "这一条只留在报告里，不进确认单。"
                            },
                            messageIsError = false,
                        )
                    }
                }

                is AppResult.Err -> _ui.update {
                    it.copy(
                        movingVerdictId = null,
                        message = result.error.message + " 再点一次试试；还不行就先把报告存下来。",
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /** 报告写进 SAF 目录（文件名与正文都由门面给，页面不自己拼）。 */
    fun saveReport(resolver: ContentResolver, treeUri: Uri) {
        val research = _ui.value.research ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SafExport.rememberPermission(resolver, treeUri)
                    val (fileName, body) = facade.exportResearch(research.id)
                    fileName to SafExport.write(resolver, treeUri, fileName, body)
                }
            }
            result.onSuccess { (_, written) ->
                settingsStore.setPlanExportDir(treeUri.toString())
                _ui.update {
                    it.copy(
                        exportDir = treeUri.toString(),
                        savedNote = "已存到：${SafExport.folderLabel(treeUri)}/$written",
                        message = null,
                        messageIsError = false,
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        savedNote = null,
                        message = "存不进去：${error.message ?: "换个文件夹试试"}",
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /** 上次的目录还能写就直接写（权限被系统回收了就让屏幕重新选）。 */
    fun saveToRemembered(resolver: ContentResolver): Boolean {
        val dir = _ui.value.exportDir ?: return false
        val uri = runCatching { Uri.parse(dir) }.getOrNull() ?: return false
        if (!SafExport.hasPermission(resolver, uri)) return false
        saveReport(resolver, uri)
        return true
    }

    fun prepareShare() {
        val research = _ui.value.research ?: return
        viewModelScope.launch {
            runCatching { facade.exportResearch(research.id) }
                .onSuccess { pair -> _ui.update { it.copy(share = pair) } }
                .onFailure { error ->
                    _ui.update {
                        it.copy(
                            message = "这份报告取不出来了（${error.message ?: "记录可能被清掉"}）。先存到文件夹，再从文件夹里发。",
                            messageIsError = true,
                        )
                    }
                }
        }
    }

    fun consumeShare() = _ui.update { it.copy(share = null) }

    // ------------------------------------------------------------------ 桌面端离线外包

    fun openOffline() {
        viewModelScope.launch {
            _ui.update { it.copy(offlineOpen = true, offlineError = null) }
            val prompt = facade.exportResearchPrompt(sessionId, _ui.value.questions)
            _ui.update { it.copy(offlinePrompt = prompt) }
        }
    }

    fun closeOffline() = _ui.update { it.copy(offlineOpen = false, offlineError = null) }

    fun onOfflineInputChange(text: String) =
        _ui.update { it.copy(offlineInput = text, offlineError = null) }

    /** 桌面端跑完粘回来 → 同一套规则归一化 → 直接渲染报告。 */
    fun importOffline() {
        val state = _ui.value
        if (state.offlineBusy) return
        if (state.offlineInput.isBlank()) {
            _ui.update { it.copy(offlineError = "还什么都没粘。把桌面端跑出来的那段整段贴进来。") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(offlineBusy = true, offlineError = null) }
            when (val result = facade.importResearchResult(sessionId, state.questions, state.offlineInput)) {
                is AppResult.Ok -> {
                    val research = result.value
                    val check = facade.researchCheck(research)
                    val dir = settingsStore.current().planExportDir
                    _ui.update {
                        it.copy(
                            offlineBusy = false,
                            offlineOpen = false,
                            offlineInput = "",
                            offlinePrompt = null,
                            phase = ResearchPhase.REPORT,
                            partial = false,
                            failure = null,
                            research = research,
                            previous = research,
                            check = check,
                            questions = research.questions.ifEmpty { state.questions },
                            exportDir = dir,
                            message = "这份是桌面端跑出来的，结论已经在下面了。",
                            messageIsError = false,
                        )
                    }
                }

                is AppResult.Err -> _ui.update {
                    it.copy(
                        offlineBusy = false,
                        offlineError = result.error.message +
                            " 检查一下粘的是不是整段、有没有被聊天软件截断。",
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 收尾

    private fun message(text: String, isError: Boolean) =
        _ui.update { it.copy(message = text, messageIsError = isError) }

    /** 问题编号必须 1..n 连续（报告与库里都按它排序）。 */
    private fun renumber(questions: List<ResearchQuestion>): List<ResearchQuestion> =
        questions.mapIndexed { index, question -> question.copy(idx = index + 1) }

    companion object {
        /** 进度最多留这么多行（页面只显示最后三行，留点余量给「取消」后回看） */
        private const val PROGRESS_KEEP = 20

        fun factory(container: AppContainer, sessionId: String) = viewModelFactory {
            initializer { ResearchViewModel(container.facade, container.settingsStore, sessionId) }
        }
    }
}
