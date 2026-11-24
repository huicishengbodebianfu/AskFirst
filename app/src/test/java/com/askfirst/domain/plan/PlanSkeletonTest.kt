package com.askfirst.domain.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 骨架规则：场景骨架优先、通用兜底、**「待决问题」必在**、去重截断。 */
class PlanSkeletonTest {

    @Test
    fun `场景自带骨架优先用`() {
        assertEquals(PlanFixtures.TECH_SKELETON, PlanSkeleton.resolve(PlanFixtures.scene()))
    }

    @Test
    fun `自建场景没骨架用通用五节`() {
        val generic = PlanSkeleton.resolve(PlanFixtures.scene(emptyList()))
        assertEquals(PlanSkeleton.GENERIC, generic)
        assertEquals(listOf("概述", "结论", "依据", "行动项", "待决问题"), generic)
        assertEquals(PlanSkeleton.GENERIC, PlanSkeleton.resolve(null))
    }

    @Test
    fun `骨架缺待决时自动补一节在最后`() {
        val resolved = PlanSkeleton.resolve(PlanFixtures.scene(PlanFixtures.RETRO_SKELETON))
        assertEquals(PlanFixtures.RETRO_SKELETON + "待决问题", resolved)
        assertTrue(resolved.any { PlanSkeleton.isPending(it) })
    }

    @Test
    fun `去空去重_超长截断且不丢待决`() {
        val messy = listOf("  概述  ", "", "- 结论", "概述", "· 依据", "# 行动项")
        assertEquals(listOf("概述", "结论", "依据", "行动项", "待决问题"), PlanSkeleton.normalize(messy))

        val tooMany = (1..20).map { "第${it}节" }
        val capped = PlanSkeleton.normalize(tooMany)
        assertEquals(PlanSkeleton.MAX_SECTIONS, capped.size)
        assertTrue(capped.last() == "待决问题")
        assertFalse(capped.any { it == "第20节" })
    }
}
