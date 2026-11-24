package com.askfirst.ui.screens.plan

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.PlanSection

/**
 * M5 方案文档页：预览（逐段带出处）/ 重新生成 / 接着写剩下的 / 逐段删 /
 * 「这段从哪来」/ 存到文件夹 / 分享。M6 起底部多一个入口：去查查别人做过没。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    container: AppContainer,
    sessionId: String,
    onOpenResearch: () -> Unit = {},
    onBack: () -> Unit,
) {
    val viewModel: PlanViewModel = viewModel(
        factory = PlanViewModel.factory(container, sessionId),
        key = "plan-$sessionId",
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver

    // SAF：选一个目录 → 记住授权 → 以后直接写进去
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.saveTo(resolver, uri)
    }

    // 分享正文准备好 → 拉起系统分享面板
    LaunchedEffect(state.share) {
        val share = state.share ?: return@LaunchedEffect
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_TEXT, share.second)
                putExtra(Intent.EXTRA_TITLE, share.first)
                putExtra(Intent.EXTRA_SUBJECT, share.first)
            }
            context.startActivity(Intent.createChooser(intent, "分享这份方案"))
        }
        viewModel.consumeShare()
    }

    var deleting by remember { mutableStateOf<PlanSection?>(null) }
    deleting?.let { section ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删掉「${section.heading}」这一节？") },
            text = {
                Text("删掉之后这份文档里就没有这一节了（再点重新生成会长回来）。")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.deleteSection(section.id)
                }) { Text("删掉") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("不删") } },
        )
    }

    if (state.sourcesSectionId != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeSources,
            title = { Text("这一段从哪来") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.sources.isEmpty()) {
                        Text("找不到当时的原话了（可能已经被清掉）。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.sources.forEach { turn ->
                            Text(
                                text = "${if (turn.role == com.askfirst.domain.model.TurnRole.USER) "你" else "它"}：${turn.text}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeSources) { Text("知道了") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("方案文档") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(
                        onClick = { viewModel.generate(resume = false) },
                        enabled = !state.busy && state.hasPlan,
                    ) { Text("重新生成") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val plan = state.plan
            Text(
                text = plan?.title?.ifBlank { null } ?: "还没生成方案",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = buildString {
                    append(state.sceneName.ifBlank { "这场思路" })
                    if (state.sectionCount > 0) append(" · ${state.sectionCount} 节")
                    if (state.missingHeadings.isNotEmpty()) append(" · 还差 ${state.missingHeadings.size} 节")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "正在写…（已收到 ${state.progressChars} 字）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.check?.headline?.let { headline ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(headline, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.check?.warningText?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.messageIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
            state.savedNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (!state.hasPlan) {
                Text(
                    text = "还没有方案。点下面「生成方案」，它会按这套场景的骨架，把这场问答写成一份 " +
                        "能发给同事看的文档；每一段都能点开看它来自你哪句原话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                plan!!.sorted().forEach { section ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${section.order}. ${section.heading}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                TextButton(onClick = { deleting = section }) { Text("删掉") }
                            }
                            Text(text = section.body, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = { viewModel.peekSources(section.id) },
                                    enabled = section.sourceMessageIds.isNotEmpty(),
                                ) {
                                    Text(
                                        text = "这段从哪来 ${section.sourceMessageIds.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.missingHeadings.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("骨架里还差这几节", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = state.missingHeadings.joinToString("、"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { viewModel.generate(resume = true) },
                                enabled = !state.busy,
                            ) { Text("接着写剩下的") }
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { viewModel.generate(resume = false) },
                    enabled = !state.busy,
                ) { Text(if (state.hasPlan) "重新生成" else "生成方案") }
                OutlinedButton(
                    onClick = {
                        // 上次选过的目录还能写就直接写，否则重新选
                        if (!viewModel.saveToRemembered(resolver)) treePicker.launch(null)
                    },
                    enabled = !state.busy && state.hasPlan,
                ) { Text("保存到文件夹") }
                TextButton(
                    onClick = viewModel::prepareShare,
                    enabled = !state.busy && state.hasPlan,
                ) { Text("分享") }
            }
            Text(
                text = "存下来的是一份 .md：正文每节末尾的 [^1] 对应文末「出处」，别人拿到文档也能查证。" +
                    "文件落在你刚选的那个文件夹里，下次还存那儿。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            // M6：写完方案顺手看一眼「别人做过没」（同一个会话的调研记录，重查会覆盖上一次）
            TextButton(onClick = onOpenResearch, enabled = !state.busy) { Text("查查别人做过没") }
        }
    }
}
