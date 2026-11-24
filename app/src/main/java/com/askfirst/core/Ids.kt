package com.askfirst.core

import java.util.UUID

/** id 生成来源（测试用固定实现，生产用 UUID）。 */
fun interface IdSource {
    fun newId(prefix: String): String
}

object UuidIdSource : IdSource {
    override fun newId(prefix: String): String {
        val raw = UUID.randomUUID().toString().replace("-", "")
        return "${prefix}_${raw.take(16)}"
    }
}

/** 常用前缀，集中在这里，避免各处硬编码字符串散落。 */
object IdPrefix {
    const val SCENE = "scene"
    const val QUESTION = "q"
    const val SESSION = "s"
    const val MESSAGE = "m"
    const val SHEET = "sh"
    const val ITEM = "it"
    const val CITATION = "c"
    const val PLAN = "pl"

    const val RESEARCH = "rs"

    /** M6 一条判定（可按条回写/撤销）。 */
    const val VERDICT = "vd"

    /** M5 方案里的一段（段落自带出处，逐段可删）。 */
    const val PLAN_SECTION = "ps"
}
