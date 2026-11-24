package com.askfirst.domain.plan

import com.askfirst.domain.model.PlanSectionDraft

/**
 * 续写合并（「接着写剩下的」）：保住已经写好的段落，只把新补的接上去，最后按骨架顺序重排。
 * 纯函数。同标题视为同一节——模型换个说法重写一遍时不会出现两节「背景与问题」。
 */
object PlanMerge {

    fun merge(
        existing: List<PlanSectionDraft>,
        incoming: List<PlanSectionDraft>,
        skeleton: List<String>,
    ): List<PlanSectionDraft> {
        val merged = existing.toMutableList()
        incoming.forEach { draft ->
            val duplicated = merged.any { PlanSkeleton.matches(it.heading, draft.heading) }
            if (!duplicated) merged += draft
        }
        return merged
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<PlanSectionDraft>> { PlanSkeleton.indexOf(skeleton, it.value.heading) }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    /** 骨架里还没有的章节标题（续写时要模型补的就是这些）。 */
    fun missingHeadings(
        existing: List<PlanSectionDraft>,
        skeleton: List<String>,
    ): List<String> = skeleton.filter { heading ->
        existing.none { PlanSkeleton.matches(heading, it.heading) }
    }
}
