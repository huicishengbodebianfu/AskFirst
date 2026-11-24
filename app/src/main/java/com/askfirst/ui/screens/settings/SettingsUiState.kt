package com.askfirst.ui.screens.settings

/**
 * 设置页状态。**永远不持有 Key 明文**——只有掩码后的后 4 位（模型的与 GitHub 的都一样）。
 */
data class SettingsUiState(
    val loaded: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    val keyInput: String = "",
    val maskedKey: String = "",
    val hasKey: Boolean = false,
    /** 现在用的是代码里的个人自用默认 Key（不是用户自己填的） */
    val usingBuiltInKey: Boolean = false,
    val busy: Boolean = false,
    val testing: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val testOk: Boolean? = null,
    val canSave: Boolean = false,
    val speedTesting: Boolean = false,
    val speedText: String? = null,
    // ---- 「拉取模型列表 / 换模型」----
    val loadingModels: Boolean = false,
    /** 非空 = 弹出选择框 */
    val models: List<String> = emptyList(),
    val modelsMessage: String? = null,
    val modelsMessageIsError: Boolean = false,
    // ---- M6：查资料（GitHub）----
    val patInput: String = "",
    val maskedPat: String = "",
    val hasPat: Boolean = false,
    /** 现在用的是代码里的个人自用默认 PAT */
    val usingBuiltInPat: Boolean = false,
    val testingGithub: Boolean = false,
    val githubTestText: String? = null,
    val githubTestOk: Boolean? = null,
    val githubProxy: String = "",
    val researchRecheck: Boolean = true,
    val researchMessage: String? = null,
    val researchMessageIsError: Boolean = false,
) {
    /** 只要地址 + Key 就能拉列表（model 可以还没选）。 */
    val canLoadModels: Boolean get() = baseUrl.isNotBlank() && hasKey && !busy && !loadingModels
}
