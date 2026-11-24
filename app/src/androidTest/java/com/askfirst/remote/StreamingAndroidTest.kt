package com.askfirst.remote

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.askfirst.core.AppError
import com.askfirst.core.NoopLogx
import com.askfirst.data.prefs.SettingsStore
import com.askfirst.data.secret.SecretStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S3 验收（真机）：SSE 流式 end-to-end，不依赖真实 API Key。
 *
 * 前置（宿主机执行）：
 *   python tools/fake_sse_server.py        # 8080 正常流 / 8081 429
 *   adb reverse tcp:8080 tcp:8080
 *   adb reverse tcp:8081 tcp:8081
 * 设备侧走 127.0.0.1（debug 构建的 network_security_config 放行了回环明文）。
 */
@RunWith(AndroidJUnit4::class)
class StreamingAndroidTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var secrets: SecretStore

    private val expectedText = "我先问一个关键问题：你说的「慢」具体是打开就要四秒，还是滚动的时候卡？"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        secrets = SecretStore(context, NoopLogx, fileName = "test_stream_secret")
        secrets.clearAll()
        secrets.putLlmKey("sk-fake-for-stream-test")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun settingsFor(baseUrl: String): SettingsStore {
        val file = File(context.cacheDir, "stream_settings_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return SettingsStore(dataStore).also { store ->
            runBlocking { store.saveConnection(baseUrl, "fake-model", hasApiKey = true) }
        }
    }

    private fun provider(baseUrl: String): LlmProvider =
        OpenAiCompatibleProvider(HttpFactory.client(), settingsFor(baseUrl), secrets, NoopLogx)

    @Test
    fun 流式逐段吐字_首字两秒内_末帧收尾() = runBlocking {
        val events = mutableListOf<LlmEvent>()
        var firstDeltaMs = -1L
        val startedAt = System.nanoTime()

        withTimeout(40_000) {
            provider("http://127.0.0.1:8080/v1")
                .stream(ChatRequest(messages = listOf(ChatMessage.user("hi")), model = "fake-model", stream = true))
                .collect { event ->
                    if (event is LlmEvent.Delta && firstDeltaMs < 0) {
                        firstDeltaMs = (System.nanoTime() - startedAt) / 1_000_000
                    }
                    events.add(event)
                }
        }

        val deltas = events.filterIsInstance<LlmEvent.Delta>()
        val done = events.filterIsInstance<LlmEvent.Done>().single()
        val failures = events.filterIsInstance<LlmEvent.Failed>()

        assertTrue("不该有失败: $failures", failures.isEmpty())
        assertTrue("至少要收到 5 段增量，实际 ${deltas.size}", deltas.size >= 5)
        assertEquals("拼接后的全文要与服务端一致", expectedText, deltas.joinToString("") { it.text })
        assertEquals("Done 里的全文也要一致", expectedText, done.fullText)
        assertEquals("stop", done.finishReason)
        assertTrue("首字延迟应 < 2000ms，实际 ${firstDeltaMs}ms", firstDeltaMs in 0..2000)
    }

    @Test
    fun 限流映射成可读错误而不是裸异常() = runBlocking {
        val events = withTimeout(25_000) {
            provider("http://127.0.0.1:8081/v1")
                .stream(ChatRequest(messages = listOf(ChatMessage.user("hi")), model = "fake-model", stream = true))
                .toList()
        }
        val failure = events.filterIsInstance<LlmEvent.Failed>().single()
        assertTrue("429 应映射成 RateLimited，实际 ${failure.error::class.simpleName}", failure.error is AppError.RateLimited)
        assertTrue("错误文案要能直接给用户看：${failure.error.message}", !failure.error.message.isNullOrBlank())
        assertTrue(events.none { it is LlmEvent.Done })
    }

    @Test
    fun 端口没人监听时是网络错误() = runBlocking {
        val events = withTimeout(25_000) {
            provider("http://127.0.0.1:8099/v1")
                .stream(ChatRequest(messages = listOf(ChatMessage.user("hi")), model = "fake-model", stream = true))
                .toList()
        }
        val failure = events.filterIsInstance<LlmEvent.Failed>().single()
        assertTrue(
            "拒连应映射成 Network，实际 ${failure.error::class.simpleName}",
            failure.error is AppError.Network,
        )
    }
}