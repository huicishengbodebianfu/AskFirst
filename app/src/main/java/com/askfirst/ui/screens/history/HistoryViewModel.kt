package com.askfirst.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.HistoryEntry
import com.askfirst.domain.model.HistoryFilter
import com.askfirst.domain.model.SceneSummary
import com.askfirst.domain.usecase.AskFirstFacade
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val loading: Boolean = true,
    val scenes: List<SceneSummary> = emptyList(),
    /** null = 全部 */
    val filterSceneId: String? = null,
    val entries: List<HistoryEntry> = emptyList(),
) {
    val empty: Boolean get() = !loading && entries.isEmpty()
}

class HistoryViewModel(private val facade: AskFirstFacade) : ViewModel() {

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    private val filter = MutableStateFlow(HistoryFilter())

    init {
        viewModelScope.launch {
            facade.sceneSummaries().let { scenes ->
                _ui.update { it.copy(scenes = scenes) }
            }
        }
        viewModelScope.launch {
            filter.flatMapLatest { facade.history(it) }.collect { entries ->
                _ui.update { it.copy(loading = false, entries = entries) }
            }
        }
    }

    fun onSelectFilter(sceneId: String?) {
        _ui.update { it.copy(filterSceneId = sceneId) }
        filter.value = HistoryFilter(sceneId)
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HistoryViewModel(container.facade) }
        }
    }
}
