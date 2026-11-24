package com.askfirst.domain.plan

import com.askfirst.domain.model.PlanSectionDraft
import org.junit.Assert.assertEquals
import org.junit.Test

/** M5 续写合并：补齐缺章、不重复、按骨架重排。 */
class PlanMergeTest {

    @Test
    fun `续写只追加缺的章节`() {
        val existing = listOf(
            PlanFixtures.draft("背景与问题"),
            PlanFixtures.draft("目标与非目标"),
        )
        val incoming = listOf(
            PlanFixtures.draft("目标与非目标", body = "模型换了个说法又写了一遍"),
            PlanFixtures.draft("约束"),
        )
        val merged = PlanMerge.merge(existing, incoming, PlanFixtures.TECH_SKELETON)
        assertEquals(listOf("背景与问题", "目标与非目标", "约束"), merged.map { it.heading })
        // 已有章节的内容不许被第二次调用覆盖
        assertEquals("写的是一句人话。", merged[1].body)
    }

    @Test
    fun `合并后按骨架顺序排`() {
        val existing = listOf(PlanFixtures.draft("约束"))
        val incoming = listOf(
            PlanFixtures.draft("排期里程碑"),
            PlanFixtures.draft("背景与问题"),
        )
        val merged = PlanMerge.merge(existing, incoming, PlanFixtures.TECH_SKELETON)
        assertEquals(listOf("背景与问题", "约束", "排期里程碑"), merged.map { it.heading })
    }

    @Test
    fun `缺章列表按骨架顺序给出`() {
        val existing = listOf<PlanSectionDraft>(PlanFixtures.draft("背景与问题"))
        val missing = PlanMerge.missingHeadings(existing, PlanFixtures.TECH_SKELETON)
        assertEquals(PlanFixtures.TECH_SKELETON.drop(1), missing)
    }
}
