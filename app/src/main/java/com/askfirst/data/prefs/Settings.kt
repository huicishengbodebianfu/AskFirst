package com.askfirst.data.prefs

/**
 * 普通设置项（**不含密钥**）。密钥只走 SecretStore（Keystore + EncryptedSharedPreferences）。
 *
 * 这里存 baseUrl / model 是明文——它们不是凭据，且用户需要随时看见自己填的是哪个地址。
 */
data class Settings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val hasApiKey: Boolean = false,
    val onboardingDone: Boolean = false,
    val temperature: Double = DEFAULT_TEMPERATURE,
    /** M5：上次给方案文档选的文件夹（SAF tree uri，字符串存）。空 = 还没选过。 */
    val planExportDir: String? = null,
    /**
     * M6：GitHub 代理/镜像 base（不含凭据，明文）。空 = 直连 api.github.com。
     * 这是「直连超时」的逃生口（方案 §3.6.2 第 3 条）。
     */
    val githubProxyBase: String = "",
    /**
     * M6：GitHub 内没命中时，允不允许换词再复核一次。
     * **关掉之后只能说「未取证」，不许说「无人区」**（方案 §八 最有害的那条风险）。
     */
    val researchRecheck: Boolean = true,
) {
    val connectionReady: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && hasApiKey

    companion object {
        /** 出厂兜底：只有在个人自用配置被关掉（`PersonalDefaults.ENABLED = false`）时才会用到。 */
        private const val FALLBACK_BASE_URL = "https://api.deepseek.com/v1"
        private const val FALLBACK_MODEL = "deepseek-chat"

        /**
         * 默认 base_url / model：**先看个人自用配置**（`自用/` + `PersonalDefaults`），
         * 空的才退回出厂兜底。
         */
        val DEFAULT_BASE_URL: String
            get() = com.askfirst.local.PersonalDefaults.baseUrl.ifBlank { FALLBACK_BASE_URL }

        val DEFAULT_MODEL: String
            get() = com.askfirst.local.PersonalDefaults.model.ifBlank { FALLBACK_MODEL }

        const val DEFAULT_TEMPERATURE = 0.6
    }
}