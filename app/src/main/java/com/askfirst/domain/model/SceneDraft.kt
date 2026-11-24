package com.askfirst.domain.model

/**
 * M3 场景编辑草稿：UI 编辑器里的中间态，保存前用 [validate] 把关。
 * 与 [Scene] 的区别：没有 id/order/builtin（落库时由仓库分配），
 * 问题顺序就是列表顺序。
 */
data class QuestionDraft(
    val text: String,
    val why: String,
    val required: Boolean = false,
    /** 条件题：开题句里命中这个词才问；空 = 每次都问。写坏的正则保存时拦下。 */
    val trigger: String? = null,
)

data class SceneDraft(
    val name: String,
    val focus: String,
    val questions: List<QuestionDraft> = emptyList(),
) {
    companion object {
        /** 题数上限：跟内置场景一个量级，防把提示词撑爆。 */
        const val MAX_QUESTIONS = 30
        const val MAX_NAME_LEN = 30
        const val MAX_FOCUS_LEN = 60
    }

    /**
     * 逐条返回**给用户看的**中文问题（不是异常）；空列表 = 可以保存。
     * 校验规则与内置场景 JSON 同一标准（docs/03 §5）。
     */
    fun validate(): List<String> {
        val issues = mutableListOf<String>()
        when {
            name.isBlank() -> issues += "先给这套场景起个名字"
            name.length > MAX_NAME_LEN -> issues += "名字太长了（最多 $MAX_NAME_LEN 个字）"
        }
        when {
            focus.isBlank() -> issues += "写一句话说明这套场景管什么用（例：把一个模糊的想法逼到能开工）"
            focus.length > MAX_FOCUS_LEN -> issues += "定位太长了（最多 $MAX_FOCUS_LEN 个字）"
        }
        if (questions.isEmpty()) {
            issues += "至少要有一道问题，不然它没得问"
        }
        if (questions.size > MAX_QUESTIONS) {
            issues += "问题太多（最多 $MAX_QUESTIONS 道），会把它撑糊涂的"
        }
        questions.forEachIndexed { index, q ->
            val no = index + 1
            if (q.text.isBlank()) issues += "第 $no 题的题目是空的"
            if (q.why.isBlank()) issues += "第 $no 题还没写「为什么问这个」——不写的话它问得没有底气"
            val trigger = q.trigger
            if (!trigger.isNullOrBlank() && runCatching { Regex(trigger) }.isFailure) {
                issues += "第 $no 题的「问到这个词才出现」写错了：$trigger（照抄例：迁移|重构）"
            }
        }
        return issues
    }
}

/** 场景 → 编辑草稿（进编辑器时拷贝一份，改坏了不影响库里那份）。 */
fun Scene.toDraft(): SceneDraft = SceneDraft(
    name = name,
    focus = focus,
    questions = questions.map { q ->
        QuestionDraft(
            text = q.text,
            why = q.why,
            required = q.required,
            trigger = q.trigger,
        )
    },
)
