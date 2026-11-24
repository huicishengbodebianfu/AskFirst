package com.askfirst.domain.scene

import com.askfirst.core.AppResult
import com.askfirst.data.db.seed.SceneSeed
import com.askfirst.domain.model.Question
import com.askfirst.domain.model.QuestionKind
import com.askfirst.domain.model.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3 场景 JSON 编解码：与出厂场景文件同一格式、容错、校验、id 撞车。 */
class SceneJsonCodecTest {

    private val goodJson = """
        {
          "schemaVersion": 1,
          "id": "scene_move",
          "name": "搬家计划",
          "focus": "把一次搬家逼到能执行",
          "questions": [
            {"id": "m1", "order": 1, "text": "什么时候必须搬完？", "why": "时间一紧，所有决策都要让路", "required": true},
            {"id": "m2", "order": 2, "text": "哪些东西绝对不能丢？", "why": "丢了要重买的东西优先处理", "trigger": "贵|证件"}
          ]
        }
    """.trimIndent()

    private val scene = Scene(
        id = "scene_move",
        name = "搬家计划",
        focus = "把一次搬家逼到能执行",
        builtin = false,
        planSkeleton = listOf("背景", "目标"),
        questions = listOf(
            Question("m1", "scene_move", 1, "什么时候必须搬完？", "时间一紧，所有决策都要让路", true),
            Question("m2", "scene_move", 2, "哪些东西绝对不能丢？", "丢了要重买的东西优先处理", false, trigger = "贵|证件"),
        ),
    )

    // ---------- decode ----------

    @Test
    fun `好 JSON 解出全部字段`() {
        val result = SceneJsonCodec.decode(goodJson)
        assertTrue(result is AppResult.Ok)
        val seed = (result as AppResult.Ok).value
        assertEquals("scene_move", seed.id)
        assertEquals("搬家计划", seed.name)
        assertEquals(2, seed.questions.size)
        assertEquals("贵|证件", seed.questions[1].trigger)
        assertEquals(true, seed.questions[0].required)
    }

    @Test
    fun `未知字段被忽略`() {
        val extra = goodJson.replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"futureField\": 42,")
        assertTrue(SceneJsonCodec.decode(extra) is AppResult.Ok)
    }

    @Test
    fun `坏 JSON 给中文错误不抛异常`() {
        val result = SceneJsonCodec.decode("{ 这不是 JSON")
        assertTrue(result is AppResult.Err)
        val msg = (result as AppResult.Err).error.message.orEmpty()
        assertTrue(msg.contains("场景文件"))
    }

    @Test
    fun `缺 why 的题被校验拦下`() {
        val noWhy = goodJson.replace("\"why\": \"丢了要重买的东西优先处理\",", "")
        val result = SceneJsonCodec.decode(noWhy)
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error.message.orEmpty().contains("第 2 题"))
    }

    @Test
    fun `空 questions 被拦下`() {
        val empty = goodJson.replaceAfter("\"questions\": [", "] }")
        val result = SceneJsonCodec.decode(empty)
        // 这条断言原来写的是「是 Err 或者解出空列表都算过」，等于把 bug 当通过（docs/28 P2-15）
        assertTrue("空问题集必须被拦下，实际 $result", result is AppResult.Err)
        assertTrue(
            (result as AppResult.Err).error.message.orEmpty().contains("至少"),
        )
    }

    @Test
    fun `id 缺失给专门提示`() {
        val noId = goodJson.replace("\"id\": \"scene_move\",", "")
        val result = SceneJsonCodec.decode(noId)
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error.message.orEmpty().let { it.contains("id") || it.contains("标识") })
    }

    // ---------- encode ----------

    @Test
    fun `导出再导入一比一还原`() {
        val json = SceneJsonCodec.encode(scene)
        val decoded = SceneJsonCodec.decode(json)
        assertTrue(decoded is AppResult.Ok)
        val seed = (decoded as AppResult.Ok).value
        assertEquals(scene.id, seed.id)
        assertEquals(scene.name, seed.name)
        assertEquals(scene.planSkeleton, seed.planSkeleton)
        assertEquals(scene.questions.size, seed.questions.size)
        scene.questions.forEachIndexed { i, q ->
            assertEquals(q.text, seed.questions[i].text)
            assertEquals(q.why, seed.questions[i].why)
            assertEquals(q.required, seed.questions[i].required)
            assertEquals(q.trigger, seed.questions[i].trigger)
        }
    }

    @Test
    fun `题目顺序按列表重排 1 到 n`() {
        val scrambled = scene.copy(
            questions = scene.questions.mapIndexed { i, q -> q.copy(order = 9 - i) }.reversed(),
        )
        val seed = SceneJsonCodec.encode(scrambled).let {
            SceneJsonCodec.decode(it) as AppResult.Ok
        }.value
        assertEquals(listOf(1, 2), seed.questions.map { it.order })
    }

    @Test
    fun `导出正文含 schemaVersion 且可读缩进`() {
        val json = SceneJsonCodec.encode(scene)
        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains("\n"))
    }

    // ---------- id 撞车 ----------

    @Test
    fun `不撞车保留原 id`() {
        assertEquals("scene_move", SceneJsonCodec.resolveImportId("scene_move", setOf("other")) { "minted" })
    }

    @Test
    fun `撞车换新 id`() {
        val minted = SceneJsonCodec.resolveImportId("scene_move", setOf("scene_move")) { "scene_new123" }
        assertEquals("scene_new123", minted)
        assertNotEquals("scene_move", minted)
    }
}
