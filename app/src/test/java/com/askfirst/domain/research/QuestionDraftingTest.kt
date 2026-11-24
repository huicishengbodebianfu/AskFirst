package com.askfirst.domain.research

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.domain.model.Sheet
import com.askfirst.domain.model.SheetItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 立题：3~6 个问题、关键词必填、坏输入不许进流程、模型挂了还有规则兜底。 */
class QuestionDraftingTest {

    @Test
    fun `正常 JSON 解析出问题且编号从 1 开始`() {
        val raw = """
            {"questions":[
              {"text":"Room 里做中文全文检索有没有成熟方案","keywords":["Room FTS5","中文分词"],"why":"你提过本地搜索"},
              {"text":"Compose 里长列表怎么保持滚动位置","keywords":["LazyList state"],"why":"你的方案要重排列表"},
              {"text":"SAF 持久授权在国产 ROM 重启后还在不在","keywords":["SAF persistable uri"],"why":"你要落盘"}
            ]}
        """
        val result = QuestionDrafting.parse(raw)
        assertTrue(result is AppResult.Ok)
        val questions = (result as AppResult.Ok).value
        assertEquals(3, questions.size)
        assertEquals(listOf(1, 2, 3), questions.map { it.idx })
        assertEquals("Room FTS5", questions[0].keywords.first())
    }

    @Test
    fun `超过 6 条只留前 6 条`() {
        val items = (1..9).joinToString(",") {
            """{"text":"第 $it 个要查的技术问题有没有先例","keywords":["kw$it"],"why":"凑数"}"""
        }
        val result = QuestionDrafting.parse("""{"questions":[$items]}""")
        assertEquals(6, (result as AppResult.Ok).value.size)
    }

    @Test
    fun `重复问题只留第一条`() {
        val raw = """
            {"questions":[
              {"text":"Room 里做中文全文检索有没有成熟方案","keywords":["a"],"why":""},
              {"text":"Room 里做中文全文检索有没有成熟方案","keywords":["b"],"why":""},
              {"text":"另一个完全不同的问题要查一下先例","keywords":["c"],"why":""}
            ]}
        """
        val questions = (QuestionDrafting.parse(raw) as AppResult.Ok).value
        assertEquals(2, questions.size)
    }

    @Test
    fun `关键词为空时从问题文字里抽词`() {
        val raw = """{"questions":[{"text":"首屏缓存失效用 TTL 还是主动通知","keywords":[],"why":""}]}"""
        val questions = (QuestionDrafting.parse(raw) as AppResult.Ok).value
        assertTrue(questions.first().keywords.isNotEmpty())
    }

    @Test
    fun `内部术语与太短的文本被丢掉`() {
        val raw = """
            {"questions":[
              {"text":"这条会提到 evidence 这种内部词","keywords":["x"],"why":""},
              {"text":"短","keywords":["y"],"why":""},
              {"text":"一个正经的可检索问题在这里","keywords":["z"],"why":""}
            ]}
        """
        val questions = (QuestionDrafting.parse(raw) as AppResult.Ok).value
        assertEquals(1, questions.size)
        assertEquals("一个正经的可检索问题在这里", questions.first().text)
    }

    @Test
    fun `坏 JSON 与空 questions 都报错`() {
        assertTrue(QuestionDrafting.parse("""{"questions":""") is AppResult.Err)
        assertTrue(QuestionDrafting.parse("""{"items":[]}""") is AppResult.Err)
        val err = QuestionDrafting.parse("not json") as AppResult.Err
        assertTrue(err.error is AppError.BadJson)
    }

    @Test
    fun `模型出的题不够 3 个时用兜底补齐`() {
        val primary = listOf(ResearchFixtures.question(idx = 1))
        val fallback = listOf(
            ResearchFixtures.question(idx = 1, text = "「两周内上线」这块，公开项目里有没有现成做法", keywords = listOf("两周内上线")),
            ResearchFixtures.question(idx = 2, text = "「一个人力」这块，公开项目里有没有现成做法", keywords = listOf("一个人力")),
        )
        val merged = QuestionDrafting.merge(primary, fallback)
        assertTrue("至少要 3 个问题", merged.size >= 3)
        assertEquals(listOf(1, 2, 3), merged.map { it.idx })
    }

    @Test
    fun `没有模型时从五栏单里抽词兜底`() {
        val sheet = Sheet(
            id = "sh_1",
            sessionId = "s_1",
            summary = "首屏缓存",
            items = mapOf(
                2 to listOf(SheetItem("it1", "sh_1", 2, 1, "只有我一个人力，两周内要能用上", listOf("m1"), false)),
                5 to listOf(SheetItem("it2", "sh_1", 5, 1, "缓存上限怎么定还没想清楚", listOf("m2"), false)),
            ),
            createdAt = 0,
            updatedAt = 0,
        )
        val questions = QuestionDrafting.fallback("首屏 4 秒太慢，想加缓存", sheet)
        assertTrue(questions.isNotEmpty())
        assertTrue(questions.all { it.keywords.isNotEmpty() })
        assertTrue(questions.all { it.idx >= 1 })
    }

    @Test
    fun `一句话都没有时兜底返回空而不是编问题`() {
        assertTrue(QuestionDrafting.fallback("", null).isEmpty())
    }
}
