package com.askfirst.ui.screens.sheet

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.AppliedTo
import com.askfirst.domain.model.Cols
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetItem
import com.askfirst.domain.model.Verdict
import kotlinx.coroutines.launch

/**
 * 确认单（五栏）：条目可点开改、可重新出单、一键复制成提示词；第 4 栏不足会明确拦住。
 * M6 起第 2/5 栏下面各挂一块「调研补充」（只读），底部多一个「查查别人做过没」的入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScreen(
    container: AppContainer,
    sessionId: String,
    buildOnOpen: Boolean = false,
    onOpenPlan: () -> Unit = {},
    onOpenResearch: () -> Unit = {},
    onBack: () -> Unit,
) {
    val viewModel: SheetViewModel = viewModel(
        factory = SheetViewModel.factory(container, sessionId, buildOnOpen),
        key = "sheet-$sessionId-$buildOnOpen",
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 回到这一页就重读一遍：从「查查别人做过没」查完退回来，第 2/5 栏下面的
    // 「调研补充」要立刻出现——不然用户会以为白查了（M6 真机验收现场修掉的那条）。
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    // 点「调研补充」里的一行：用它带的链接打开浏览器（打不开就说一句，不静默）
    fun openLink(url: String) {
        if (url.isBlank()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                Toast.makeText(context, "这台手机打不开这个链接，可以先把它复制走", Toast.LENGTH_SHORT).show()
            }
    }

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

    LaunchedEffect(state.prompt) {
        val text = state.prompt
        if (text != null) {
            clipboard.setText(AnnotatedString(text))
            viewModel.consumePrompt()
        }
    }

    var editing by remember { mutableStateOf<SheetItem?>(null) }
    var editText by remember { mutableStateOf("") }

    val sourcesItem = state.sourcesItem
    if (sourcesItem != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeSources,
            title = { Text("这条从哪来") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "「${sourcesItem.text}」",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                    if (state.sources.isEmpty()) {
                        Text("找不到原始回答了（可能已经被清掉）。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.sources.forEach { turn ->
                            Text(
                                text = "· ${turn.text}",
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

    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("改这一条") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = editing
                    editing = null
                    if (target != null) viewModel.editItem(target.id, editText)
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("思路确认单") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = viewModel::build, enabled = !state.busy) { Text("重新出单") }
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
            Text(
                text = "你说要做：${state.intent}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "正在按你说过的内容出单…（已收到 ${state.progressChars} 字）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val check = state.check
            val headline = check?.headline
            if (headline != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        text = headline,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            val warning = check?.warningText
            if (warning != null) {
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val message = state.message
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (state.canFallback) {
                OutlinedButton(onClick = viewModel::fallback, enabled = !state.busy) {
                    Text("先用手里这些内容出单")
                }
            }

            val sheet = state.sheet
            if (sheet == null) {
                Text(
                    text = if (state.loading) "读取中…" else "还没有内容",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Cols.ALL.forEach { col ->
                    ColumnCard(
                        col = col,
                        items = sheet.column(col),
                        onEdit = { item ->
                            editing = item
                            editText = item.text
                        },
                        onShowSources = viewModel::peekSources,
                    )
                    // M6：第 2/5 栏下面各挂一块只读的「调研补充」（条目本身一条都不动）
                    val appendTarget = when (col) {
                        Cols.CONSTRAINTS -> AppliedTo.COL2
                        Cols.OPEN_QUESTIONS -> AppliedTo.COL5
                        else -> null
                    }
                    if (appendTarget != null) {
                        val appends = state.researchAppends.filter { it.appliedTo == appendTarget }
                        if (appends.isNotEmpty()) {
                            ResearchAppendCard(
                                verdicts = appends,
                                urls = state.researchUrls,
                                onOpenUrl = ::openLink,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = viewModel::copyPrompt, enabled = !state.busy) { Text("复制成提示词") }
                OutlinedButton(onClick = onBack) { Text("继续聊") }
                TextButton(onClick = viewModel::finish, enabled = !state.busy) { Text("存下来") }
                TextButton(onClick = ::shareMarkdown, enabled = !state.busy && sheet != null) { Text("导出 MD") }
            }
            // M5：把这张单子写成一份能发给同事看的文档
            OutlinedButton(
                onClick = onOpenPlan,
                enabled = !state.busy && sheet != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("生成方案.md（能发给同事看的那份）")
            }
            // M6：查查别人做过没（查来的结论只回写到第 2/5 栏下面那块，不进条目）
            OutlinedButton(
                onClick = onOpenResearch,
                enabled = !state.busy && sheet != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("查查别人做过没")
            }
            Text(
                text = "点任意一条可以改。左边括号里的数字是它来自几条回答；改过的条目重新出单也不会被冲掉。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ColumnCard(
    col: Int,
    items: List<SheetItem>,
    onEdit: (SheetItem) -> Unit,
    onShowSources: (SheetItem) -> Unit,
) {
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
            Text(
                text = "$col ${Cols.title(col)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (items.isEmpty()) {
                Text(
                    text = "（空）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "· ${item.text}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onEdit(item) },
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        TextButton(
                            onClick = { onShowSources(item) },
                            enabled = item.sourceMessageIds.isNotEmpty(),
                        ) {
                            Text(
                                text = if (item.edited) "已改" else "看出处 ${item.sourceMessageIds.size}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 调研补充这块：第 2/5 栏下面那份只读的「查到的东西」。
 * 它**不是条目**（不进 sheet_item、不带原话出处），所以换一套底色，别让人误当单子内容。
 */
@Composable
private fun ResearchAppendCard(
    verdicts: List<Verdict>,
    urls: Map<String, String>,
    onOpenUrl: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("调研补充 · 查到的东西", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "这些来自公开仓库与 issue，不进你的原话出处",
                style = MaterialTheme.typography.labelSmall,
            )
            verdicts.forEach { verdict ->
                val url = urls[verdict.id].orEmpty()
                Text(
                    text = "${verdict.judgement.mark} ${verdict.rationale}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = url.isNotBlank()) { onOpenUrl(url) },
                )
            }
        }
    }
}

