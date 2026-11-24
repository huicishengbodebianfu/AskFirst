package com.askfirst.ui.screens.plan

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.data.prefs.SettingsStore
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.Plan
import com.askfirst.domain.model.PlanCheck
import com.askfirst.domain.model.Turn
import com.askfirst.domain.usecase.AskFirstFacade
import com.askfirst.domain.usecase.PlanOutcome
import com.askfirst.ui.SafExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlanUiState(
    val loading: Boolean = true,
    val plan: Plan? = null,
    val check: PlanCheck? = null,
    val sceneName: String = "",
    val missingHeadings: List<String> = emptyList(),
    val generating: Boolean = false,
    val progressChars: Int = 0,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** 上一步失败时能不能「接着写剩下的」 */
    val canResume: Boolean = false,
    val sourcesSectionId: String? = null,
    val sources: List<Turn> = emptyList(),
    /** 拉起分享面板用的正文（消费后清空） */
    val share: Pair<String, String>? = null,
    /** 上次选过的落盘目录（SAF tree uri 字符串） */
    val exportDir: String? = null,
    val savedNote: String? = null,
) {
    val hasPlan: Boolean get() = plan?.sections?.isNotEmpty() == true
    val sectionCount: Int get() = plan?.sections?.size ?: 0
    val busy: Boolean get() = generating
}

/**
 * M5 方案文档页：生成 / 重新生成 / 接着写剩下的 / 逐段删 / 溯源 / 落盘 / 分享。
 * 落盘走 [SafExport]（ContentResolver 由屏幕传进来，ViewModel 不碰 Context）。
 */
class PlanViewModel(
    private val facade: AskFirstFacade,
    private val settingsStore: SettingsStore,
    private val sessionId: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlanUiState())
    val ui: StateFlow<PlanUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val view = facade.planViewOf(sessionId)
            val dir = settingsStore.current().planExportDir
            _ui.update { state ->
                state.copy(
                    loading = false,
                    plan = view?.plan,
                    check = view?.check,
                    sceneName = view?.sceneName ?: facade.sceneNameOf(sessionId).orEmpty(),
                    missingHeadings = view?.missingHeadings.orEmpty(),
                    exportDir = dir,
                )
            }
        }
    }

    fun generate(resume: Boolean = false) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _ui.update {
                it.copy(
                    generating = true,
                    progressChars = 0,
                    message = null,
                    messageIsError = false,
                    canResume = false,
                    savedNote = null,
                )
            }
            facade.generatePlan(sessionId, resume).collect { outcome ->
                when (outcome) {
                    is PlanOutcome.Progress -> _ui.update { it.copy(progressChars = outcome.receivedChars) }

                    is PlanOutcome.Done -> {
                        val missing = facade.planViewOf(sessionId)?.missingHeadings.orEmpty()
                        _ui.update {
                            it.copy(
                                generating = false,
                                plan = outcome.plan,
                                check = outcome.check,
                                missingHeadings = missing,
                                // blocking 的时候屏幕上已经有一张红卡说同一句话，别重复两遍
                                message = when {
                                    !outcome.check.canSend -> null
                                    missing.isNotEmpty() -> "先出了一部分，还差 ${missing.size} 节"
                                    else -> "方案写好了，可以发给同事看了"
                                },
                                messageIsError = false,
                                canResume = missing.isNotEmpty(),
                            )
                        }
                    }

                    is PlanOutcome.Failed -> _ui.update {
                        val suffix = if (outcome.canResume && it.hasPlan) "（还能接着写剩下的）" else ""
                        it.copy(
                            generating = false,
                            message = "${outcome.error.message}$suffix",
                            messageIsError = true,
                            canResume = outcome.canResume,
                        )
                    }
                }
            }
        }
    }

    /** 逐段删：删完立刻落库并重算检查（删掉的不会因为重进又长回来）。 */
    fun deleteSection(sectionId: String) {
        val plan = _ui.value.plan ?: return
        viewModelScope.launch {
            val updated = facade.deletePlanSection(plan.id, sectionId)
            if (updated == null) {
                _ui.update { it.copy(message = "这一段删不掉了，回上一页重进一次试试", messageIsError = true) }
                return@launch
            }
            val view = facade.planViewOf(sessionId)
            _ui.update {
                it.copy(
                    plan = view?.plan ?: updated,
                    check = view?.check,
                    missingHeadings = view?.missingHeadings.orEmpty(),
                    message = "删掉了这一段",
                    messageIsError = false,
                )
            }
        }
    }

    fun peekSources(sectionId: String) {
        viewModelScope.launch {
            val turns = facade.planSectionSources(sessionId, sectionId)
            _ui.update { it.copy(sourcesSectionId = sectionId, sources = turns) }
        }
    }

    fun closeSources() = _ui.update { it.copy(sourcesSectionId = null, sources = emptyList()) }

    fun prepareShare() {
        val plan = _ui.value.plan ?: return
        viewModelScope.launch {
            runCatching { facade.exportPlan(plan.id) }
                .onSuccess { pair -> _ui.update { it.copy(share = pair) } }
                .onFailure { error ->
                    _ui.update { it.copy(message = "导出失败：${error.message}", messageIsError = true) }
                }
        }
    }

    fun consumeShare() = _ui.update { it.copy(share = null) }

    /** 把方案写进 SAF 目录（用户刚选完目录，或上次选过的那个）。 */
    fun saveTo(resolver: ContentResolver, treeUri: Uri) {
        val plan = _ui.value.plan ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SafExport.rememberPermission(resolver, treeUri)
                    val (fileName, body) = facade.exportPlan(plan.id)
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

    /** 上次的目录还能写就直接写，权限被系统回收了就让屏幕重新选。 */
    fun saveToRemembered(resolver: ContentResolver): Boolean {
        val dir = _ui.value.exportDir ?: return false
        val uri = runCatching { Uri.parse(dir) }.getOrNull() ?: return false
        if (!SafExport.hasPermission(resolver, uri)) return false
        saveTo(resolver, uri)
        return true
    }

    fun clearMessage() = _ui.update { it.copy(message = null, savedNote = null) }

    companion object {
        fun factory(container: AppContainer, sessionId: String) = viewModelFactory {
            initializer { PlanViewModel(container.facade, container.settingsStore, sessionId) }
        }
    }
}
