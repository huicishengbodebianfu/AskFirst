package com.askfirst.ui.screens.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.SceneSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页：选场景 + 一句话开题 + 上次未完成入口 + 设置。
 * M1 把"场景选择"并进首页（少一次跳转）；M3 加自建场景（新建/导入/每卡菜单）。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenSceneEdit: (String?) -> Unit = {},
    onOpenCitePicker: (List<String>) -> Unit = {},
    /** M4：引用选择页带回的 sheetId（逗号分隔）；消费后调用 [onConsumeCiteResult] */
    citedResult: String? = null,
    onConsumeCiteResult: () -> Unit = {},
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // M3：从场景编辑器保存/删除返回时刷新列表（不然要重启 App 才看到新卡）
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshSilently()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.openSessionId) {
        val id = state.openSessionId
        if (id != null) {
            viewModel.consumeNavigation()
            onOpenChat(id)
        }
    }

    // M3 导入：系统文件选择器（SAF）选 .json → 读全文 → 交给 ViewModel
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) {
                viewModel.importScene("") // 读不到文件也走同一错误路径
            } else {
                viewModel.importScene(text)
            }
        }
    }

    // M4：引用选择页带回来的结果（逗号分隔 sheetId；由导航层回传）
    LaunchedEffect(citedResult) {
        val ids = citedResult
        if (!ids.isNullOrBlank()) {
            viewModel.setCitedSheetIds(ids.split(',').filter { it.isNotBlank() })
            onConsumeCiteResult()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AskFirst", style = MaterialTheme.typography.displaySmall)
                Row {
                    TextButton(onClick = onOpenHistory) { Text("历史") }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                }
            }
            Text(
                text = "开工前先把它问清楚：它问你，你说够了，它给你一张能开工的单子。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.resumableSessionId != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("上次未完成的思路", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = state.resumableSummary.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = viewModel::resume) { Text("接着聊") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("1. 这次做的是哪类事？", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) {
                        Text("导入")
                    }
                    TextButton(onClick = { onOpenSceneEdit(null) }) { Text("自建场景") }
                }
            }
            state.scenes.forEach { scene ->
                SceneCard(
                    scene = scene,
                    selected = scene.id == state.selectedSceneId,
                    onClick = { viewModel.onSelectScene(scene.id) },
                    onEdit = if (scene.builtin) null else ({ onOpenSceneEdit(scene.id) }),
                    onDuplicate = { viewModel.duplicateScene(scene.id) },
                    onDelete = if (scene.builtin) null else ({ viewModel.deleteScene(scene.id) }),
                    onExport = {
                        scope.launch {
                            runCatching {
                                val (fileName, body) = container.facade.exportSceneJson(scene.id)
                                shareJson(context, fileName, body)
                            }
                        }
                    },
                )
            }

            Text("2. 用一句话说清你要做的事（20 字也行）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = state.intent,
                onValueChange = viewModel::onIntentChange,
                placeholder = { Text("例：首屏 4 秒太慢，想加缓存") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            // M4：带上旧结论（可选，≤3 份）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.citedSheetIds.isEmpty()) {
                        "（可选）带上以前的结论吗？"
                    } else {
                        "带 ${state.citedSheetIds.size} 份旧结论当背景"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { onOpenCitePicker(state.citedSheetIds) }) {
                    Text(if (state.citedSheetIds.isEmpty()) "去选" else "改一下")
                }
            }
            if (state.citedSheetIds.isNotEmpty()) {
                TextButton(onClick = { viewModel.setCitedSheetIds(emptyList()) }) {
                    Text("不带")
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val error = state.error
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = viewModel::start,
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.busy) "正在开…" else "开始，让它问我")
            }

            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun shareJson(context: android.content.Context, fileName: String, body: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_TEXT, body)
        putExtra(android.content.Intent.EXTRA_TITLE, fileName)
        putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "分享这套场景"))
}

@Composable
private fun SceneCard(
    scene: SceneSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onDelete: (() -> Unit)?,
    onExport: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(scene.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = scene.focus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (scene.builtin) {
                        "出厂 · ${scene.questionCount} 个问题 · 必问 ${scene.requiredCount}"
                    } else {
                        "自建 · ${scene.questionCount} 个问题 · 必问 ${scene.requiredCount}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            SceneCardMenu(
                builtin = scene.builtin,
                onEdit = onEdit,
                onDuplicate = onDuplicate,
                onDelete = onDelete,
                onExport = onExport,
            )
        }
    }
}

@Composable
private fun SceneCardMenu(
    builtin: Boolean,
    onEdit: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onDelete: (() -> Unit)?,
    onExport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Text("⋮", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onEdit != null) {
                DropdownMenuItem(
                    text = { Text("改这套场景") },
                    onClick = { expanded = false; onEdit() },
                )
            }
            if (builtin) {
                DropdownMenuItem(
                    text = { Text("复制为自建") },
                    onClick = { expanded = false; onDuplicate() },
                )
            }
            DropdownMenuItem(
                text = { Text("导出发给别人") },
                onClick = { expanded = false; onExport() },
            )
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text("删掉这套场景") },
                    onClick = { expanded = false; onDelete() },
                )
            }
        }
    }
}
