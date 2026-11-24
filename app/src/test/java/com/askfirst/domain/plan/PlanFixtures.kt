package com.askfirst.domain.plan

import com.askfirst.domain.model.Plan
import com.askfirst.domain.model.PlanSection
import com.askfirst.domain.model.PlanSectionDraft
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import java.time.LocalDate
import java.time.ZoneId

/** M5 方案文档的测试夹具：骨架 / 段落 / 一场最小问答。 */
object PlanFixtures {

    const val SESSION_ID = "s_1"

    /** assets/scenes/tech_arch.json 里的骨架（逐字，改它就要改提示词与验收）。 */
    val TECH_SKELETON = listOf(
        "背景与问题",
        "目标与非目标",
        "约束",
        "方案对比表",
        "选定方案与设计要点",
        "迁移与回滚",
        "风险与待决问题",
        "排期里程碑",
    )

    /** assets/scenes/retro.json 里的骨架：最后是「待验证」，没有「待决」。 */
    val RETRO_SKELETON = listOf(
        "原定目标", "实际结果", "差距归因", "关键转折点", "运气 vs 能力", "下次改什么", "结论与行动项", "待验证",
    )

    fun scene(skeleton: List<String> = TECH_SKELETON): Scene = Scene(
        id = "scene_tech_arch",
        name = "技术方案 · 架构选型",
        focus = "把模糊的技术想法逼到能开工",
        builtin = true,
        planSkeleton = skeleton,
        questions = emptyList(),
    )

    fun section(
        id: String = "ps_1",
        order: Int = 1,
        heading: String = "背景与问题",
        body: String = "首屏白屏四秒，第一屏内容一个都没出来。",
        sources: List<String> = listOf("m1"),
    ) = PlanSection(
        id = id,
        order = order,
        heading = heading,
        body = body,
        sourceMessageIds = sources,
    )

    fun draft(
        heading: String,
        body: String = "写的是一句人话。",
        sources: List<String> = listOf("m1"),
    ) = PlanSectionDraft(heading = heading, body = body, sourceMessageIds = sources)

    fun plan(
        sections: List<PlanSection> = listOf(section()),
        title: String = "首屏缓存分层方案",
    ) = Plan(
        id = "pl_1",
        sessionId = SESSION_ID,
        sheetId = "sh_1",
        title = title,
        sections = sections,
        createdAt = 0,
        updatedAt = 0,
    )

    fun turns(): List<Turn> = listOf(
        Turn("m1", SESSION_ID, TurnRole.USER, "打开首屏白屏四秒，第一屏内容一个都没出来", createdAt = 0),
        Turn("m2", SESSION_ID, TurnRole.AI, "你说的「慢」是首屏打开就慢，不是滚动卡。", createdAt = 1),
        Turn("m3", SESSION_ID, TurnRole.USER, "只有我一个人力，两周内要能用上。", createdAt = 2),
    )

    /** 固定的"今天"：用例里的日期断言不随机器时区漂。 */
    fun fixedNow(year: Int = 2026, month: Int = 11, day: Int = 24): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
