package com.askfirst.domain.sheet

import com.askfirst.domain.model.Turn

/**
 * 把条目的 `sourceMessageIds` 还原成"当时他说过的那几句话"（M1-07 的"点条目看出处"）。
 * 纯函数：顺序按条目记录的 id 顺序，找不到的 id 直接跳过（数据被删过也不许崩）。
 */
object SourceCollector {

    fun collect(turns: List<Turn>, sourceIds: List<String>): List<Turn> {
        if (sourceIds.isEmpty()) return emptyList()
        val byId = turns.associateBy { it.id }
        return sourceIds.mapNotNull { byId[it] }
    }
}