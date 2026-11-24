package com.askfirst.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 场景编辑草稿校验：中文人话 + 内部词黑名单。 */
class SceneDraftTest {

    private fun draft(
        name: String = "搬家计划",
        focus: String = "把一次搬家逼到能执行",
        questions: List<QuestionDraft> = listOf(
            QuestionDraft("什么时候必须搬完？", "时间一紧，所有决策都要让路", required = true),
            QuestionDraft("哪些东西绝对不能丢？", "丢了要重买的东西优先处理", trigger = "贵|证件"),
        ),
    ) = SceneDraft(name, focus, questions)

    @Test
    fun `合格草稿零问题`() {
        assertTrue(draft().validate().isEmpty())
    }

    @Test
    fun `名字留空给中文提示`() {
        val issues = draft(name = "  ").validate()
        assertTrue(issues.isNotEmpty())
        assertTrue(issues.first().contains("名字"))
    }

    @Test
    fun `定位留空给中文提示`() {
        val issues = draft(focus = "").validate()
        assertTrue(issues.any { it.contains("一句话") })
    }

    @Test
    fun `零题不给保存`() {
        val issues = draft(questions = emptyList()).validate()
        assertTrue(issues.any { it.contains("至少") })
    }

    @Test
    fun `题目空给题号`() {
        val questions = listOf(
            QuestionDraft("", "为什么"),
            QuestionDraft("题目在", "为什么在"),
        )
        val issues = draft(questions = questions).validate()
        assertTrue(issues.any { it.contains("第 1 题") && it.contains("题目") })
    }

    @Test
    fun `为什么问为空给题号`() {
        val questions = listOf(QuestionDraft("题目在", ""))
        val issues = draft(questions = questions).validate()
        assertTrue(issues.any { it.contains("第 1 题") && it.contains("为什么问") })
    }

    @Test
    fun `坏 trigger 被拦下且带原文`() {
        val questions = listOf(QuestionDraft("题目在", "为什么在", trigger = "(未闭合"))
        val issues = draft(questions = questions).validate()
        assertTrue(issues.any { it.contains("第 1 题") && it.contains("(未闭合") })
    }

    @Test
    fun `好 trigger 放行`() {
        val questions = listOf(QuestionDraft("题目在", "为什么在", trigger = "迁移|重构"))
        assertTrue(draft(questions = questions).validate().isEmpty())
    }

    @Test
    fun `题数超上限拦截`() {
        val questions = (1..31).map { QuestionDraft("题$it", "为什么$it") }
        val issues = draft(questions = questions).validate()
        assertTrue(issues.any { it.contains("太多") })
    }

    @Test
    fun `校验文案零内部词`() {
        val issues = draft(
            name = "",
            focus = "",
            questions = listOf(QuestionDraft("", "", trigger = "[")),
        ).validate()
        val banned = listOf("schema", "trigger", "JSON", "validate", "draft")
        banned.forEach { word ->
            assertTrue("文案里不该出现 $word", issues.none { it.contains(word, ignoreCase = true) })
        }
    }

    @Test
    fun `场景转草稿逐字段拷贝`() {
        val scene = Scene(
            id = "scene_x",
            name = "复盘",
            focus = "把一件事的得失问清楚",
            builtin = false,
            questions = listOf(
                Question("q1", "scene_x", 1, "当时目标是什么？", "没有目标就没有偏差可言", true),
                Question("q2", "scene_x", 2, "哪一步走对了？", "走对的部分比走错的部分更少被复盘", false, trigger = "上线|发布"),
            ),
        )
        val d = scene.toDraft()
        assertEquals(scene.name, d.name)
        assertEquals(scene.focus, d.focus)
        assertEquals(2, d.questions.size)
        assertEquals("哪一步走对了？", d.questions[1].text)
        assertEquals("上线|发布", d.questions[1].trigger)
    }
}
