package com.askfirst.data.repo

import com.askfirst.data.db.entity.QuestionEntity

/**
 * 保存自建场景时，决定每道题沿用旧 id 还是发新 id。
 *
 * 为什么值得单独一个纯函数：**"哪些题答过了"是按问题 id 记在 `message.coveredJson` 里的**
 * （`StateMachine.requiredAnswered`、`PromptBuilder.selectQuestions` 都在比 id）。
 * 原来每次保存都全部换新 id，等于把已有会话的已答记录一次性作废——
 * 引擎重问已答过的题、状态退回 `GATHERING`、READY 不可达（docs/28 P1-3）。
 *
 * 判据：位置 + 题干（文字/为什么问/必问/条件词）都没变 = 还是那道题，沿用旧 id；
 * 只要有一项变了，就是"换了一个问题"，必须发新 id（不然已答记录会张冠李戴）。
 */
internal object QuestionIdReuse {

    /**
     * @param order 该题在草稿里的位置（1 开始）
     * @param existing 保存前库里这套场景的问题
     * @param mint 需要发新 id 时调用
     */
    fun idOf(
        order: Int,
        text: String,
        why: String,
        required: Boolean,
        trigger: String?,
        existing: List<QuestionEntity>,
        mint: () -> String,
    ): String {
        val triggerOrNull = trigger?.takeIf { it.isNotBlank() }
        val reused = existing.firstOrNull { old ->
            old.order == order &&
                old.text == text &&
                old.why == why &&
                old.required == required &&
                old.trigger == triggerOrNull
        }
        return reused?.id ?: mint()
    }
}
