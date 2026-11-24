package com.askfirst.ui.screens.scene_edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.QuestionDraft
import com.askfirst.domain.model.SceneDraft
import com.askfirst.domain.usecase.AskFirstFacade
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SceneEditUiState(
    val loading: Boolean = true,
    /** null = 新建；非空 = 编辑这套自建场景 */
    val sceneId: String? = null,
    /** 内置场景不该进来编辑（进来只展示，不给保存） */
    val builtin: Boolean = false,
    val name: String = "",
    val focus: String = "",
    val questions: List<QuestionDraft> = emptyList(),
    val busy: Boolean = false,
    /** validate() 的中文问题列表（保存被拦时展示） */
    val issues: List<String> = emptyList(),
    val error: String? = null,
    /** 保存/删除成功 → UI 返回首页 */
    val finished: Boolean = false,
) {
    val isCreate: Boolean get() = sceneId == null
    val canSave: Boolean get() = !busy && !builtin && !loading && !finished
}

class SceneEditViewModel(
    private val facade: AskFirstFacade,
    private val sceneId: String?,
) : ViewModel() {

    private val _ui = MutableStateFlow(SceneEditUiState(sceneId = sceneId))
    val ui: StateFlow<SceneEditUiState> = _ui.asStateFlow()

    init {
        if (sceneId != null) {
            viewModelScope.launch {
                val scene = facade.sceneOf(sceneId)
                _ui.update {
                    if (scene == null) {
                        it.copy(loading = false, error = "这套场景不见了（可能刚被删掉）")
                    } else {
                        it.copy(
                            loading = false,
                            builtin = scene.builtin,
                            name = scene.name,
                            focus = scene.focus,
                            questions = scene.questions.map { q ->
                                QuestionDraft(q.text, q.why, q.required, q.trigger)
                            },
                        )
                    }
                }
            }
        } else {
            _ui.update { it.copy(loading = false) }
        }
    }

    fun onName(value: String) = _ui.update { it.copy(name = value, issues = emptyList(), error = null) }

    fun onFocus(value: String) = _ui.update { it.copy(focus = value, issues = emptyList(), error = null) }

    fun addQuestion() = _ui.update {
        it.copy(questions = it.questions + QuestionDraft(text = "", why = ""), issues = emptyList())
    }

    fun updateQuestion(index: Int, draft: QuestionDraft) {
        _ui.update { state ->
            if (index !in state.questions.indices) state
            else state.copy(
                questions = state.questions.toMutableList().also { it[index] = draft },
                issues = emptyList(),
            )
        }
    }

    fun removeQuestion(index: Int) {
        _ui.update { state ->
            if (index !in state.questions.indices) state
            else state.copy(questions = state.questions.filterIndexed { i, _ -> i != index })
        }
    }

    fun moveUp(index: Int) {
        _ui.update { state ->
            if (index !in 1..state.questions.lastIndex.coerceAtLeast(0)) state
            else state.copy(
                questions = state.questions.toMutableList().also { list ->
                    val tmp = list[index - 1]
                    list[index - 1] = list[index]
                    list[index] = tmp
                },
            )
        }
    }

    fun save() {
        val state = _ui.value
        if (state.builtin || state.busy) return
        val draft = SceneDraft(state.name, state.focus, state.questions)
        val issues = draft.validate()
        if (issues.isNotEmpty()) {
            _ui.update { it.copy(issues = issues) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            val result = if (state.sceneId == null) {
                facade.createScene(draft)
            } else {
                facade.updateScene(state.sceneId, draft)
            }
            _ui.update {
                when (result) {
                    is com.askfirst.core.AppResult.Ok -> it.copy(busy = false, finished = true)
                    is com.askfirst.core.AppResult.Err -> it.copy(busy = false, error = result.error.message)
                }
            }
        }
    }

    fun delete() {
        val sceneId = _ui.value.sceneId ?: return
        if (_ui.value.builtin || _ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            val result = facade.deleteScene(sceneId)
            _ui.update {
                when (result) {
                    is com.askfirst.core.AppResult.Ok -> it.copy(busy = false, finished = true)
                    is com.askfirst.core.AppResult.Err -> it.copy(busy = false, error = result.error.message)
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, sceneId: String?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SceneEditViewModel(container.facade, sceneId) }
            }
    }
}
