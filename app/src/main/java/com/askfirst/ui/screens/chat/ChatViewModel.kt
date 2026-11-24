package com.askfirst.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.EngineQuestion
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.model.Turn
import com.askfirst.domain.usecase.AskFirstFacade
import com.askfirst.domain.usecase.TurnOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessionId: String,
    val loading: Boolean = true,
    val sceneName: String = "",
    val intent: String = "",
    val turns: List<Turn> = emptyList(),
    val questions: List<EngineQuestion> = emptyList(),
    val clarify: String? = null,
    val nudge: String? = null,
    val blockReason: String? = null,
    val answer: String = "",
    val busy: Boolean = false,
    val progressChars: Int = 0,
    val error: String? = null,
    val canRetry: Boolean = false,
    val sheetItemCount: Int = 0,
    val state: EngineState = EngineState.GATHERING,
    /** M4：这场会话带了几份旧结论（顶部提示行） */
    val citationCount: Int = 0,
) {
    val readyForSheet: Boolean get() = state == EngineState.READY
    val canSend: Boolean get() = !busy && answer.isNotBlank()
}

class ChatViewModel(
    private val facade: AskFirstFacade,
    private val sessionId: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState(sessionId = sessionId))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val view = facade.sessionView(sessionId)
            if (view == null) {
                _ui.update { it.copy(loading = false, error = "这个会话找不到了") }
                return@launch
            }
            _ui.update {
                it.copy(
                    loading = false,
                    sceneName = view.scene.name,
                    intent = view.session.intent,
                    turns = view.turns,
                    questions = view.turns.lastOrNull { turn -> turn.questions.isNotEmpty() }?.questions.orEmpty(),
                    clarify = null,
                    state = view.session.state,
                    sheetItemCount = view.sheet.allItems().size,
                    citationCount = facade.citationCountOf(sessionId),
                )
            }
        }
    }

    fun onAnswerChange(value: String) {
        _ui.update { it.copy(answer = value, error = null) }
    }

    fun chooseOption(option: String) {
        _ui.update {
            if (it.answer == option) it.copy(answer = "") else it.copy(answer = option, error = null)
        }
    }

    fun send() {
        val answer = _ui.value.answer.trim()
        if (answer.isEmpty() || _ui.value.busy) return
        submit(answer)
    }

    fun skip() {
        if (_ui.value.busy) return
        submit(null)
    }

    fun retry() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, progressChars = 0) }
        viewModelScope.launch {
            facade.retry(sessionId).collect { handle(it) }
        }
    }

    private fun submit(answer: String?) {
        _ui.update { it.copy(busy = true, error = null, progressChars = 0) }
        viewModelScope.launch {
            facade.ask(sessionId, answer).collect { handle(it) }
        }
    }

    private fun handle(outcome: TurnOutcome) {
        when (outcome) {
            is TurnOutcome.Progress -> _ui.update { it.copy(progressChars = outcome.receivedChars) }

            is TurnOutcome.Done -> _ui.update {
                it.copy(
                    busy = false,
                    progressChars = 0,
                    turns = outcome.turns,
                    questions = outcome.decision.questions,
                    clarify = outcome.decision.clarify,
                    nudge = outcome.decision.nudge,
                    blockReason = outcome.decision.blockReason,
                    state = outcome.decision.state,
                    sheetItemCount = outcome.sheet.allItems().size,
                    answer = "",
                    error = null,
                    canRetry = false,
                )
            }

            is TurnOutcome.Failed -> _ui.update {
                it.copy(
                    busy = false,
                    progressChars = 0,
                    error = outcome.error.message,
                    canRetry = outcome.canRetry,
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, sessionId: String) = viewModelFactory {
            initializer { ChatViewModel(container.facade, sessionId) }
        }
    }
}