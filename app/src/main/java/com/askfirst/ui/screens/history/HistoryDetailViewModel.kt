package com.askfirst.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.Turn
import com.askfirst.domain.usecase.AskFirstFacade
import com.askfirst.domain.usecase.SessionView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val view: SessionView? = null,
    val turns: List<Turn> = emptyList(),
    /** M4：这份结论基于哪几份旧结论（空 = 没带引用，不显示区块） */
    val citations: List<com.askfirst.domain.model.CitationEntry> = emptyList(),
) {
    val sessionTitle: String get() = view?.session?.intent?.ifBlank { "（没写一句话）" } ?: ""
    val sceneName: String get() = view?.scene?.name ?: ""
    val open: Boolean get() = view?.session?.resumable == true
}

class HistoryDetailViewModel(
    private val facade: AskFirstFacade,
    private val sessionId: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(HistoryDetailUiState())
    val ui: StateFlow<HistoryDetailUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val view = facade.sessionView(sessionId)
            _ui.update {
                it.copy(
                    loading = false,
                    notFound = view == null,
                    view = view,
                    turns = view?.turns.orEmpty(),
                    citations = if (view == null) emptyList() else facade.citationsOf(sessionId),
                )
            }
        }
    }

    /** 「接着深挖」：已存下的会话回退 open，然后由 UI 跳回提问流。 */
    fun reopen(onReopened: () -> Unit) {
        if (_ui.value.view?.session?.resumable == true) {
            onReopened()
            return
        }
        viewModelScope.launch {
            facade.reopen(sessionId)
            refresh()
            onReopened()
        }
    }

    companion object {
        fun factory(container: AppContainer, sessionId: String) = viewModelFactory {
            initializer { HistoryDetailViewModel(container.facade, sessionId) }
        }
    }
}
