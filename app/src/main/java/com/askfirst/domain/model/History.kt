package com.askfirst.domain.model

import com.askfirst.core.Text

/** 历史列表的筛选（M2）。sceneId = null 表示「全部」。 */
data class HistoryFilter(val sceneId: String? = null) {
    val all: Boolean get() = sceneId == null
}

/** 历史列表一行（对应 session 表 + 场景名，时间倒序）。 */
data class HistoryEntry(
    val id: String,
    val sceneId: String,
    val sceneName: String?,
    val intent: String,
    val summary: String?,
    val status: SessionStatus,
    val state: EngineState,
    val updatedAt: Long,
    val turnCount: Int,
    val itemCount: Int,
) {
    /** 列表主文案：有摘要用摘要，否则用开题一句话。 */
    val title: String get() = summary?.takeIf { it.isNotBlank() } ?: intent

    /** 副文案：场景 + 状态 + 轮数。 */
    val subtitle: String
        get() = buildString {
            append(sceneName ?: "（场景已删除）")
            append(" · ")
            append(statusLabel())
            append(" · ")
            append("$turnCount 条消息")
            if (itemCount > 0) append(" · 单子 $itemCount 条")
        }

    private fun statusLabel(): String = when (status) {
        SessionStatus.OPEN -> when (state) {
            EngineState.BLOCKED -> "被拦下"
            EngineState.READY -> "可以出单"
            else -> "进行中"
        }
        SessionStatus.DONE -> "已出单"
        SessionStatus.DRAFT -> "草稿"
    }

    companion object {
        fun previewTitle(entry: HistoryEntry): String = Text.truncate(entry.title, 60)
    }
}
