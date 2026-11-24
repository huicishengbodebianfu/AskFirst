package com.askfirst.ui.screens.history

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import kotlinx.coroutines.launch

/**
 * M2 历史详情：问答一起回看 + 从这里继续聊 / 看确认单。M6 起还能「调研验证」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    container: AppContainer,
    sessionId: String,
    onOpenChat: (String) -> Unit,
    onOpenSheet: (String) -> Unit,
    onOpenDetail: (String) -> Unit = {},
    onOpenPlan: (String) -> Unit = {},
    onOpenResearch: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val viewModel: HistoryDetailViewModel = viewModel(
        factory = HistoryDetailViewModel.factory(container, sessionId),
        key = sessionId,
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val view = state.view
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun shareMarkdown() {
        scope.launch {
            runCatching {
                val (fileName, body) = container.facade.exportMarkdown(sessionId)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_TEXT, body)
                    putExtra(Intent.EXTRA_TITLE, fileName)
                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                }
                context.startActivity(Intent.createChooser(intent, "分享这场思路"))
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.sceneName.ifBlank { "回看" }) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { innerPadding ->
        when {
            state.notFound -> Text(
                text = "这个会话找不到了（可能数据被清过）。",
                modifier = Modifier.padding(innerPadding).padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )

            view != null -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "你说要做：${view.session.intent}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            view.session.summary?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = HistoryFormat.full(view.session.updatedAt) +
                                    " · ${state.turns.size} 条消息 · 单子 ${view.sheet.allItems().size} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                if (state.open) {
                                    Button(onClick = { onOpenChat(sessionId) }) { Text("继续聊") }
                                } else {
                                    Button(onClick = { viewModel.reopen { onOpenChat(sessionId) } }) {
                                        Text("接着深挖")
                                    }
                                }
                                OutlinedButton(onClick = { onOpenSheet(sessionId) }) {
                                    Text("看确认单（${view.sheet.allItems().size}）")
                                }
                                OutlinedButton(onClick = ::shareMarkdown) { Text("导出 MD") }
                            }
                            // M5：这场思路的方案文档（没生成过进去生成）
                            OutlinedButton(
                                onClick = { onOpenPlan(sessionId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("方案文档") }
                            // M6：调研验证（和上面「看确认单」同一个条件：有单子才给点）
                            OutlinedButton(
                                onClick = { onOpenResearch(sessionId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("调研验证") }
                        }
                    }
                }
                // M4：这份结论基于哪几份旧结论（有引用才出现）
                if (state.citations.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "这场带过 ${state.citations.size} 份旧结论",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                state.citations.forEach { citation ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(onClick = { onOpenDetail(citation.sessionId) }),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = citation.sceneName,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                text = citation.summary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                            )
                                        }
                                        Text("看")
                                    }
                                }
                            }
                        }
                    }
                }
                items(state.turns, key = { it.id }) { turn ->
                    HistoryTurnItem(turn)
                }
            }

            else -> Box(Modifier.fillMaxSize()) {
                Text(
                    "正在读…",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryTurnItem(turn: Turn) {
    val isUser = turn.role == TurnRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (isUser) "你" else "它",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 10.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
