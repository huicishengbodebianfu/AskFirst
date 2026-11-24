package com.askfirst.local

import com.askfirst.BuildConfig

/**
 * 个人自用的默认配置（**源码里没有任何凭据**）。
 *
 * 值来自 `自用/personal.properties`（已加入 `.gitignore`），由 `app/build.gradle.kts`
 * 在编译期注入 `BuildConfig`。开源仓库里没有那个文件 → 这里全是空串 →
 * App 会像普通用户一样，要求你在设置页填 base_url / api_key / model。
 *
 * 优先级：**用户在设置页填过的 > 这里的值**。
 * 想彻底关掉这层：把 [ENABLED] 改成 false（或者干脆不建那个 properties 文件）。
 */
object PersonalDefaults {

    /** 总开关：false = 下面四项全部当作空。 */
    const val ENABLED: Boolean = true

    val baseUrl: String get() = if (ENABLED) BuildConfig.PERSONAL_BASE_URL else ""
    val model: String get() = if (ENABLED) BuildConfig.PERSONAL_MODEL else ""
    val apiKey: String get() = if (ENABLED) BuildConfig.PERSONAL_API_KEY else ""
    val githubPat: String get() = if (ENABLED) BuildConfig.PERSONAL_GITHUB_PAT else ""

    /** 内置默认值是否在用（设置页要如实说一句，别让人以为是自己填的）。 */
    fun hasBuiltInApiKey(): Boolean = apiKey.isNotBlank()
    fun hasBuiltInGithubPat(): Boolean = githubPat.isNotBlank()
}
