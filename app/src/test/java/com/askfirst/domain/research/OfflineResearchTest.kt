package com.askfirst.domain.research

import com.askfirst.core.AppResult
import com.askfirst.domain.model.Judgement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 离线外包：导出的提示词要能被人看懂，粘回来的 JSON 要走同一套规则。 */
class OfflineResearchTest {

    private val questions = listOf(
        ResearchFixtures.question(idx = 1),
        ResearchFixtures.question(idx = 2, text = "SAF 授权重启后还在不在", keywords = listOf("SAF")),
    )

    @Test
    fun `导出的提示词带问题与关键词 且说明要回什么`() {
        val prompt = OfflineResearch.promptOf("首屏 4 秒太慢，想加缓存", questions)
        assertTrue(prompt.contains("首屏 4 秒太慢，想加缓存"))
        assertTrue(prompt.contains("Room 里做中文全文检索"))
        assertTrue(prompt.contains("检索关键词：Room FTS5 / 中文分词"))
        assertTrue(prompt.contains("\"evidence\""))
        assertTrue(prompt.contains("\"verdicts\""))
        assertTrue("要禁止硬凑常见做法", prompt.contains("不要拿 1~2 个项目硬凑"))
    }

    @Test
    fun `粘回来的完整 JSON 能解析出证据与判定`() {
        val raw = """
            {"evidence":[
               {"id":"ev_q1_repo_1","question":1,"kind":"repo","title":"owner/cache-lib",
                "url":"https://github.com/owner/cache-lib","repo":"owner/cache-lib","stars":1234},
               {"id":"ev_q1_issue_1","question":1,"kind":"issue","title":"corruption on concurrent writes",
                "url":"https://github.com/owner/cache-lib/issues/217","snippet":"moved to per-record rows"},
               {"id":"ev_q1_issue_2","question":1,"kind":"issue","title":"same pit again",
                "url":"https://github.com/owner2/other/issues/3"}
             ],
             "verdicts":[
               {"question":1,"judgement":"known_pit","why":"两个项目在同一个写入路径上摔过。",
                "evidence":["ev_q1_issue_1","ev_q1_issue_2"]}
             ]}
        """
        val bundle = (OfflineResearch.parseBundle(raw, questions) as AppResult.Ok).value
        assertEquals(3, bundle.evidence.size)
        assertEquals(1, bundle.verdicts.size)
        assertEquals(Judgement.KNOWN_PIT, bundle.verdicts.first().judgement)
        assertEquals(listOf("ev_q1_issue_1", "ev_q1_issue_2"), bundle.verdicts.first().evidenceIds)
    }

    @Test
    fun `代码围栏与首尾空白都能容忍`() {
        val raw = "```json\n{\"evidence\":[],\"verdicts\":[{\"question\":1,\"judgement\":\"unexplored\",\"why\":\"没找到同类\"}]}\n```"
        assertTrue(OfflineResearch.parseBundle(raw, questions) is AppResult.Ok)
    }

    @Test
    fun `对不上问题号与不认识的判定被丢掉`() {
        val raw = """
            {"evidence":[{"question":9,"url":"https://github.com/x/y","title":"x/y"}],
             "verdicts":[{"question":1,"judgement":"听起来不错","why":"x"},
                         {"question":1,"judgement":"ungathered","why":"没查够"}]}
        """
        val bundle = (OfflineResearch.parseBundle(raw, questions) as AppResult.Ok).value
        assertTrue(bundle.evidence.isEmpty())
        assertEquals(1, bundle.verdicts.size)
    }

    @Test
    fun `空的或者坏的 JSON 报错`() {
        assertTrue(OfflineResearch.parseBundle("我不懂你在说什么", questions) is AppResult.Err)
        assertTrue(OfflineResearch.parseBundle("""{"evidence":[],"verdicts":[]}""", questions) is AppResult.Err)
    }

    @Test
    fun `粘回来的判定仍要过四条硬规则`() {
        // 只给 1 条 issue，却声称 known_pit → 归一化后必须降级
        val raw = """
            {"evidence":[{"id":"ev_q1_issue_1","question":1,"kind":"issue","title":"one pit",
                          "url":"https://github.com/a/b/issues/1"}],
             "verdicts":[{"question":1,"judgement":"known_pit","why":"高频坑","evidence":["ev_q1_issue_1"]}]}
        """
        val bundle = (OfflineResearch.parseBundle(raw, questions) as AppResult.Ok).value
        val normalized = ResearchRules.normalize(bundle.verdicts, bundle.evidence, questions, setOf(1, 2))
        assertEquals(Judgement.UNGATHERED, normalized.verdicts.single().judgement)
    }

    @Test
    fun `提示词里不出现内部术语`() {
        val prompt = OfflineResearch.promptOf("首屏太慢", questions)
        assertFalse(prompt.lowercase().contains("verdict json"))
        assertFalse(prompt.contains("sectionsJson"))
    }
}
