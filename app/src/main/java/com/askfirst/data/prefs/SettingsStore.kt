package com.askfirst.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 设置存储（DataStore Preferences）。**不放任何密钥**。
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val flow: Flow<Settings> = dataStore.data.map { prefs -> prefs.toSettings() }

    suspend fun current(): Settings = flow.first()

    suspend fun saveConnection(baseUrl: String, model: String, hasApiKey: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl.trim().trimEnd('/')
            prefs[KEY_MODEL] = model.trim()
            prefs[KEY_HAS_API_KEY] = hasApiKey
        }
    }

    suspend fun saveAll(settings: Settings) {
        dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = settings.baseUrl.trim().trimEnd('/')
            prefs[KEY_MODEL] = settings.model.trim()
            prefs[KEY_HAS_API_KEY] = settings.hasApiKey
            prefs[KEY_ONBOARDING_DONE] = settings.onboardingDone
            prefs[KEY_TEMPERATURE] = settings.temperature
        }
    }

    suspend fun setOnboardingDone(done: Boolean = true) {
        dataStore.edit { it[KEY_ONBOARDING_DONE] = done }
    }

    /** M5：记住方案文档落盘的目录（SAF 授权字符串）。 */
    suspend fun setPlanExportDir(uri: String?) {
        dataStore.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(KEY_PLAN_EXPORT_DIR) else prefs[KEY_PLAN_EXPORT_DIR] = uri
        }
    }

    /** M6：GitHub 代理 base（逃生口）与「换词复核」开关。 */
    suspend fun saveResearch(githubProxyBase: String, recheck: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_GITHUB_PROXY] = githubProxyBase.trim().trimEnd('/')
            prefs[KEY_RESEARCH_RECHECK] = recheck
        }
    }

    private fun Preferences.toSettings(): Settings = Settings(
        baseUrl = this[KEY_BASE_URL] ?: Settings.DEFAULT_BASE_URL,
        model = this[KEY_MODEL] ?: Settings.DEFAULT_MODEL,
        hasApiKey = this[KEY_HAS_API_KEY] ?: false,
        onboardingDone = this[KEY_ONBOARDING_DONE] ?: false,
        temperature = this[KEY_TEMPERATURE] ?: Settings.DEFAULT_TEMPERATURE,
        planExportDir = this[KEY_PLAN_EXPORT_DIR],
        githubProxyBase = this[KEY_GITHUB_PROXY] ?: "",
        researchRecheck = this[KEY_RESEARCH_RECHECK] ?: true,
    )

    companion object {
        const val FILE_NAME = "settings"

        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_HAS_API_KEY = booleanPreferencesKey("has_api_key")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_TEMPERATURE = doublePreferencesKey("temperature")
        private val KEY_PLAN_EXPORT_DIR = stringPreferencesKey("plan_export_dir")
        private val KEY_GITHUB_PROXY = stringPreferencesKey("github_proxy_base")
        private val KEY_RESEARCH_RECHECK = booleanPreferencesKey("research_recheck")
    }
}
