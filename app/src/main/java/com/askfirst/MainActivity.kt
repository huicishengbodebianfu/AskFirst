package com.askfirst

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.askfirst.di.AppContainer
import com.askfirst.domain.usecase.SheetOutcome
import com.askfirst.domain.usecase.TurnOutcome
import com.askfirst.ui.AskFirstAppRoot
import com.askfirst.ui.theme.AskFirstTheme
import kotlinx.coroutines.launch

/**
 * 唯一 Activity。M1 挂 NavHost（首页 → 提问流 → 确认单）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AskFirstApp).container

        // ---- 调试钩子（只在 debug 构建里生效，release 无副作用）----
        //   --ez open_settings true          直接打开设置页
        //   --ez seed_dummy_key true         写入假 Key（验证掩码）
        //   --ez clear_key true              清除 Key
        //   --es base_url <url> --es model m 直接改模型地址（e2e 用）
        //   --ez demo_run true               后台跑 3 轮"假模型"问答（验整条流水线）
        //   --es open_chat <sessionId>       启动即进某个会话
        val startInSettings = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true
        val openChat = intent?.getStringExtra(EXTRA_OPEN_CHAT)
        val openSheet = intent?.getStringExtra(EXTRA_OPEN_SHEET)
        val openHistory = intent?.getBooleanExtra(EXTRA_OPEN_HISTORY, false) == true
        val openSceneEdit = intent?.getStringExtra(EXTRA_OPEN_SCENE_EDIT)
        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL)
        val model = intent?.getStringExtra(EXTRA_MODEL)
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY)
        val demoRun = intent?.getBooleanExtra(EXTRA_DEMO_RUN, false) == true
        val demoSheet = intent?.getBooleanExtra(EXTRA_DEMO_SHEET, false) == true
        val demoShort = intent?.getBooleanExtra(EXTRA_DEMO_SHORT, false) == true
        val demoPing = intent?.getBooleanExtra(EXTRA_DEMO_PING, false) == true
        val demoSpeed = intent?.getBooleanExtra(EXTRA_DEMO_SPEED, false) == true

        // 崩溃日志自检：debug 构建里主动抛一次，用来验证"闪退会留下日志"（S7）
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_FORCE_CRASH, false) == true) {
            throw IllegalStateException("AskFirst 崩溃日志自检（debug only，不影响 release）")
        }

        if (BuildConfig.DEBUG) {
            if (intent?.getBooleanExtra(EXTRA_SEED_DUMMY_KEY, false) == true) {
                container.secretStore.putLlmKey(FAKE_KEY_FOR_UI_TEST)
            }
            if (intent?.getBooleanExtra(EXTRA_CLEAR_KEY, false) == true) {
                container.secretStore.clearLlmKey()
            }
            // 设置写入 → 再跑动作，**必须同一个协程顺序执行**：
            // 否则 ping/速度测试会读到上一次的 base_url（写过一次真实的坑）
            val needSetup = baseUrl != null || !apiKey.isNullOrBlank()
            if (needSetup || demoPing || demoSpeed || demoRun) {
                lifecycleScope.launch {
                    if (!apiKey.isNullOrBlank()) {
                        container.secretStore.putLlmKey(apiKey)
                    }
                    if (baseUrl != null) {
                        container.settingsStore.saveConnection(
                            baseUrl = baseUrl,
                            model = model ?: "fake-model",
                            hasApiKey = container.secretStore.hasLlmKey(),
                        )
                    }
                    if (needSetup) {
                        Log.i(DEMO_TAG, "settings saved: base=${baseUrl ?: "(unchanged)"} model=${model ?: "fake-model"}")
                    }
                    if (demoPing) {
                        val started = System.currentTimeMillis()
                        val result = container.llmProvider.ping()
                        Log.i(DEMO_TAG, "ping in ${System.currentTimeMillis() - started}ms -> $result")
                    }
                    if (demoSpeed) {
                        val result = container.speedTester.measure()
                        Log.i(DEMO_TAG, "speed -> $result")
                    }
                    if (demoRun && !demoStarted) {
                        demoStarted = true
                        runDemo(container, buildSheet = demoSheet)
                    }
                }
            }
            if (demoShort && !demoShortStarted) {
                demoShortStarted = true
                lifecycleScope.launch { runShortDemo(container) }
            }
        }

        setContent {
            AskFirstTheme {
                AskFirstAppRoot(
                    container = container,
                    startInSettings = startInSettings,
                    startInChat = openChat,
                    startInSheet = openSheet,
                    startInHistory = openHistory,
                    startInSceneEdit = openSceneEdit,
                )
            }
        }
    }

    /** 用"假引擎"把 3 轮问答跑一遍：只写日志与数据库，不依赖任何点击（S5 端到端验收用）。 */
    private suspend fun runDemo(container: AppContainer, buildSheet: Boolean) {
        container.seedResult.await()
        val scenes = container.facade.sceneSummaries()
        if (scenes.isEmpty()) {
            Log.e(DEMO_TAG, "no scenes")
            return
        }
        val scene = scenes.first()
        val session = container.facade.start(scene.id, "首屏 4 秒太慢，想加缓存")
        Log.i(DEMO_TAG, "session=${session.id} scene=${scene.name}")

        val answers = listOf(
            "打开首屏白屏四秒，第一屏内容一个都没出来",
            "只有我一个人力，两周内要能用上",
            "先做本地缓存加聚合层，别的不做，就缓首屏接口",
        )
        answers.forEachIndexed { index, answer ->
            var done: TurnOutcome.Done? = null
            var failure: String? = null
            container.facade.ask(session.id, answer).collect { outcome ->
                when (outcome) {
                    is TurnOutcome.Done -> done = outcome
                    is TurnOutcome.Failed -> failure = outcome.error.message
                    else -> Unit
                }
            }
            val decided = done
            Log.i(
                DEMO_TAG,
                "round=${index + 1} state=${decided?.decision?.state} " +
                    "covered=${decided?.decision?.covered} " +
                    "questions=${decided?.decision?.questions?.map { "${it.id}:${it.text.take(40)}" }} " +
                    "clarify=${decided?.decision?.clarify?.take(60)} " +
                    "issues=${decided?.decision?.issues?.map { it.kind }} " +
                    "items=${decided?.sheet?.allItems()?.size} err=$failure",
            )
        }
        val view = container.facade.sessionView(session.id)
        Log.i(
            DEMO_TAG,
            "final state=${view?.session?.state} sheetItems=${view?.sheet?.allItems()?.size} " +
                "cols=${view?.sheet?.items?.mapValues { it.value.size }}",
        )

        if (buildSheet) {
            var built: SheetOutcome.Done? = null
            var sheetError: String? = null
            container.facade.buildSheet(session.id).collect { outcome ->
                when (outcome) {
                    is SheetOutcome.Done -> built = outcome
                    is SheetOutcome.Failed -> sheetError = outcome.error.message
                    else -> Unit
                }
            }
            val done = built
            if (done == null) {
                Log.e(DEMO_TAG, "sheet build failed: $sheetError")
                return
            }
            Log.i(
                DEMO_TAG,
                "sheet summary=${done.sheet.summary} items=${done.sheet.allItems().size} " +
                    "cols=${done.sheet.items.mapValues { it.value.size }} " +
                    "canStart=${done.check.canStart} blocking=${done.check.blocking.size} " +
                    "warnings=${done.check.warnings.size} headline=${done.check.headline}",
            )
            done.sheet.items.toSortedMap().forEach { (col, items) ->
                Log.i(DEMO_TAG, "  col$col: ${items.joinToString(" | ") { it.text.take(50) }}")
            }
            val prompt = container.facade.exportPrompt(done.sheet.id)
            Log.i(DEMO_TAG, "PROMPT_START\n$prompt\nPROMPT_END")
        }
    }

    /** 只开题、不问答，然后用降级出单：用来验"第 4 栏不足必须被拦住"（M1-10）。 */
    private suspend fun runShortDemo(container: AppContainer) {
        container.seedResult.await()
        val scene = container.facade.sceneSummaries().firstOrNull() ?: return
        val session = container.facade.start(scene.id, "想做一个记账小工具")
        val done = container.facade.fallbackSheet(session.id)
        Log.i(
            DEMO_TAG,
            "short session=${session.id} items=${done.sheet.allItems().size} " +
                "canStart=${done.check.canStart} blocking=${done.check.blocking.map { it::class.simpleName }} " +
                "headline=${done.check.headline}",
        )
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        const val EXTRA_SEED_DUMMY_KEY = "seed_dummy_key"
        const val EXTRA_CLEAR_KEY = "clear_key"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_MODEL = "model"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_DEMO_RUN = "demo_run"
        const val EXTRA_DEMO_SHEET = "demo_sheet"
        const val EXTRA_DEMO_SHORT = "demo_short"
        const val EXTRA_DEMO_PING = "demo_ping"
        const val EXTRA_DEMO_SPEED = "demo_speed"
        const val EXTRA_OPEN_CHAT = "open_chat"
        const val EXTRA_OPEN_SHEET = "open_sheet"
        const val EXTRA_OPEN_HISTORY = "open_history"
        const val EXTRA_OPEN_SCENE_EDIT = "open_scene_edit"
        const val EXTRA_FORCE_CRASH = "force_crash"

        private const val DEMO_TAG = "AskFirstDemo"

        /** 同一个进程里 demo 只跑一次（Activity 重建不重复跑） */
        private var demoStarted = false
        private var demoShortStarted = false

        /** 仅用于 debug UI 验证的假 Key——不是真凭据，也永远不会出现在日志明文里 */
        private const val FAKE_KEY_FOR_UI_TEST = "sk-debug-only-1234"
    }
}
