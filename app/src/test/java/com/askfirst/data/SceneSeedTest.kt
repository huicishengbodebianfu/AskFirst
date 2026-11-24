package com.askfirst.data

import com.askfirst.core.AppJson
import com.askfirst.data.db.seed.BuiltinSceneAssets
import com.askfirst.data.db.seed.QuestionSeed
import com.askfirst.data.db.seed.SceneSeed
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置场景资产校验（S1 验收：docs/05 §2 SceneSeedTest）。
 * 这些 JSON 是产品的出厂内容，写坏了用户第一屏就是空的。
 */
class SceneSeedTest {

    private fun loadAll(): List<SceneSeed> {
        // Gradle 单测的工作目录是模块目录（app/）
        val assetsDir = File("src/main/assets")
        assertTrue("assets 目录不存在: ${assetsDir.absolutePath}", assetsDir.isDirectory)
        return BuiltinSceneAssets.files.map { relative ->
            val file = File(assetsDir, relative.removePrefix("scenes/").let { "scenes/$it" })
            assertTrue("缺少场景资产: $file", file.isFile)
            AppJson.decodeFromString(SceneSeed.serializer(), file.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun `三套内置场景都存在且 schemaVersion 合法`() {
        val scenes = loadAll()
        assertEquals(3, scenes.size)
        assertEquals(listOf("scene_tech_arch", "scene_project", "scene_retro"), scenes.map { it.id })
        scenes.forEach { scene ->
            assertEquals("${scene.id} schemaVersion", 1, scene.schemaVersion)
            assertTrue("${scene.id} 名称不能为空", scene.name.isNotBlank())
            assertTrue("${scene.id} 关注点不能为空", scene.focus.isNotBlank())
            assertTrue("${scene.id} 必须带文档骨架（M5 要用）", scene.planSkeleton.isNotEmpty())
        }
    }

    @Test
    fun `附录 A 的三套场景题目全部在，题号连续且不重复`() {
        val scenes = loadAll()
        val expected = mapOf("scene_tech_arch" to 9, "scene_project" to 10, "scene_retro" to 9)
        // 附录 A 实际是 28 题（A1 9 + A2 10 + A3 9）；产品方案里"27 道"是笔误
        assertEquals(28, scenes.sumOf { it.questions.size })
        scenes.forEach { scene ->
            assertEquals("${scene.id} 题数", expected.getValue(scene.id), scene.questions.size)
            val orders = scene.questions.map { it.order }
            assertEquals("${scene.id} order 必须是 1..n 连续", (1..scene.questions.size).toList(), orders)
            assertEquals("${scene.id} 问题 id 不许重复", scene.questions.size, scene.questions.map { it.id }.toSet().size)
        }
        // 全局 id 唯一（跨场景也不许撞）
        val allIds = scenes.flatMap { it.questions }.map { it.id }
        assertEquals(allIds.size, allIds.toSet().size)
    }

    @Test
    fun `每道题都必须写 why，否则用户不知道你为什么问`() {
        val questions = loadAll().flatMap { it.questions }
        questions.forEach { q ->
            assertTrue("${q.id} 缺 text", q.text.isNotBlank())
            assertTrue("${q.id} 缺 why（方案 §3.1 要求显示）", q.why.isNotBlank())
        }
    }

    @Test
    fun `必问题覆盖方案里的固定题数，且每题 kind 合法`() {
        val scenes = loadAll()
        val required = scenes.associate { scene -> scene.id to scene.questions.count { it.required } }
        assertEquals(mapOf("scene_tech_arch" to 4, "scene_project" to 4, "scene_retro" to 3), required)
        scenes.flatMap { it.questions }.forEach { q ->
            assertTrue("${q.id} kind 只能是 open/choice，实际 ${q.kind}", q.kind in setOf("open", "choice"))
        }
    }

    @Test
    fun `条件轮 trigger 必须能编译成正则，且能命中预期词`() {
        val questions: List<QuestionSeed> = loadAll().flatMap { it.questions }
        val conditional = questions.filterNot { it.trigger.isNullOrBlank() }
        assertEquals("M1 只保留 4 条条件轮（docs/03 §5）", 4, conditional.size)

        val probes = mapOf(
            "proj_q9" to ("这个能不能收费" to "今天天气不错"),
            "proj_q8" to ("准备下周上线" to "内部先用着"),
            "tech_q7" to ("这是一次存量系统迁移" to "从零开始写"),
            "retro_q9" to ("下次我要注意" to "这次先这样"),
        )
        conditional.forEach { q ->
            val regex = Regex(q.trigger!!)
            val (hit, miss) = probes.getValue(q.id)
            assertTrue("${q.id} 应命中「$hit」", regex.containsMatchIn(hit))
            assertTrue("${q.id} 不该命中「$miss」", !regex.containsMatchIn(miss))
        }
    }
}