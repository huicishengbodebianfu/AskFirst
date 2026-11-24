package com.askfirst.domain.plan

import com.askfirst.domain.model.Scene

/**
 * 文档骨架（方案 §3.5）：技术方案 / 立项 / 复盘三套来自场景的 `planSkeleton`
 * （M1 就入库了），自建场景没配骨架时用 [GENERIC]。
 *
 * 一条硬规则：**骨架里必须有一节「待决问题」**——方案 §3.5 硬约束 3 说第 5 栏不许被消化，
 * 落到文档里就是这一节必须在。骨架没配（比如复盘骨架最后是「待验证」）就自动补一节。
 * 纯函数，可单测。
 */
object PlanSkeleton {

    /** 固定标题：校验器按「标题里含待决」认这一节。 */
    const val PENDING = "待决问题"

    /** 超过 12 节就不像文档了，像目录。 */
    const val MAX_SECTIONS = 12

    /** 自建场景没有骨架时的通用结构（方案 §3.5 表格最后一行）。 */
    val GENERIC = listOf("概述", "结论", "依据", "行动项", PENDING)

    fun resolve(scene: Scene?): List<String> =
        normalize(scene?.planSkeleton.orEmpty().ifEmpty { GENERIC })

    /** 去空、去重、瘦身、保证有「待决」。 */
    fun normalize(raw: List<String>): List<String> {
        val cleaned = raw.mapNotNull { line ->
            line.trim()
                .removePrefix("#").trim()
                .removePrefix("-").trim()
                .removePrefix("·").trim()
                .takeIf { it.isNotEmpty() }
        }.distinct()

        val hasPending = cleaned.any { isPending(it) }
        return if (hasPending) {
            cleaned.take(MAX_SECTIONS)
        } else {
            // 留一个位置给「待决问题」，别让补进来的那节被截掉
            cleaned.take(MAX_SECTIONS - 1) + PENDING
        }
    }

    fun isPending(heading: String): Boolean = heading.contains("待决")

    /** 骨架标题与模型给的标题是不是同一节（宽松：允许「风险与待决问题」vs「风险与待决」）。 */
    fun matches(skeletonHeading: String, actual: String): Boolean {
        val a = squash(skeletonHeading)
        val b = squash(actual)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /** 标题在骨架里的位置；不在骨架里 = 排到最后。 */
    fun indexOf(skeleton: List<String>, heading: String): Int {
        val hit = skeleton.indexOfFirst { matches(it, heading) }
        return if (hit < 0) Int.MAX_VALUE else hit
    }

    private fun squash(text: String): String =
        text.replace(Regex("""[\s　:：·.、,，.。()（）\[\]【】#*`]"""), "")
}
