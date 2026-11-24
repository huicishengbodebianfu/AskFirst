package com.askfirst.ui.screens.sheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetCheck
import com.askfirst.domain.model.SheetItem
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.Verdict
import com.askfirst.domain.sheet.SheetValidator
import com.askfirst.domain.usecase.AskFirstFacade
import com.askfirst.domain.usecase.SheetOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SheetUiState(
    val loading: Boolean = true,
    val intent: String = "",
    val sceneName: String = "",
    val sheet: Sheet? = null,
    val check: SheetCheck? = null,
    val busy: Boolean = false,
    val progressChars: Int = 0,
    val canFallback: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val prompt: String? = null,
    val error: String? = null,
    val sourcesItem: SheetItem? = null,
    val sources: List<Turn> = emptyList(),
    /** M6：第 2/5 栏下面那两块「调研补充」（只可能是 col2 / col5） */
    val researchAppends: List<Verdict> = emptyList(),
    /** 每条补充能点开的链接（引文链接，或第一条来源的链接；都没有就不给跳） */
    val researchUrls: Map<String, String> = emptyMap(),
)

class SheetViewModel(
    private val facade: AskFirstFacade,
    private val validator: SheetValidator,
    private val sessionId: String,
    private val buildOnOpen: Boolean,
) : ViewModel() {

    private val _ui = MutableStateFlow(SheetUiState())
    val ui: StateFlow<SheetUiState> = _ui.asStateFlow()

    init {
        loadAndMaybeBuild()
    }

    fun load() {
        viewModelScope.launch {
            val view = facade.sessionView(sessionId)
            if (view == null) {
                _ui.update { it.copy(loading = false, error = "这个会话找不到了") }
                return@launch
            }
            val (appends, urls) = researchAppends()
            _ui.update {
                it.copy(
                    loading = false,
                    intent = view.session.intent,
                    sceneName = view.scene.name,
                    sheet = view.sheet,
                    check = validator.check(view.sheet),
                    researchAppends = appends,
                    researchUrls = urls,
                )
            }
        }
    }

    /**
     * 第 2/5 栏下面那两块「调研补充」：查到的结论 + 能点开的链接。
     * **只是贴在单子下面给人看**，不进 [SheetItem]——条目必须有原话出处，查来的东西没有。
     */
    private suspend fun researchAppends(): Pair<List<Verdict>, Map<String, String>> {
        val verdicts = facade.researchAppends(sessionId)
        if (verdicts.isEmpty()) return emptyList<Verdict>() to emptyMap()
        val research = facade.researchOf(sessionId)
        val urls = verdicts.mapNotNull { verdict ->
            val url = verdict.quotedUrl.ifBlank {
                research?.evidenceOf(verdict)?.firstOrNull { it.url.isNotBlank() }?.url.orEmpty()
            }
            if (url.isBlank()) null else verdict.id to url
        }.toMap()
        return verdicts to urls
    }

    /** 第一次进来（单子还是空的）就自动出单：点了「够了」的用户不希望这里还要再点一次。 */
    private fun loadAndMaybeBuild() {
        viewModelScope.launch {
            val view = facade.sessionView(sessionId)
            if (view == null) {
                _ui.update { it.copy(loading = false, error = "这个会话找不到了") }
                return@launch
            }
            val (appends, urls) = researchAppends()
            _ui.update {
                it.copy(
                    loading = false,
                    intent = view.session.intent,
                    sceneName = view.scene.name,
                    sheet = view.sheet,
                    check = validator.check(view.sheet),
                    researchAppends = appends,
                    researchUrls = urls,
                )
            }
            // 只有"点了够了"进来才出单；"看单子"只是预览，不许偷偷调模型
            if (buildOnOpen) build()
        }
    }

    fun build() {
        if (_ui.value.busy) return
        _ui.update {
            it.copy(busy = true, progressChars = 0, message = null, messageIsError = false, canFallback = false)
        }
        viewModelScope.launch {
            facade.buildSheet(sessionId).collect { outcome ->
                when (outcome) {
                    is SheetOutcome.Progress -> _ui.update { it.copy(progressChars = outcome.receivedChars) }

                    is SheetOutcome.Done -> _ui.update {
                        it.copy(
                            busy = false,
                            progressChars = 0,
                            sheet = outcome.sheet,
                            check = outcome.check,
                            message = "单子出好了。改过的条目会被保留，重新出单也不会冲掉。",
                            messageIsError = false,
                        )
                    }

                    is SheetOutcome.Failed -> _ui.update {
                        it.copy(
                            busy = false,
                            progressChars = 0,
                            message = outcome.error.message,
                            messageIsError = true,
                            canFallback = outcome.canFallback,
                        )
                    }
                }
            }
        }
    }

    /** 模型不可用：用手里的内容直接出单（第 5 栏会如实写"这栏还是空的"）。 */
    fun fallback() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, message = null, messageIsError = false) }
        viewModelScope.launch {
            val done = facade.fallbackSheet(sessionId)
            _ui.update {
                it.copy(
                    busy = false,
                    sheet = done.sheet,
                    check = done.check,
                    canFallback = false,
                    message = "已用手里的内容出单。模型恢复后可以点「重新出单」补全。",
                    messageIsError = false,
                )
            }
        }
    }

    fun copyPrompt() {
        viewModelScope.launch {
            val text = facade.exportPrompt(_ui.value.sheet?.id ?: return@launch)
            _ui.update { it.copy(prompt = text) }
        }
    }

    fun consumePrompt() {
        _ui.update { it.copy(prompt = null, message = "已复制。粘到别的 AI 里就能直接开工。", messageIsError = false) }
    }

    /** 「点条目看出处」：显示这条内容来自他当时哪几句原话。 */
    fun peekSources(item: SheetItem) {
        viewModelScope.launch {
            val turns = facade.sourceTurns(sessionId, item.id)
            _ui.update { it.copy(sourcesItem = item, sources = turns) }
        }
    }

    fun closeSources() {
        _ui.update { it.copy(sourcesItem = null, sources = emptyList()) }
    }

    fun editItem(itemId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            facade.editItem(itemId, text)
            load()
            _ui.update { it.copy(message = "改好了。这条以后重新出单也不会被冲掉。", messageIsError = false) }
        }
    }

    fun finish() {
        viewModelScope.launch {
            facade.markDone(sessionId)
            load()
            _ui.update { it.copy(message = "这次先存下了。", messageIsError = false) }
        }
    }

    companion object {
        fun factory(container: AppContainer, sessionId: String, buildOnOpen: Boolean) = viewModelFactory {
            initializer {
                SheetViewModel(container.facade, container.sheetValidator, sessionId, buildOnOpen)
            }
        }
    }
}

