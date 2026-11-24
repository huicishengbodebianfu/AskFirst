package com.askfirst.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首启引导（方案附录 B 的 5 步压成 1 屏，M1-01 的门槛页）。
 * **亲友最大的门槛不是这个 App，是"去哪拿 Key"**，所以这里只讲这件事 + 隐私与费用。
 */
@Composable
fun OnboardingScreen(
    onOpenSettings: () -> Unit,
    onStartUsing: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("AskFirst", style = MaterialTheme.typography.displaySmall)
            Text(
                text = "一个会反过来问你的 App。开工前先打开它，它一轮一轮问你，你说「够了」，" +
                    "它把聊天压成一份能直接开工的思路确认单。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StepCard(
                step = "1",
                title = "先拿一个模型 Key（只做这一次）",
                body = "去你信任的模型服务商控制台（DeepSeek、Kimi、智谱等）注册 → 充值 → " +
                    "创建 API Key → 复制三样东西：\n" +
                    "· base_url（例如 https://api.deepseek.com/v1）\n" +
                    "· api_key（sk- 开头那一长串）\n" +
                    "· model 名（例如 deepseek-chat）\n" +
                    "填进下面的「设置 → 模型服务」，点「测试连通」看到 ✅ 就行。",
            )
            StepCard(
                step = "2",
                title = "它怎么用",
                body = "选一个场景（技术方案 / 项目立项 / 复盘）→ 一句话说清你要做的事 → " +
                    "回答问题（每题都有「跳过」）→ 觉得够了就点「够了，出确认单」→ " +
                    "点「复制成提示词」，粘给你平时用的 AI，让它直接开工。",
            )
            StepCard(
                step = "3",
                title = "你的东西在哪",
                body = "所有记录只存在这台手机上，没有账号、没有云端。\n" +
                    "模型的调用费走你自己的 Key，开发者不代付。\n" +
                    "请求只发往你填的那个地址；Key 存在本机加密存储里，不进日志、不进导出文件。",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpenSettings) { Text("去填 Key") }
                TextButton(onClick = onStartUsing) { Text("先随便看看") }
            }
            Text(
                text = "以后再想看这页：设置页底部「重新看引导」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Start),
            )
        }
    }
}

@Composable
private fun StepCard(step: String, title: String, body: String) {
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
                text = "$step. $title",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}