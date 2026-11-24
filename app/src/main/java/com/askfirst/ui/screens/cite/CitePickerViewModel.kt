package com.askfirst.ui.screens.cite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.di.AppContainer
import com.askfirst.domain.citation.CitationPolicy
import com.askfirst.domain.model.CitationCandidate
import com.askfirst.domain.usecase.AskFirstFacade
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CitePickerUiState(
    val loading: Boolean = true,
    val keyword: String = "",
    val candidates: List<CitationCandidate> = emptyList(),
    /** 已勾选的 sheetId（保序，≤3） */
    val selected: List<String> = emptyList(),
    /** 勾选时选中的候选（用于首页展示摘要行） */
    val picked: List<CitationCandidate> = emptyList(),
    val message: String? = null,
) {
    val canConfirm: Boolean get() = selected.isNotEmpty()
}

class CitePickerViewModel(
    private val facade: AskFirstFacade,
    initialSelected: List<String>,
) : ViewModel() {

    private val _ui = MutableStateFlow(CitePickerUiState(selected = CitationPolicy.cap(initialSelected)))
    val ui: StateFlow<CitePickerUiState> = _ui.asStateFlow()

    private val keywordFlow = MutableStateFlow("")

    /** 上一次真正拿去搜的关键词（跳过 debounce 首发的空关键词，但用户输入必须搜）。 */
    private var lastSearched: String? = null

    init {
        search(_ui.value.keyword)
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            keywordFlow.debounce(300).collect { kw ->
                if (kw != lastSearched) {
                    lastSearched = kw
                    search(kw)
                }
            }
        }
    }

    fun onKeywordChange(value: String) {
        _ui.update { it.copy(keyword = value, message = null) }
        keywordFlow.value = value
    }

    fun search(keyword: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val candidates = facade.searchCitations(keyword)
            _ui.update { state ->
                val selected = state.selected
                state.copy(
                    loading = false,
                    candidates = candidates,
                    // 刷新后把已勾的候选信息补上（首页要展示摘要）
                    picked = candidates.filter { it.sheetId in selected } + state.picked.filter { p ->
                        p.sheetId in selected && candidates.none { it.sheetId == p.sheetId }
                    },
                )
            }
        }
    }

    /** 勾/取消勾一份；满了 3 份再勾 → 提示。 */
    fun toggle(sheetId: String) {
        _ui.update { state ->
            if (sheetId in state.selected) {
                state.copy(
                    selected = state.selected - sheetId,
                    picked = state.picked.filterNot { it.sheetId == sheetId },
                    message = null,
                )
            } else if (CitationPolicy.canSelectMore(state.selected.size)) {
                val candidate = state.candidates.firstOrNull { it.sheetId == sheetId }
                state.copy(
                    selected = state.selected + sheetId,
                    picked = if (candidate != null) state.picked + candidate else state.picked,
                    message = null,
                )
            } else {
                state.copy(message = "最多带 ${CitationPolicy.MAX_CITATIONS} 份旧结论")
            }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    companion object {
        fun factory(container: AppContainer, initialSelected: List<String>) = viewModelFactory {
            initializer { CitePickerViewModel(container.facade, initialSelected) }
        }
    }
}
