package com.askfirst.domain.research

import com.askfirst.core.AppResult
import com.askfirst.domain.model.Judgement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对照输出 → 判定：白名单过滤、结构容错、什么算「一条能用的判定」。 */
class VerdictParseTest {

    private val questions = listOf(ResearchFixtures.question(idx = 1))
    private val evidence = listOf(
        ResearchFixtures.repo(),
        ResearchFixtures.issue(),
    )

    @Test
    fun `四种判定都能解析出来`() {
        val raw = """
            {"verdicts":[
              {"question":1,"judgement":"common","why":"至少三个项目在这么做","evidence":["ev_q1_repo_1"]},
              {"question":1,"judgement":"known_pit","why":"两个项目在同一处摔过","evidence":["ev_q1_issue_1"]},
              {"question":1,"judgement":"antipattern","why":"有项目明确放弃这个做法",
               "quote":"moved to per-record rows","quote_url":"https://github.com/owner/cache-lib/issues/217",
               "evidence":["ev_q1_issue_1"]},
              {"question":1,"judgement":"unexplored","why":"换词也没找到","evidence":[]}
            ]}
        """
        val verdicts = (VerdictParse.parse(raw, questions, evidence) as AppResult.Ok).value
        assertEquals(4, verdicts.size)
        assertEquals(
            listOf(Judgement.COMMON, Judgement.KNOWN_PIT, Judgement.ANTIPATTERN, Judgement.UNEXPLORED),
            verdicts.map { it.judgement },
        )
        // id 唯一（同问题多条判定不能撞 id，页面要按 id 切落点）
        assertEquals(4, verdicts.map { it.id }.toSet().size)
        assertEquals("moved to per-record rows", verdicts[2].quotedText)
    }

    @Test
    fun `编造的证据 id 被过滤掉`() {
        val raw = """
            {"verdicts":[
              {"question":1,"judgement":"common","why":"看起来很常见",
               "evidence":["ev_q1_repo_1","ev_q9_repo_99","made-up-id"]}
            ]}
        """
        val verdicts = (VerdictParse.parse(raw, questions, evidence) as AppResult.Ok).value
        assertEquals(listOf("ev_q1_repo_1"), verdicts.first().evidenceIds)
    }

    @Test
    fun `不认识的判定与对不上的问题号都丢掉`() {
        val raw = """
            {"verdicts":[
              {"question":1,"judgement":"我觉得挺好","why":"x"},
              {"question":7,"judgement":"common","why":"x"},
              {"question":1,"judgement":"known_pit","why":"两个项目踩过同一个坑"}
            ]}
        """
        val verdicts = (VerdictParse.parse(raw, questions, evidence) as AppResult.Ok).value
        assertEquals(1, verdicts.size)
        assertEquals(Judgement.KNOWN_PIT, verdicts.first().judgement)
    }

    @Test
    fun `没写为什么的判定丢掉`() {
        val raw = """{"verdicts":[{"question":1,"judgement":"common","why":"   "}]}"""
        assertTrue(VerdictParse.parse(raw, questions, evidence) is AppResult.Err)
    }

    @Test
    fun `判定文字里出现内部词就丢掉`() {
        val raw = """{"verdicts":[{"question":1,"judgement":"common","why":"evidence 里显示了三个项目"}]}"""
        assertTrue(VerdictParse.parse(raw, questions, evidence) is AppResult.Err)
    }

    @Test
    fun `一条能用的都没有时报错而不是返回空列表`() {
        assertTrue(VerdictParse.parse("""{"verdicts":[]}""", questions, evidence) is AppResult.Err)
        assertTrue(VerdictParse.parse("""{"verdicts":""", questions, evidence) is AppResult.Err)
    }

    @Test
    fun `代码围栏包着的 JSON 也能解析`() {
        val raw = "```json\n{\"verdicts\":[{\"question\":1,\"judgement\":\"known_pit\",\"why\":\"两个项目踩过同一个坑\"}]}\n```"
        val verdicts = (VerdictParse.parse(raw, questions, evidence) as AppResult.Ok).value
        assertEquals(1, verdicts.size)
    }
}
