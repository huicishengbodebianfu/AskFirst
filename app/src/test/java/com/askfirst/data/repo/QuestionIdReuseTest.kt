package com.askfirst.data.repo

import com.askfirst.data.db.entity.QuestionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 保存自建场景时的问题 id 复用（docs/28 P1-3）。
 * 判据错一个分支就会出现"答过的题变没答过"，或者"答过的记录张冠李戴"。
 */
class QuestionIdReuseTest {

    private fun old(
        id: String,
        order: Int,
        text: String,
        why: String = "因为要问清楚",
        required: Boolean = false,
        trigger: String? = null,
    ) = QuestionEntity(
        id = id,
        sceneId = "scene_1",
        order = order,
        text = text,
        why = why,
        required = required,
        trigger = trigger,
        createdAt = 1L,
    )

    private val existing = listOf(
        old("q_1", 1, "这次要解决什么问题？", required = true),
        old("q_2", 2, "边界条件哪条是硬的？"),
        old("q_3", 3, "有没有人踩过这个坑？", trigger = "迁移|重构"),
    )

    private var minted = 0
    private fun mint(): String = "new_${++minted}"

    @Test
    fun `一道题都没改 全部沿用旧 id`() {
        existing.forEachIndexed { index, q ->
            assertEquals(
                q.id,
                QuestionIdReuse.idOf(q.order, q.text, q.why, q.required, q.trigger, existing, ::mint),
            )
        }
        assertEquals("不该发新 id", 0, minted)
    }

    @Test
    fun `改了一个字的题发新 id 其余不动`() {
        val ids = listOf(
            QuestionIdReuse.idOf(1, "这次要解决什么问题？", "因为要问清楚", true, null, existing, ::mint),
            QuestionIdReuse.idOf(2, "边界条件哪条是最硬的？", "因为要问清楚", false, null, existing, ::mint),
            QuestionIdReuse.idOf(3, "有没有人踩过这个坑？", "因为要问清楚", false, "迁移|重构", existing, ::mint),
        )
        assertEquals("q_1", ids[0])
        assertNotEquals("题干变了必须换 id", "q_2", ids[1])
        assertEquals("q_3", ids[2])
    }

    @Test
    fun `必问开关和条件词变了也算换了问题`() {
        assertNotEquals(
            "q_2",
            QuestionIdReuse.idOf(2, "边界条件哪条是硬的？", "因为要问清楚", true, null, existing, ::mint),
        )
        assertNotEquals(
            "q_3",
            QuestionIdReuse.idOf(3, "有没有人踩过这个坑？", "因为要问清楚", false, "重构", existing, ::mint),
        )
    }

    @Test
    fun `位置变了不复用 免得答过的记录张冠李戴`() {
        val ids = listOf(
            QuestionIdReuse.idOf(1, "边界条件哪条是硬的？", "因为要问清楚", false, null, existing, ::mint),
            QuestionIdReuse.idOf(2, "这次要解决什么问题？", "因为要问清楚", true, null, existing, ::mint),
        )
        assertEquals(listOf("new_1", "new_2"), ids)
    }

    @Test
    fun `新增的题发新 id 老题保持`() {
        val ids = listOf(
            QuestionIdReuse.idOf(1, "这次要解决什么问题？", "因为要问清楚", true, null, existing, ::mint),
            QuestionIdReuse.idOf(2, "边界条件哪条是硬的？", "因为要问清楚", false, null, existing, ::mint),
            QuestionIdReuse.idOf(3, "有没有人踩过这个坑？", "因为要问清楚", false, "迁移|重构", existing, ::mint),
            QuestionIdReuse.idOf(4, "退路是什么？", "没退路不敢开工", false, null, existing, ::mint),
        )
        assertEquals(listOf("q_1", "q_2", "q_3", "new_1"), ids)
    }

    @Test
    fun `空场景第一次保存 全部发新 id`() {
        val ids = listOf(
            QuestionIdReuse.idOf(1, "这次要解决什么问题？", "因为要问清楚", true, null, emptyList(), ::mint),
            QuestionIdReuse.idOf(2, "边界条件哪条是硬的？", "因为要问清楚", false, null, emptyList(), ::mint),
        )
        assertEquals(listOf("new_1", "new_2"), ids)
    }

    @Test
    fun `条件词的空串与 null 等价 不该白换一次 id`() {
        assertEquals(
            "q_2",
            QuestionIdReuse.idOf(2, "边界条件哪条是硬的？", "因为要问清楚", false, "  ", existing, ::mint),
        )
    }
}
