package com.askfirst.ui.screens.research

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.AppliedTo
import com.askfirst.domain.model.Evidence
import com.askfirst.domain.model.Judgement
import com.askfirst.domain.model.Research
import com.askfirst.domain.model.ResearchCheck
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.Verdict
import com.askfirst.domain.research.QuestionDrafting

/**
 * M6 调研页：立题（可改可删）→ 查（进度可取消）→ 报告（坏消息在前 + 逐条「放哪」+
 * 存盘/分享/再查/拿去桌面端查）。失败也照渲残本，不给「查过了」的假相。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
) {
    val viewModel: ResearchViewModel = viewModel(
        factory = ResearchViewModel.factory(container, sessionId),
        key = "research-$sessionId",
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val resolver = context.contentResolver

    // 打不开链接（手机没浏览器）时说一句人话，而不是点了没反应
    fun openLink(url: String) {
        if (url.isBlank()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                Toast.makeText(context, "这台手机打不开这个链接，可以先把它复制走", Toast.LENGTH_SHORT).show()
            }
    }

    // SAF：选一个目录 → 记住授权 → 以后直接写进去（和方案页共用同一份目录记忆）
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.saveReport(resolver, uri)
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
            context.startActivity(Intent.createChooser(intent, "分享这份验证报告"))
        }
        viewModel.consumeShare()
    }

    var editing by remember { mutableStateOf<ResearchQuestion?>(null) }
    var editText by remember { mutableStateOf("") }
    var placing by remember { mutableStateOf<Verdict?>(null) }
    var offlineCopied by remember(state.offlineOpen) { mutableStateOf(false) }

    // 改问题：只改文字，关键词保留（换说法不该把想好的检索词弄丢）
    val editingQuestion = editing
    if (editingQuestion != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("改这个问题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "要搜的词：${editingQuestion.keywords.joinToString(" / ")}（改文字不动它们）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 校验没过就别关弹窗：关了等于把他刚敲的字扔了（docs/28 P2-16）
                    if (viewModel.updateQuestion(editingQuestion.idx, editText)) editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }

    // 放哪：只有三个落点，「选定方案」那栏根本没有入口
    val placingVerdict = placing
    if (placingVerdict != null) {
        AlertDialog(
            onDismissRequest = { placing = null },
            title = { Text("这一条放哪") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = placingVerdict.rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    AppliedTo.entries.forEach { target ->
                        TextButton(
                            onClick = {
                                placing = null
                                viewModel.moveVerdict(placingVerdict.id, target)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(target.label) }
                    }
                    Text(
                        text = "「选定方案」那栏不接收查来的东西：方案得你自己定。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { placing = null }) { Text("先不动") } },
        )
    }

    if (state.offlineOpen) {
        OfflineDialog(
            prompt = state.offlinePrompt,
            input = state.offlineInput,
            busy = state.offlineBusy,
            error = state.offlineError,
            copied = offlineCopied,
            onInputChange = viewModel::onOfflineInputChange,
            onImport = viewModel::importOffline,
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                offlineCopied = true
            },
            onShare = { text ->
                runCatching {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "把这段发到电脑上"))
                }
            },
            onClose = viewModel::closeOffline,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("查查别人做过没") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
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
                text = "查的是公开仓库和 issue：别人做过没、踩过什么坑。查来的东西只回答「有没有人这么干过」，不替你选方案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

            when (state.phase) {
                ResearchPhase.DRAFT -> DraftContent(
                    state = state,
                    onEdit = { question ->
                        editing = question
                        editText = question.text
                    },
                    onDelete = viewModel::removeQuestion,
                    onAdd = viewModel::addQuestion,
                    onRedraft = viewModel::refreshDraft,
                    onStart = viewModel::start,
                    onShowPrevious = viewModel::showPrevious,
                    onOffline = viewModel::openOffline,
                )

                ResearchPhase.RUNNING -> RunningContent(state = state, onCancel = viewModel::cancelRun)

                ResearchPhase.REPORT -> {
                    val research = state.research
                    if (research == null) {
                        FailedContent(
                            failure = "这次什么都没查回来。",
                            onRetry = viewModel::backToDraft,
                            onOffline = viewModel::openOffline,
                        )
                    } else {
                        ReportContent(
                            research = research,
                            check = state.check,
                            partial = state.partial,
                            failure = state.failure,
                            movingVerdictId = state.movingVerdictId,
                            onPlace = { verdict -> placing = verdict },
                            onOpenUrl = ::openLink,
                            onSave = {
                                if (!viewModel.saveToRemembered(resolver)) treePicker.launch(null)
                            },
                            onShare = viewModel::prepareShare,
                            onRetry = viewModel::backToDraft,
                            onOffline = viewModel::openOffline,
                        )
                    }
                }

                ResearchPhase.FAILED -> FailedContent(
                    failure = state.failure,
                    onRetry = viewModel::backToDraft,
                    onOffline = viewModel::openOffline,
                )
            }
        }
    }
}

/** 立题这块：把这场要查的事列成几张能改能删的问题卡，也能自己加一个。 */
@Composable
private fun DraftContent(
    state: ResearchUiState,
    onEdit: (ResearchQuestion) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: (String, String) -> Boolean,
    onRedraft: () -> Unit,
    onStart: () -> Unit,
    onShowPrevious: () -> Unit,
    onOffline: () -> Unit,
) {
    if (state.drafting) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = "正在想该查什么…（想不出来我也会按你确认单里的词凑几个出来）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    state.draftNote?.let { note ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text(note, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }

    val draftError = state.draftError
    if (draftError != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text(draftError, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRedraft) { Text("重新想一遍") }
        }
    }

    Text(
        text = "要查的问题（${state.questions.size}/${QuestionDrafting.MAX_QUESTIONS}）",
        style = MaterialTheme.typography.titleSmall,
    )
    if (state.questions.isEmpty() && !state.drafting) {
        Text(
            text = "现在一个问题都没有。下面自己加一个，或者点上面「重新想一遍」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.questions.forEach { question ->
        QuestionCard(
            question = question,
            onEdit = { onEdit(question) },
            onDelete = { onDelete(question.idx) },
        )
    }

    AddQuestionRow(enabled = !state.atQuestionLimit, onAdd = onAdd)

    state.previous?.let { previous ->
        TextButton(onClick = onShowPrevious) {
            Text("看上次那份报告（${previous.coverageText} 个问题有据可查）", style = MaterialTheme.typography.labelMedium)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onStart, enabled = state.canStart) { Text("开始查") }
        OutlinedButton(onClick = onOffline, enabled = state.questions.isNotEmpty()) {
            Text("拿去桌面端查")
        }
    }
    Text(
        text = if (state.questions.isEmpty()) {
            "先有个问题（上面可以自己加一个）才能开始查，也才能打包成电脑上能跑的那段话。"
        } else {
            "点「开始查」之后最多等 60 秒，等不及可以取消；同一个问题查过的话第二次会快一些。"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** 一张问题卡：问题 + 要搜的词 + 为什么问，点一下就能改，右边「改 / 删掉」。 */
@Composable
private fun QuestionCard(
    question: ResearchQuestion,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${question.idx}. ${question.text}", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "要搜的词：${question.keywords.joinToString(" / ")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (question.why.isNotBlank()) {
                Text(
                    text = question.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("改") }
                TextButton(onClick = onDelete) { Text("删掉") }
            }
        }
    }
}

/** 加问题这块：一个问题 + 一行词（词留空就按问题文字自己抽几个）。 */
@Composable
private fun AddQuestionRow(
    enabled: Boolean,
    onAdd: (String, String) -> Boolean,
) {
    var text by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("自己加一个问题") },
            placeholder = { Text("比如：首屏图片缓存一般怎么分层") },
            minLines = 2,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = keywords,
            onValueChange = { keywords = it },
            label = { Text("要搜的词（逗号分开，可留空）") },
            placeholder = { Text("缓存, 分层, 首屏") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    // 加成功了才清空，被拦下来时输入还在，改一下就能再点
                    if (onAdd(text, keywords)) {
                        text = ""
                        keywords = ""
                    }
                },
                enabled = enabled && text.isNotBlank(),
            ) { Text("加进去") }
            if (!enabled) {
                Text(
                    text = "已经到 ${QuestionDrafting.MAX_QUESTIONS} 个上限了，先删一个再加。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 进度这块：一个转圈 + 最后三行 + 「取消」（不许把人锁在这 60 秒里）。 */
@Composable
private fun RunningContent(
    state: ResearchUiState,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("正在查，最多等 60 秒", style = MaterialTheme.typography.titleSmall)
            }
            val lines = state.progress.takeLast(3)
            if (lines.isEmpty()) {
                Text(
                    text = "正在连公开仓库找…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lines.forEach { line ->
                Text(
                    text = "· $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    OutlinedButton(onClick = onCancel) { Text("取消") }
    Text(
        text = "取消了也不会留下半份东西；想接着查可以再点一次「开始查」，问题都还在。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** 失败这块：一句人话说明发生了什么 + 下一步两个出口。 */
@Composable
private fun FailedContent(
    failure: String?,
    onRetry: () -> Unit,
    onOffline: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = failure ?: "这次没查成。",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onRetry) { Text("再查一次") }
        OutlinedButton(onClick = onOffline) { Text("拿去桌面端查") }
    }
    Text(
        text = "「再查一次」回上一步，问题都留着，可以改完再查；" +
            "「拿去桌面端查」是把问题写成一段话，你在有网的电脑上跑完再把结果粘回来。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * 报告这块：顶部覆盖度与没查成的地方 → 按判定分组（坏消息在最前）→
 * 逐条「放哪」→ 存盘 / 分享 / 再查 / 拿去桌面端查。
 */
@Composable
private fun ReportContent(
    research: Research,
    check: ResearchCheck?,
    partial: Boolean,
    failure: String?,
    movingVerdictId: String?,
    onPlace: (Verdict) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onOffline: () -> Unit,
) {
    failure?.let { text ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (partial) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text(
                text = "残缺的报告：这次没查全，下面只有已经拿到的那部分。缺的逐条写在「证据缺在哪」里。",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Text(
        text = research.title.ifBlank { "这份验证报告" },
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = "证据覆盖 ${research.coverageText} 问题 · " +
            "耗时 ${(research.elapsedMs / 1000).coerceAtLeast(1)}s",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (research.failures.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text(
                text = "有 ${research.failures.size} 处没查成（下面写了原因）",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (check != null && check.blocking.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(check.headline, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "这份我不认：先照着它改确认单，会比不改更糟。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    if (check != null && check.blocking.isEmpty()) {
        check.warnings.forEach { warning ->
            Text(
                text = "· $warning",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    if (research.verdicts.isEmpty()) {
        Text(
            text = "这次一条能站得住的结论都没有。证据不够的时候，不下结论比下错结论有用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 顺序必须坏消息在前：反模式 → 已知坑 → 常见做法 → 无人区 → 没查够的
    Judgement.entries.sortedBy { it.reportOrder }.forEach { judgement ->
        val verdicts = research.verdictsOf(judgement).filter { verdict ->
            // 没人搜过的结论必须能说清搜了什么词，说不清就不显示这一组
            if (judgement == Judgement.UNEXPLORED) keywordsOf(research, verdict).isNotEmpty() else true
        }
        if (verdicts.isNotEmpty()) {
            VerdictGroupCard(
                judgement = judgement,
                verdicts = verdicts,
                research = research,
                movingVerdictId = movingVerdictId,
                onPlace = onPlace,
                onOpenUrl = onOpenUrl,
            )
        }
    }

    if (research.failures.isNotEmpty()) FailureList(research)

    HorizontalDivider()

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onSave) { Text("保存报告") }
        OutlinedButton(onClick = onShare) { Text("分享") }
        TextButton(onClick = onRetry) { Text("再查一次") }
    }
    OutlinedButton(onClick = onOffline, modifier = Modifier.fillMaxWidth()) { Text("拿去桌面端查") }
    Text(
        text = "存下来的是一份 .md：顶部写着证据覆盖几个问题、哪几处没查成，每条结论后面跟着来源链接。" +
            "文件落在你刚选的那个文件夹里，下次还存那儿。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/** 一组判定：标题带标记与一句人话说明（反模式永远排在最前）。 */
@Composable
private fun VerdictGroupCard(
    judgement: Judgement,
    verdicts: List<Verdict>,
    research: Research,
    movingVerdictId: String?,
    onPlace: (Verdict) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${judgement.mark} ${judgement.label}", style = MaterialTheme.typography.titleSmall)
            Text(
                text = groupHint(judgement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            verdicts.forEachIndexed { index, verdict ->
                if (index > 0) HorizontalDivider()
                VerdictRow(
                    verdict = verdict,
                    research = research,
                    withKeywords = judgement == Judgement.UNEXPLORED,
                    movingVerdictId = movingVerdictId,
                    onPlace = onPlace,
                    onOpenUrl = onOpenUrl,
                )
            }
        }
    }
}

/** 一条判定：一句「意味着什么」+ 反模式的灰色引文 + 前三条来源 + 右边「放哪」。 */
@Composable
private fun VerdictRow(
    verdict: Verdict,
    research: Research,
    withKeywords: Boolean,
    movingVerdictId: String?,
    onPlace: (Verdict) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = verdict.rationale,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = { onPlace(verdict) },
                    enabled = movingVerdictId == null,
                ) { Text("放哪", style = MaterialTheme.typography.labelSmall) }
                Text(
                    text = appliedHint(verdict.appliedTo),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        if (verdict.quotedText.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = verdict.quotedUrl.isNotBlank()) { onOpenUrl(verdict.quotedUrl) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "「${verdict.quotedText}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (verdict.quotedUrl.isNotBlank()) {
                    Text(
                        text = verdict.quotedUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (withKeywords) {
            Text(
                text = "已覆盖关键词：${keywordsOf(research, verdict).joinToString(" / ")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        research.evidenceOf(verdict).take(3).forEach { evidence ->
            EvidenceLine(evidence = evidence, onOpenUrl = onOpenUrl)
        }
    }
}

/** 一条来源：标题 · ★stars · 链接（点一下用浏览器打开）。 */
@Composable
private fun EvidenceLine(
    evidence: Evidence,
    onOpenUrl: (String) -> Unit,
) {
    val stars = if (evidence.stars > 0) " · ★${evidence.stars}" else ""
    Text(
        text = "· ${evidence.title}$stars · ${evidence.url}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(evidence.url) },
    )
}

/** 失败可见这块：哪个问题、哪一步没查成、为什么，逐条写出来（静默失败最害人）。 */
@Composable
private fun FailureList(research: Research) {
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
            Text("证据缺在哪", style = MaterialTheme.typography.titleSmall)
            research.failures.forEach { failure ->
                Text(
                    text = "❌ ${failureLabel(research, failure.questionIdx)} · ${failure.source}：${failure.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 失败的归属：能对上问题就引问题；`questionIdx = 0` 是整轮级失败（预算用尽 / 模型对照失败），
 * 给个人话标签，别在页面上印出空的「」（docs/28 P2-5）。
 */
private fun failureLabel(research: Research, questionIdx: Int): String {
    research.questions.firstOrNull { it.idx == questionIdx }?.let { return "「${it.text}」" }
    return if (questionIdx <= 0) "【整轮调研】" else "【第 $questionIdx 个问题】"
}

/** 离线外包这块：给桌面端的那段话（复制/分享）+ 把它跑出来的结果粘回来。 */
@Composable
private fun OfflineDialog(
    prompt: String?,
    input: String,
    busy: Boolean,
    error: String?,
    copied: Boolean,
    onInputChange: (String) -> Unit,
    onImport: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("拿去桌面端查") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "把下面这段复制到有网的电脑上跑一遍，它会把答案给你。回来把那段结果整段粘在下面，一样能出报告。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (prompt == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "正在取…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onCopy(prompt) }) { Text("复制") }
                        OutlinedButton(onClick = { onShare(prompt) }) { Text("分享") }
                    }
                    if (copied) {
                        Text(
                            text = "已经复制好了，去电脑上粘吧。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                HorizontalDivider()

                Text(
                    text = "把电脑上跑出来的结果整段粘回来：",
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text("从电脑上复制的那一整段，原样贴进来就行") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onImport,
                    enabled = !busy && input.isNotBlank(),
                ) { Text(if (busy) "正在整理…" else "用它生成报告") }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text("关掉") } },
    )
}

/** 某条判定对应问题的「已覆盖关键词」（报告里没人搜过的结论必须能说出来）。 */
private fun keywordsOf(research: Research, verdict: Verdict): List<String> =
    research.questions
        .firstOrNull { it.idx == verdict.questionIdx }
        ?.keywords
        .orEmpty()
        .filter { it.isNotBlank() }

/** 每种判定的一句人话说明（不给内部术语）。 */
private fun groupHint(judgement: Judgement): String = when (judgement) {
    Judgement.ANTIPATTERN -> "别人明确抱怨过这条路，先看坏消息"
    Judgement.KNOWN_PIT -> "同类项目反复出现的坑"
    Judgement.COMMON -> "好几个项目都这么干，你不是孤例"
    Judgement.UNEXPLORED -> "没找到同类的做法，这件事得你自己验证"
    Judgement.UNGATHERED -> "这次没查够，不下结论"
}

/** 当前落点的一行小字（不写第 3 栏：那里没有入口）。 */
private fun appliedHint(target: AppliedTo): String = when (target) {
    AppliedTo.COL2 -> "→ 第 2 栏"
    AppliedTo.COL5 -> "→ 第 5 栏"
    AppliedTo.APPENDIX -> "→ 只进报告"
}
