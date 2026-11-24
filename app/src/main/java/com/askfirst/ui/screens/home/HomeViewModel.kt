package com.askfirst.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.core.AppResult
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.SceneSummary
import com.askfirst.domain.usecase.AskFirstFacade
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val scenes: List<SceneSummary> = emptyList(),
    val resumableSessionId: String? = null,
    val resumableSummary: String? = null,
    val selectedSceneId: String? = null,
    val intent: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** M3：场景导入/删除/复制的操作回执（成功类，展示后手动清） */
    val message: String? = null,
    /** 非空 = 让 UI 跳到这个会话 */
    val openSessionId: String? = null,
    /** M4：带上旧结论（sheetId，≤3） */
    val citedSheetIds: List<String> = emptyList(),
) {
    val canStart: Boolean get() = selectedSceneId != null && intent.isNotBlank() && !busy
}

class HomeViewModel(
    private val facade: AskFirstFacade,
    /**
     * 内置场景灌库的结果。**必须等它**：`AppContainer.seedResult` 是 async 起的，
     * 首页原来直接查库，冷启动时会先读到空表（要等 ON_RESUME 那次刷新才补上），
     * 而灌库失败时首页永远空着、一句话都不说（docs/28 P2-11）。
     */
    private val seed: Deferred<AppResult<Int>>,
) : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    init {
        refreshSilently()
    }

    /**
     * 只重读列表数据，不清提示（错误/成功文案由各操作自己管理）。
     * 场景编辑器保存/删除后从 ON_RESUME 也走这里。
     */
    fun refreshSilently() {
        viewModelScope.launch {
            val seedError = when (val seeded = seed.await()) {
                is AppResult.Ok -> null
                is AppResult.Err -> seeded.error.message
            }
            val scenes = facade.sceneSummaries()
            val resumable = facade.resumable()
            _ui.update {
                it.copy(
                    loading = false,
                    scenes = scenes,
                    resumableSessionId = resumable?.id,
                    resumableSummary = resumable?.summary ?: resumable?.intent,
                    selectedSceneId = it.selectedSceneId?.takeIf { id -> scenes.any { s -> s.id == id } }
                        ?: scenes.firstOrNull()?.id,
                    // 灌库失败要说出来：不许留一个空首页让人以为"本来就长这样"
                    error = seedError ?: it.error,
                )
            }
        }
    }

    fun refresh() = refreshSilently()

    fun onSelectScene(sceneId: String) {
        _ui.update { it.copy(selectedSceneId = sceneId, error = null) }
    }

    fun onIntentChange(value: String) {
        _ui.update { it.copy(intent = value, error = null) }
    }

    /** M4：引用选择页带回的勾选（上限由 UI/picker 拦，这里再截一次保险）。 */
    fun setCitedSheetIds(ids: List<String>) {
        _ui.update { it.copy(citedSheetIds = com.askfirst.domain.citation.CitationPolicy.cap(ids)) }
    }

    fun start() {
        val state = _ui.value
        val sceneId = state.selectedSceneId
        if (sceneId == null || state.intent.isBlank()) {
            _ui.update { it.copy(error = "先选一个场景，再用一句话说清你要做的事") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            val session = facade.start(sceneId, state.intent, state.citedSheetIds)
            _ui.update { it.copy(busy = false, openSessionId = session.id) }
        }
    }

    fun resume() {
        val id = _ui.value.resumableSessionId ?: return
        _ui.update { it.copy(openSessionId = id) }
    }

    fun open(sessionId: String) {
        _ui.update { it.copy(openSessionId = sessionId) }
    }

    fun consumeNavigation() {
        _ui.update { it.copy(openSessionId = null) }
    }

    // ---------- M3：场景管理 ----------

    /** 导入场景文件（首页「导入」按钮，文本已从文件读出）。 */
    fun importScene(text: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null, error = null) }
            when (val result = facade.importSceneJson(text)) {
                is com.askfirst.core.AppResult.Ok -> _ui.update {
                    it.copy(busy = false, message = "导进来了：${result.value.name}（${result.value.questions.size} 道问题）")
                }
                is com.askfirst.core.AppResult.Err -> _ui.update {
                    it.copy(busy = false, error = result.error.message)
                }
            }
            refreshSilently()
        }
    }

    fun duplicateScene(sceneId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null, error = null) }
            when (val result = facade.duplicateScene(sceneId)) {
                is com.askfirst.core.AppResult.Ok -> _ui.update {
                    it.copy(busy = false, message = "拷了一份自建的「${result.value.name}」，随你改")
                }
                is com.askfirst.core.AppResult.Err -> _ui.update {
                    it.copy(busy = false, error = result.error.message)
                }
            }
            refreshSilently()
        }
    }

    fun deleteScene(sceneId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null, error = null) }
            when (val result = facade.deleteScene(sceneId)) {
                is com.askfirst.core.AppResult.Ok -> _ui.update { it.copy(busy = false, message = "删掉了") }
                is com.askfirst.core.AppResult.Err -> _ui.update {
                    it.copy(busy = false, error = result.error.message)
                }
            }
            refreshSilently()
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null, error = null) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeViewModel(container.facade, container.seedResult) }
        }
    }
}