package com.askfirst.ui.screens.scene_edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.QuestionDraft

/**
 * M3 场景编辑器：新建/编辑自建场景。
 * 内置场景进来只读（首页菜单里内置只有「复制为自建/导出」，这条路是调试钩子留的兜底）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneEditScreen(
    container: AppContainer,
    sceneId: String?,
    onBack: () -> Unit,
) {
    val viewModel: SceneEditViewModel = viewModel(
        factory = SceneEditViewModel.factory(container, sceneId),
        key = sceneId ?: "new",
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (state.isCreate) "自建场景" else "改场景") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave) { Text("保存") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.builtin) {
                Text(
                    text = "这是出厂带的场景，改了会被升级覆盖。回到首页用「复制为自建」拷一份再改。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.loading) {
                Text("正在读这套场景…", style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onName,
                label = { Text("场景名字（例：搬家计划）") },
                enabled = !state.builtin,
                isError = state.name.isBlank() && state.issues.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.focus,
                onValueChange = viewModel::onFocus,
                label = { Text("它管什么用（一句话）") },
                enabled = !state.builtin,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("问题清单（它就照这个问）", style = MaterialTheme.typography.titleSmall)
            state.questions.forEachIndexed { index, q ->
                QuestionCard(
                    index = index,
                    draft = q,
                    enabled = !state.builtin,
                    first = index == 0,
                    onUpdate = { viewModel.updateQuestion(index, it) },
                    onMoveUp = { viewModel.moveUp(index) },
                    onRemove = { viewModel.removeQuestion(index) },
                )
            }
            if (!state.builtin) {
                OutlinedButton(onClick = viewModel::addQuestion, modifier = Modifier.fillMaxWidth()) {
                    Text("加一道问题")
                }
            }

            state.issues.forEach { issue ->
                Text(
                    text = issue,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存这套场景") }

            if (!state.isCreate && !state.builtin) {
                TextButton(
                    onClick = viewModel::delete,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("删掉这套场景", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    index: Int,
    draft: QuestionDraft,
    enabled: Boolean,
    first: Boolean,
    onUpdate: (QuestionDraft) -> Unit,
    onMoveUp: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("第 ${index + 1} 题", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(
                value = draft.text,
                onValueChange = { onUpdate(draft.copy(text = it)) },
                label = { Text("题目") },
                enabled = enabled,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.why,
                onValueChange = { onUpdate(draft.copy(why = it)) },
                label = { Text("为什么问这个（它会拿这个把握分寸）") },
                enabled = enabled,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(
                    checked = draft.required,
                    onCheckedChange = { onUpdate(draft.copy(required = it)) },
                    enabled = enabled,
                )
                Text("必问（不答完不让出单）", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = draft.trigger.orEmpty(),
                onValueChange = { onUpdate(draft.copy(trigger = it.ifBlank { null })) },
                label = { Text("问到这个词才出现（可留空）例：迁移|重构") },
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onMoveUp, enabled = !first) { Text("上移") }
                    TextButton(onClick = onRemove) { Text("删这题", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
