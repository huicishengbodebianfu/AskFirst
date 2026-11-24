package com.askfirst.domain.plan

import com.askfirst.domain.model.PlanProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 机械检查：待决问题必在（blocking）、骨架缺章/数字无据/公文体（warning）。 */
class PlanValidatorTest {

    private val validator = PlanValidator()

    /** 这一场的「原话 + 已确认条目」语料：文档里的数字都得能在里面找到。 */
    private val facts = "打开首屏白屏 4 秒，第一屏内容一个都没出来。只有我一个人力，两周内要能用上。"

    private val fullSections = PlanFixtures.TECH_SKELETON.mapIndexed { index, heading ->
        PlanFixtures.section(
            id = "ps_$index",
            order = index + 1,
            heading = heading,
            body = "这一节写的是原话里有的东西。",
            sources = listOf("m1"),
        )
    }

    @Test
    fun `骨架齐全且待决保留时全绿`() {        val plan = PlanFixtures.plan(fullSections)
        val check = validator.check(plan, PlanFixtures.TECH_SKELETON, facts)
        assertTrue(check.canSend)
        assertNull(check.headline)
        assertNull(check.warningText)
    }

    @Test
    fun `缺待决问题就是 blocking 且文案说人话`() {
        val sections = fullSections.filterNot { it.heading.contains("待决") }
        val check = validator.check(PlanFixtures.plan(sections), PlanFixtures.TECH_SKELETON, facts)
        assertFalse(check.canSend)
        assertTrue(check.blocking.any { it is PlanProblem.PendingMissing })
        assertTrue(check.headline!!.contains("待决问题"))
    }

    @Test
    fun `待决被写成漂亮话就是 blocking`() {
        val sections = fullSections.map { section ->
            if (section.heading.contains("待决")) {
                section.copy(body = "后续持续优化缓存命中率，逐步完善监控。")
            } else {
                section
            }
        }
        val check = validator.check(PlanFixtures.plan(sections), PlanFixtures.TECH_SKELETON, facts)
        assertFalse(check.canSend)
        val hit = check.blocking.filterIsInstance<PlanProblem.PendingSoftened>().single()
        assertTrue(hit.hit.isNotBlank())
        assertTrue(check.headline!!.contains("安全带"))
    }

    @Test
    fun `待决写成空正文也算缺`() {
        val sections = fullSections.map { section ->
            if (section.heading.contains("待决")) section.copy(body = "  ") else section
        }
        val check = validator.check(PlanFixtures.plan(sections), PlanFixtures.TECH_SKELETON, facts)
        assertFalse(check.canSend)
        assertTrue(check.blocking.any { it is PlanProblem.PendingMissing })
    }

    @Test
    fun `骨架缺章只提醒不拦`() {
        val sections = fullSections.filterNot { it.heading == "排期里程碑" }
        val check = validator.check(PlanFixtures.plan(sections), PlanFixtures.TECH_SKELETON, facts)
        assertTrue(check.canSend)
        val missing = check.warnings.filterIsInstance<PlanProblem.MissingSection>().single()
        assertTrue(missing.heading.contains("排期里程碑"))
        assertTrue(check.warningText!!.contains("排期里程碑"))
    }

    @Test
    fun `正文数字在原话里找不到就提醒`() {
        val sections = fullSections.mapIndexed { index, section ->
            if (index == 0) section.copy(body = "首屏要从 4 秒降到 1.2 秒。") else section
        }
        val check = validator.check(PlanFixtures.plan(sections), PlanFixtures.TECH_SKELETON, facts)
        val stray = check.warnings.filterIsInstance<PlanProblem.NumberNotInSources>().flatMap { it.tokens }
        assertTrue(stray.contains("1.2"))
        assertFalse(stray.contains("4"))
        assertTrue(check.warningText!!.contains("原话里没有的数字"))
    }

    @Test
    fun `列表序号与脚注不算事实数字`() {
        val body = "1. 先做本地缓存\n2. 再上聚合层 [^3]\n- 3. 最后看监控"
        assertTrue(validator.numbersOf(body).isEmpty())
    }

    @Test
    fun `行首小数不许被当成列表序号剥掉`() {
        // docs/28 P2-3：`- 3.5 天上线` 原来会被剥成 `5 天上线`
        assertEquals(listOf("3.5"), validator.numbersOf("- 3.5 天上线"))
        assertEquals(listOf("1.5"), validator.numbersOf("吞吐要提高 1.5 倍"))
        // 真序号照样要剥干净
        assertTrue(validator.numbersOf("1. 先拉火焰图").isEmpty())
        assertTrue(validator.numbersOf("- 2. 再定契约").isEmpty())
    }

    @Test
    fun `公文体黑名单只是提醒`() {
        val sections = fullSections.mapIndexed { index, section ->
            if (index == 0) section.copy(body = "形成闭环，沉淀能力。") else section
        }
        val check = validator.check(PlanFixtures.plan(sections), PlanFixtures.TECH_SKELETON, facts)
        assertTrue(check.canSend)
        assertTrue(check.warnings.filterIsInstance<PlanProblem.BannedTerm>().single().terms.contains("闭环"))
    }

    @Test
    fun `没有出处的段落是 blocking`() {
        val sections = fullSections.mapIndexed { index, section ->
            if (index == 0) section.copy(sourceMessageIds = emptyList()) else section
        }
        val check = validator.check(PlanFixtures.plan(sections), PlanFixtures.TECH_SKELETON, facts)
        assertFalse(check.canSend)
        assertTrue(check.blocking.any { it is PlanProblem.UnsourcedSection })
    }
}
