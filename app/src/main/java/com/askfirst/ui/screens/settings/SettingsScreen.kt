package com.askfirst.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.askfirst.core.CrashLogger
import com.askfirst.di.AppContainer

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onShowOnboarding: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasCrashLog by remember { mutableStateOf(CrashLogger.latest(context) != null) }

    SettingsContent(
        state = state,
        onBack = onBack,
        onBaseUrlChange = viewModel::onBaseUrlChange,
        onModelChange = viewModel::onModelChange,
        onKeyChange = viewModel::onKeyChange,
        onSave = { viewModel.save() },
        onTest = viewModel::testConnection,
        onSpeedTest = viewModel::speedTest,
        onClearKey = viewModel::clearKey,
        onLoadModels = viewModel::loadModels,
        onPickModel = viewModel::pickModel,
        onDismissModels = viewModel::dismissModels,
        onPatChange = viewModel::onPatChange,
        onSavePat = viewModel::savePat,
        onClearPat = viewModel::clearPat,
        onTestGithub = viewModel::testGithub,
        onProxyChange = viewModel::onProxyChange,
        onRecheckChange = viewModel::onRecheckChange,
        onSaveResearch = viewModel::saveResearchSettings,
        hasCrashLog = hasCrashLog,
        onShareLog = {
            val file = CrashLogger.latest(context) ?: return@SettingsContent
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "AskFirst 崩溃日志")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "把日志发给开发者"))
            }
        },
        onClearLogs = {
            CrashLogger.clear(context)
            hasCrashLog = CrashLogger.latest(context) != null
        },
        onShowOnboarding = onShowOnboarding,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onSpeedTest: () -> Unit,
    onClearKey: () -> Unit,
    onLoadModels: () -> Unit,
    onPickModel: (String) -> Unit,
    onDismissModels: () -> Unit,
    onPatChange: (String) -> Unit,
    onSavePat: () -> Unit,
    onClearPat: () -> Unit,
    onTestGithub: () -> Unit,
    onProxyChange: (String) -> Unit,
    onRecheckChange: (Boolean) -> Unit,
    onSaveResearch: () -> Unit,
    hasCrashLog: Boolean,
    onShareLog: () -> Unit,
    onClearLogs: () -> Unit,
    onShowOnboarding: () -> Unit,
) {
    // 模型选择框：point 一个就换上（换模型不用再点保存）
    if (state.models.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissModels,
            title = { Text("选一个模型（共 ${state.models.size} 个）") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(state.models) { name ->
                        TextButton(
                            onClick = { onPickModel(name) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (name == state.model) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismissModels) { Text("取消") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "模型服务（OpenAI 兼容）",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("base_url") },
                placeholder = { Text("https://api.deepseek.com/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.model,
                onValueChange = onModelChange,
                label = { Text("model") },
                placeholder = { Text("deepseek-chat") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            // 「拉取模型列表」：省得手敲模型名、也省得不知道网关上有哪些模型。
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onLoadModels, enabled = state.canLoadModels) {
                    Text(if (state.loadingModels) "正在拉…" else "拉取模型列表")
                }
                Text(
                    text = "从上面的地址问一次有哪些模型，点一个就换上",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            state.modelsMessage?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.modelsMessageIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedTextField(
                value = state.keyInput,
                onValueChange = onKeyChange,
                label = { Text("api_key") },
                placeholder = {
                    Text(
                        if (state.hasKey) {
                            "已保存 ${state.maskedKey}（要换就粘新的）"
                        } else {
                            "sk-…（只存本机加密存储）"
                        },
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.usingBuiltInKey) {
                Text(
                    text = "现在用的是「自用」里写死的内置 Key（${state.maskedKey}）。" +
                        "想换成自己的就在上面粘一把并按保存。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSave, enabled = state.canSave && !state.busy) { Text("保存") }
                OutlinedButton(onClick = onTest, enabled = state.canSave && !state.busy) {
                    Text(if (state.testing) "正在测…" else "测试连通")
                }
                OutlinedButton(onClick = onSpeedTest, enabled = state.canSave && !state.busy && !state.speedTesting) {
                    Text(if (state.speedTesting) "测速中…" else "测一下速度")
                }
                if (state.hasKey) {
                    TextButton(onClick = onClearKey, enabled = !state.busy) { Text("清除 Key") }
                }
            }

            if (state.speedText != null) {
                Text(
                    text = state.speedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.message != null) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (state.testOk == true) {
                Text("✅ 这个地址和 Key 能通", style = MaterialTheme.typography.bodySmall)
            } else if (state.testOk == false) {
                Text("❌ 没通，看上面的原因", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ResearchSettingsSection(
                state = state,
                onPatChange = onPatChange,
                onSavePat = onSavePat,
                onClearPat = onClearPat,
                onTestGithub = onTestGithub,
                onProxyChange = onProxyChange,
                onRecheckChange = onRecheckChange,
                onSaveResearch = onSaveResearch,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("崩溃日志（闪退时用）", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (hasCrashLog) {
                    "上次闪退的日志已经存在本机。点下面按钮用微信/QQ 发给他，他才能修。"
                } else {
                    "还没有崩溃记录。以后闪退会自动记在本机，不上传。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShareLog, enabled = hasCrashLog) { Text("把日志发给他") }
                TextButton(onClick = onClearLogs, enabled = hasCrashLog) { Text("清掉") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("关于费用与隐私", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "模型费用走你自己的 Key，开发者不代付、不代收。\n" +
                    "Key 只存在这台手机的加密存储里，不进日志、不进导出文件；" +
                    "请求直接发往你上面填的地址。\n" +
                    "所有思路记录只存在本机，没有账号、没有云端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "填法：到服务商控制台创建 API Key，把 base_url（到 /v1 为止）、key、model 名三样填上面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onShowOnboarding) { Text("重新看引导") }
        }
    }
}

/**
 * 查资料这块：GitHub 的访问凭证（只看后 4 位）、测试检索、代理、复核开关与隐私提示。
 * 凭证明文只进加密存储，页面上永远只有最后四位。
 */
@Composable
private fun ResearchSettingsSection(
    state: SettingsUiState,
    onPatChange: (String) -> Unit,
    onSavePat: () -> Unit,
    onClearPat: () -> Unit,
    onTestGithub: () -> Unit,
    onProxyChange: (String) -> Unit,
    onRecheckChange: (Boolean) -> Unit,
    onSaveResearch: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("查资料（GitHub）", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "「查查别人做过没」用的是 GitHub 的公开检索。不填也能查，只是查得浅" +
                "（代码这一类要凭证才让查）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.patInput,
            onValueChange = onPatChange,
            label = { Text("访问凭证（PAT）") },
            placeholder = {
                Text(
                    if (state.hasPat) {
                        "已保存 ${state.maskedPat}（要换就粘新的）"
                    } else {
                        "ghp_…（只存本机加密存储，界面上只看得到后 4 位）"
                    },
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSavePat) { Text("保存") }
            OutlinedButton(onClick = onTestGithub, enabled = !state.testingGithub) {
                Text(if (state.testingGithub) "正在试…" else "测试检索")
            }
            if (state.hasPat) {
                TextButton(onClick = onClearPat) { Text("清除 token") }
            }
        }
        if (state.hasPat && state.maskedPat.isNotBlank()) {
            Text(
                text = "当前已保存：${state.maskedPat}" +
                    if (state.usingBuiltInPat) "（「自用」里写死的内置值；想换就在上面粘新的）" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.githubTestText?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.githubTestOk == true) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = state.githubProxy,
            onValueChange = onProxyChange,
            label = { Text("代理或镜像地址（可留空）") },
            placeholder = { Text("https://你的代理或镜像/v3") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "直连 GitHub 不稳时再填：填了先从它走，失败了会自动退回直连。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.researchRecheck, onCheckedChange = onRecheckChange)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "GitHub 里没搜到就换个词再查一次",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = "关掉之后只能说「没查到」，不能说「没人做过」。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        OutlinedButton(onClick = onSaveResearch, enabled = !state.busy) { Text("保存查询设置") }
        state.researchMessage?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.researchMessageIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "隐私：检索关键词会明文发给 GitHub（公开接口）。公司还没公开的项目，别把关键词写太具体。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}