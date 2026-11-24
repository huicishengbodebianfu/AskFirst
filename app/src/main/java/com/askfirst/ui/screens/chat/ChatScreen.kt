package com.askfirst.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askfirst.di.AppContainer
import com.askfirst.domain.model.EngineQuestion
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import com.askfirst.domain.usecase.AskFirstFacade

/**
 * 提问流：一题一卡（默认开放式输入，推荐答案显式给出，选项必带「我自己写」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    onOpenSheet: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(container, sessionId),
        key = sessionId,
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // LazyColumn 前面还有固定 item（"你说要做" + 可能有的引用横幅），
    // 直接滚到 turns.lastIndex 会停在最新消息上两行（docs/28 P2-9）
    val headerCount = 1 + if (state.citationCount > 0) 1 else 0
    LaunchedEffect(state.turns.size, headerCount) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(state.turns.lastIndex + headerCount)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.sceneName.ifBlank { "提问" }) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = { onOpenSheet(sessionId, false) }) {
                        Text("看单子（${state.sheetItemCount}）")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        text = "你说要做：${state.intent}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (state.citationCount > 0) {
                    item {
                        Text(
                            text = "带了 ${state.citationCount} 份旧结论当背景",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                items(state.turns, key = { it.id }) { turn -> TurnBubble(turn) }
                item { Column(Modifier.padding(bottom = 8.dp)) {} }
            }

            AnswerPanel(
                state = state,
                onAnswerChange = viewModel::onAnswerChange,
                onChooseOption = viewModel::chooseOption,
                onSend = viewModel::send,
                onSkip = viewModel::skip,
                onRetry = viewModel::retry,
                onStop = { onOpenSheet(sessionId, true) },
                // 「先看看单子」只是预览：绝不许借这个按钮偷偷再调一次模型（docs/28 P1-4）
                onPreview = { onOpenSheet(sessionId, false) },
            )
        }
    }
}

@Composable
private fun TurnBubble(turn: Turn) {
    val isUser = turn.role == TurnRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            val text = if (turn.text == AskFirstFacade.SKIP_MARKER) "（跳过了这个问题）" else turn.text
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (turn.text == AskFirstFacade.SKIP_MARKER) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontStyle = if (turn.text == AskFirstFacade.SKIP_MARKER) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}

@Composable
private fun AnswerPanel(
    state: ChatUiState,
    onAnswerChange: (String) -> Unit,
    onChooseOption: (String) -> Unit,
    onSend: () -> Unit,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onPreview: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state.busy -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "正在想…（已收到 ${state.progressChars} 字）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error != null -> {
                Text(
                    text = state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canRetry) {
                        Button(onClick = onRetry) { Text("重试") }
                    }
                    OutlinedButton(onClick = onPreview) { Text("先看看单子") }
                }
            }

            else -> {
                if (state.blockReason != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = state.blockReason,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                state.questions.forEach { question ->
                    QuestionCard(
                        question = question,
                        answer = state.answer,
                        onAnswerChange = onAnswerChange,
                        onChooseOption = onChooseOption,
                    )
                }

                OutlinedTextField(
                    value = state.answer,
                    onValueChange = onAnswerChange,
                    placeholder = { Text("用你自己的话说，一句流水账也行") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSkip, enabled = !state.busy) { Text("跳过这题") }
                    Button(onClick = onSend, enabled = state.canSend) { Text("回答") }
                    OutlinedButton(onClick = onStop) { Text("够了，出确认单") }
                }

                if (state.nudge != null) {
                    Text(
                        text = state.nudge,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: EngineQuestion,
    answer: String,
    onAnswerChange: (String) -> Unit,
    onChooseOption: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(question.text, style = MaterialTheme.typography.bodyLarge)
            if (question.why.isNotBlank()) {
                Text(
                    text = "为什么问这个：${question.why}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!question.recommended.isNullOrBlank()) {
                Text(
                    text = "我的猜测：${question.recommended}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 只有答案真的对应不同取舍方向时模型才会给选项；末尾强制「我自己写」（改动 #2）
            if (question.options.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    question.options.forEach { option ->
                        val selected = answer == option
                        if (selected) {
                            Button(onClick = { onAnswerChange("") }) { Text(option) }
                        } else {
                            OutlinedButton(onClick = { onChooseOption(option) }) { Text(option) }
                        }
                    }
                    OutlinedButton(onClick = { onAnswerChange("") }) { Text("我自己写") }
                }
            }
        }
    }
}