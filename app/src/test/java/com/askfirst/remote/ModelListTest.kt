package com.askfirst.remote

import com.askfirst.core.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/v1/models` 的解析（设置页「拉取模型列表」）。
 * 各家网关的返回形状不统一，这里把见过的四种都钉住；认不出来必须报错，不许静默给空列表。
 */
class ModelListTest {

    @Test
    fun `OpenAI 标准的 data 数组`() {
        val raw = """{"object":"list","data":[{"id":"gpt-4o","object":"model"},{"id":"gpt-4o-mini"}]}"""
        val parsed = (ModelList.parse(raw) as AppResult.Ok).value
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), parsed)
    }

    @Test
    fun `少数网关用 models 包对象`() {
        val raw = """{"models":[{"id":"auto/deepseek-v4.1-flash"},{"id":"auto/glm-5.3"}]}"""
        val parsed = (ModelList.parse(raw) as AppResult.Ok).value
        assertEquals(listOf("auto/deepseek-v4.1-flash", "auto/glm-5.3"), parsed)
    }

    @Test
    fun `更简单的网关直接给字符串数组`() {
        assertEquals(
            listOf("a", "b"),
            (ModelList.parse("""{"models":["b","a"]}""") as AppResult.Ok).value,
        )
        assertEquals(
            listOf("a", "b"),
            (ModelList.parse("""["b","a"]""") as AppResult.Ok).value,
        )
    }

    @Test
    fun `对象里没有 id 时退到 name`() {
        val raw = """{"data":[{"name":"模型甲"},{"id":"model-b"}]}"""
        val parsed = (ModelList.parse(raw) as AppResult.Ok).value
        assertEquals(listOf("model-b", "模型甲"), parsed)
    }

    @Test
    fun `去重去空并排序`() {
        val raw = """{"data":[{"id":"b"},{"id":"a"},{"id":"b"},{"id":"  "}]}"""
        assertEquals(listOf("a", "b"), (ModelList.parse(raw) as AppResult.Ok).value)
    }

    @Test
    fun `认不出来的返回体要报错 不许静默给空列表`() {
        // 静默返回空列表会让人以为"这网关上没有模型"，而不是"这个返回我不认识"
        val noName = ModelList.parse("""{"object":"list","data":[{"foo":"bar"}]}""")
        assertTrue(noName is AppResult.Err)
        assertTrue((noName as AppResult.Err).error.message!!.contains("找不到模型名"))

        assertTrue(ModelList.parse("not json") is AppResult.Err)
        assertTrue(ModelList.parse("") is AppResult.Err)
        assertTrue(ModelList.parse("{}") is AppResult.Err)
    }
}
