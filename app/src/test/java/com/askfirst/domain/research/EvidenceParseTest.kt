package com.askfirst.domain.research

import com.askfirst.core.AppResult
import com.askfirst.domain.model.EvidenceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub 检索结果 → 证据：脏数据丢掉、0 命中合法、README 能解出原文。 */
class EvidenceParseTest {

    // ---- docs/28 P2-7：清洗 README 不许把代码里的泛型一起吃掉 ----

    @Test
    fun `清理 README 不许吃掉代码里的泛型与比较式`() {
        val raw = "## 缓存\n\n```kotlin\nval m: Map<String, List<Int>> = mapOf()\n```\n"
        val cleaned = EvidenceParse.clean(raw)
        assertTrue("泛型被吃了：$cleaned", cleaned.contains("Map<String, List<Int>>"))
        assertTrue(cleaned.contains("val m"))
    }

    @Test
    fun `清理 README 要剥掉真的 HTML 标签与徽章`() {
        val raw = "[![CI](https://img.shields.io/badge.svg)](https://ci.example.com)\n" +
            "<div align=\"center\">hello</div>\n<br/>\n正文在这里"
        val cleaned = EvidenceParse.clean(raw)
        assertFalse("徽章链接没剥：$cleaned", cleaned.contains("shields.io"))
        assertFalse("HTML 标签没剥：$cleaned", cleaned.contains("<div"))
        assertFalse("自闭合标签没剥：$cleaned", cleaned.contains("<br"))
        assertTrue(cleaned.contains("hello"))
        assertTrue(cleaned.contains("正文在这里"))
    }

    @Test
    fun `repo 解析出 id 仓库名 star 与链接`() {
        val evidence = (EvidenceParse.parseRepos(ResearchFixtures.REPOS_JSON, 1) as AppResult.Ok).value
        assertEquals(3, evidence.size) // 第 4 条没有 html_url，必须被丢掉
        val first = evidence.first()
        assertEquals("ev_q1_repo_1", first.id)
        assertEquals(EvidenceKind.REPO, first.kind)
        assertEquals("owner/cache-lib", first.repoFullName)
        assertEquals(1234, first.stars)
        assertEquals("https://github.com/owner/cache-lib", first.url)
        assertEquals("ev_q1_repo_2", evidence[1].id)
        assertEquals(88, evidence[1].stars)
    }

    @Test
    fun `issue 解析出正文片段与仓库名`() {
        val evidence = (EvidenceParse.parseIssues(ResearchFixtures.ISSUES_JSON, 1) as AppResult.Ok).value
        assertEquals(2, evidence.size)
        assertEquals(EvidenceKind.ISSUE, evidence.first().kind)
        assertTrue(evidence.first().snippet.contains("corruption"))
        assertEquals("owner/cache-lib", evidence.first().repoFullName)
        assertEquals("https://github.com/owner/cache-lib/issues/217", evidence.first().url)
        // body 是 null 的那条也要活下来（只是没有正文）
        assertEquals("", evidence[1].snippet)
    }

    @Test
    fun `code 解析出仓库与路径`() {
        val evidence = (EvidenceParse.parseCode(ResearchFixtures.CODE_JSON, 2) as AppResult.Ok).value
        assertEquals(1, evidence.size)
        assertEquals("CacheManager.kt", evidence.first().title)
        assertEquals("owner/cache-lib", evidence.first().repoFullName)
        assertEquals("ev_q2_code_1", evidence.first().id)
    }

    @Test
    fun `0 命中是合法结果而不是错误`() {
        val empty = """{"total_count":0,"items":[]}"""
        val parsed = EvidenceParse.parseRepos(empty, 3)
        assertTrue(parsed is AppResult.Ok)
        assertTrue((parsed as AppResult.Ok).value.isEmpty())
    }

    @Test
    fun `坏 JSON 与缺 items 都报错`() {
        assertTrue(EvidenceParse.parseRepos("""{"items":""", 1) is AppResult.Err)
        assertTrue(EvidenceParse.parseRepos("""{"total_count":0}""", 1) is AppResult.Err)
        assertTrue(EvidenceParse.parseIssues("""{"items":{}}""", 1) is AppResult.Err)
    }

    @Test
    fun `README 能 base64 解出正文并去掉徽章`() {
        val text = EvidenceParse.readmeText(ResearchFixtures.README_JSON)
        assertTrue("解出来不能是空", !text.isNullOrBlank())
        assertTrue(text!!.contains("本地优先"))
        assertTrue(text.contains("取舍"))
        assertFalse("徽章图片要清掉", text.contains("shields.io"))
        assertFalse("标题符号要清掉", text.contains("# cache-lib"))
    }

    @Test
    fun `README 缺 content 或坏 base64 返回 null`() {
        assertEquals(null, EvidenceParse.readmeText("""{"name":"README.md"}"""))
        assertEquals(null, EvidenceParse.readmeText("""{"content":"!!!!不是合法的base64!!!!"}"""))
        assertEquals(null, EvidenceParse.readmeText("not json"))
    }

    @Test
    fun `证据 id 可按问题号与序号复算`() {
        assertEquals("ev_q3_issue_2", EvidenceParse.evidenceId(3, EvidenceKind.ISSUE, 2))
    }
}
