package com.askfirst.domain.model

/**
 * 确认单 = 五栏条目集合。**条目行**（不是大段文本）是 M1 的结构决定 D1：
 * 条目天生带出处，M5 的段落溯源才不用重建。
 */
data class Sheet(
    val id: String,
    val sessionId: String,
    val summary: String,
    val items: Map<Int, List<SheetItem>>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun column(col: Int): List<SheetItem> = items[col].orEmpty()

    fun texts(col: Int): List<String> = column(col).map { it.text }

    fun isEmptyColumn(col: Int): Boolean = column(col).isEmpty()

    fun allItems(): List<SheetItem> = items.values.flatten()

    /** 第 4 栏动作条数——M1 的"能不能开工"判据 */
    val firstStepCount: Int get() = column(Cols.FIRST_STEPS).size
}

data class SheetItem(
    val id: String,
    val sheetId: String,
    val col: Int,
    val order: Int,
    val text: String,
    /** D4：至少一条，否则不许入库（不编造的执行层保证） */
    val sourceMessageIds: List<String>,
    /** 用户手改过；重新出单时不许被模型覆盖 */
    val edited: Boolean,
)

/** 引擎一轮能往单子里增的条目。 */
data class SheetDelta(
    val col: Int,
    val text: String,
    val sourceMessageIds: List<String>,
)

/** 跨场景引用用的摘要（M4）。M1 只留类型，不让引擎读到全文。 */
data class SheetDigest(
    val sheetId: String,
    val sceneId: String,
    val summary: String,
    val createdAt: Long,
)