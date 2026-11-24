package com.askfirst.ui.screens.cite

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.askfirst.di.AppContainer
import com.askfirst.domain.citation.CitationPolicy
import com.askfirst.domain.model.CitationCandidate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M4 引用选择页：搜旧确认单 → 勾 ≤3 份 → 确定带回首页。
 * 界面零内部词：引用叫「带上旧结论」，不出现 citation/digest。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitePickerScreen(
    container: AppContainer,
    initialSelected: List<String>,
    onConfirm: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: CitePickerViewModel = viewModel(
        factory = CitePickerViewModel.factory(container, initialSelected),
        key = "cite_picker",
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("带上旧结论") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(
                        onClick = { onConfirm(state.selected) },
                        enabled = state.canConfirm,
                    ) { Text("带 ${state.selected.size} 份") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "最多带 ${CitationPolicy.MAX_CITATIONS} 份。带了它，提问时会参考里面的结论，" +
                    "但它不会替你回答。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = state.keyword,
                onValueChange = viewModel::onKeywordChange,
                placeholder = { Text("搜一句旧结论里的词（不搜就看最近的）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
            state.message?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (state.loading) {
                CircularProgressIndicator()
            } else if (state.candidates.isEmpty()) {
                Text(
                    text = if (state.keyword.isBlank()) {
                        "还没有出过单子的会话。先去聊一场、出一份单，再回来带它。"
                    } else {
                        "没搜到带这个词的旧结论。换个词试试，或者清空搜索看最近的。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(state.candidates, key = { it.sheetId }) { candidate ->
                        CandidateCard(
                            candidate = candidate,
                            checked = candidate.sheetId in state.selected,
                            onToggle = { viewModel.toggle(candidate.sheetId) },
                        )
                    }
                    item { TextButton(onClick = onBack) { Text("先不带") } }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: CitationCandidate,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Text(
                    text = candidate.sceneName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = candidate.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    text = formatTime(candidate.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(epochMillis))
