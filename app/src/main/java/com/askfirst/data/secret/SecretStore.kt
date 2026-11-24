package com.askfirst.data.secret

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.askfirst.core.Logx
import com.askfirst.core.Text
import com.askfirst.local.PersonalDefaults

/**
 * 密钥存储（方案 §五、§3.6.2）：
 * - 主密钥由 Android Keystore 持有，加密文件是 EncryptedSharedPreferences
 * - 明文只在这一层出现；UI 只拿 [maskedLlmKey]，日志/导出永远拿不到原文
 * - 清除要"连带清内存缓存"：这里没有缓存，读一次解一次，天然满足
 */
class SecretStore(
    context: Context,
    private val log: Logx,
    private val fileName: String = FILE_NAME,
) {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { createPrefs() }

    private fun createPrefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        // Keystore 被重置 / 文件损坏：删掉重建，比让 App 起不来强
        log.e(TAG, "创建加密存储失败，清掉重建", t)
        appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear().commit()
        appContext.deleteSharedPreferences(fileName)
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun putLlmKey(raw: String) {
        prefs.edit().putString(KEY_LLM, raw.trim()).apply()
        log.d(TAG, "LLM key 已保存（${Text.maskToken(raw)}）")
    }

    /**
     * 预热：第一次碰 [prefs] 要建 MasterKey（Keystore 调用）+ 读加密文件 + 解密全部值，
     * 这是一个实打实的磁盘 IO + 加密运算。别让它发生在主线程的第一次 get 上（docs/28 P2-13）。
     * `AppContainer` 在 IO 线程上调它。
     */
    fun warmUp() {
        runCatching { prefs }.onFailure { log.e(TAG, "预热加密存储失败", it) }
    }

    /**
     * LLM key 的有效值：**用户填的 > 代码里的个人自用默认值**。
     *
     * 注意 [CLEARED] 这个哨兵：用户点过「清除 Key」之后不许又悄悄回退到内置默认值，
     * 否则那个按钮就成了摆设（清了还在用，等于没清）。
     */
    fun llmKey(): String? {
        val stored = prefs.getString(KEY_LLM, null)
        return when {
            stored == CLEARED -> null
            !stored.isNullOrBlank() -> stored
            else -> PersonalDefaults.apiKey.takeIf { it.isNotBlank() }
        }
    }

    fun clearLlmKey() {
        prefs.edit().putString(KEY_LLM, CLEARED).apply()
        log.d(TAG, "LLM key 已清除")
    }

    /** 只给 UI 看后 4 位 */
    fun maskedLlmKey(): String = Text.maskToken(llmKey())

    fun hasLlmKey(): Boolean = llmKey() != null

    /** 现在用的是代码里的内置默认 Key（不是用户自己填的）→ 设置页要说一句。 */
    fun usingBuiltInLlmKey(): Boolean =
        prefs.getString(KEY_LLM, null).isNullOrBlank() && PersonalDefaults.hasBuiltInApiKey()

    fun putGithubPat(raw: String) {
        prefs.edit().putString(KEY_GITHUB, raw.trim()).apply()
    }

    /** GitHub PAT 的有效值：用户填的 > 代码里的个人自用默认值（同 [llmKey] 的规则）。 */
    fun githubPat(): String? {
        val stored = prefs.getString(KEY_GITHUB, null)
        return when {
            stored == CLEARED -> null
            !stored.isNullOrBlank() -> stored
            else -> PersonalDefaults.githubPat.takeIf { it.isNotBlank() }
        }
    }

    fun clearGithubPat() {
        prefs.edit().putString(KEY_GITHUB, CLEARED).apply()
    }

    /** 现在用的是代码里的内置默认 PAT → 设置页要说一句。 */
    fun usingBuiltInGithubPat(): Boolean =
        prefs.getString(KEY_GITHUB, null).isNullOrBlank() && PersonalDefaults.hasBuiltInGithubPat()

    fun clearAll() {
        prefs.edit().clear().apply()
        log.d(TAG, "所有密钥已清除")
    }

    companion object {
        const val FILE_NAME = "askfirst_secret"
        private const val TAG = "SecretStore"
        private const val KEY_LLM = "llm_api_key"
        private const val KEY_GITHUB = "github_pat"

        /**
         * 「用户明确清空过」的哨兵值。
         * 没有它就分不清"没填过"（该用内置默认）和"清掉了"（不许再回退），
         * 「清除 Key」按钮会变成摆设。
         */
        private const val CLEARED = "\u0000cleared"
    }
}