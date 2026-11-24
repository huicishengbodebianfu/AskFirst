package com.askfirst.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.askfirst.core.AppResult
import com.askfirst.core.NoopLogx
import com.askfirst.data.prefs.SettingsStore
import com.askfirst.data.secret.SecretStore
import com.askfirst.remote.HttpFactory
import com.askfirst.remote.OpenAiCompatibleProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S2 验收（真机）：设置持久化、密钥加密存储与掩码、模型地址连通失败的错误映射。
 * 这些东西在 JVM 上测不了（Keystore / DataStore 都依赖 Android）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsSecretAndroidTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 每个用例前清干净，避免互相污染
        File(context.filesDir.parentFile, "shared_prefs/test_secret.xml").delete()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newSettingsStore(): SettingsStore {
        val file = File(context.cacheDir, "test_settings_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return SettingsStore(dataStore)
    }

    @Test
    fun 设置能存能读_默认值正确() = runBlocking {
        val store = newSettingsStore()
        val defaults = store.current()
        assertEquals("https://api.deepseek.com/v1", defaults.baseUrl)
        assertEquals("deepseek-chat", defaults.model)
        assertFalse(defaults.hasApiKey)

        store.saveConnection("https://gw.example.com/v1/", "qwen-max", hasApiKey = true)
        val saved = store.current()
        assertEquals("https://gw.example.com/v1", saved.baseUrl) // 尾部斜杠被吃掉
        assertEquals("qwen-max", saved.model)
        assertTrue(saved.hasApiKey)
        assertTrue(saved.connectionReady)
    }

    @Test
    fun 密钥只显示后四位_明文不落普通存储() = runBlocking {
        val secrets = SecretStore(context, NoopLogx, fileName = "test_secret")
        secrets.clearAll()
        assertNull(secrets.llmKey())

        val raw = "sk-test-abcdef1234"
        secrets.putLlmKey(raw)
        assertEquals(raw, secrets.llmKey())
        assertEquals("••••1234", secrets.maskedLlmKey())
        assertTrue(secrets.hasLlmKey())

        // 加密存储文件里不许出现明文（这就是"不进明文 SP"的机器证据）
        val prefsFile = File(context.filesDir.parentFile, "shared_prefs/test_secret.xml")
        assertTrue("加密存储文件应存在: ${prefsFile.absolutePath}", prefsFile.isFile)
        val content = prefsFile.readText()
        assertFalse("明文 Key 泄露到 shared_prefs", content.contains(raw))
        assertTrue("应该是 v2 加密条目", content.contains("__androidx_security_crypto_encrypted_prefs_key_keyset__"))

        secrets.clearLlmKey()
        assertNull(secrets.llmKey())
        assertEquals("", secrets.maskedLlmKey())
    }

    @Test
    fun 密钥缺失时配置校验给出可读错误() = runBlocking {
        val settings = newSettingsStore()
        val secrets = SecretStore(context, NoopLogx, fileName = "test_secret")
        secrets.clearAll()
        val provider = OpenAiCompatibleProvider(HttpFactory.client(), settings, secrets, NoopLogx)

        val result = provider.config()
        assertTrue(result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertTrue("应提示缺 Key: ${error.message}", error.message!!.contains("API Key"))
    }

    @Test
    fun 连不上的地址映射成网络错误而不是崩溃() = runBlocking {
        val settings = newSettingsStore()
        val secrets = SecretStore(context, NoopLogx, fileName = "test_secret")
        secrets.clearAll()
        secrets.putLlmKey("sk-fake")
        // 端口 9 在本机没人监听 → 立刻 refuse，避免依赖外网可达性
        settings.saveConnection("http://127.0.0.1:9/v1", "test-model", hasApiKey = true)

        val provider = OpenAiCompatibleProvider(HttpFactory.client(), settings, secrets, NoopLogx)
        val result = provider.ping()
        assertTrue("ping 应失败，实际 $result", result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertNotNull(error.message)
        assertTrue(
            "错误应可读（网络/超时），实际 ${error::class.simpleName}: ${error.message}",
            error is com.askfirst.core.AppError.Network || error is com.askfirst.core.AppError.Timeout,
        )
    }
}