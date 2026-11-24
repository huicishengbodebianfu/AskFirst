package com.askfirst.domain.model

/**
 * 场景与问题（对应 scene / question 两张表）。
 */
data class Scene(
    val id: String,
    val name: String,
    val focus: String,
    val builtin: Boolean,
    /** M5 用，M1 可为空 */
    val planSkeleton: List<String> = emptyList(),
    val questions: List<Question> = emptyList(),
)

data class Question(
    val id: String,
    val sceneId: String,
    val order: Int,
    val text: String,
    val why: String,
    val required: Boolean,
    val kind: QuestionKind = QuestionKind.OPEN,
    /** 条件轮：命中才问，null = 无条件（改动 #12） */
    val trigger: String? = null,
) {
    /** trigger 是正则片段；写坏了不让整个场景挂掉，只当作无条件题。 */
    val triggerRegex: Regex? by lazy {
        trigger?.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }
    }

    fun matchesTrigger(text: String): Boolean {
        val regex = triggerRegex ?: return true
        return regex.containsMatchIn(text)
    }
}

/** 场景摘要（首页列表用，避免把整包问题带进 UI）。 */
data class SceneSummary(
    val id: String,
    val name: String,
    val focus: String,
    val builtin: Boolean,
    val questionCount: Int,
    val requiredCount: Int,
)