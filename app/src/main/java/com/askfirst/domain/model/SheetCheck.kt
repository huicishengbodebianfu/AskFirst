package com.askfirst.domain.model

/**
 * 出单前的本地检查结果（M1-10 的判据）。
 * `blocking` 非空 = "这份单子还不能直接开工"，但**不阻止保存**（用户有权先存着）。
 */
data class SheetCheck(
    val blocking: List<SheetProblem>,
    val warnings: List<SheetProblem> = emptyList(),
) {
    val canStart: Boolean get() = blocking.isEmpty()

    /** 给用户看的一句话（不出现内部术语）。 */
    val headline: String?
        get() = when {
            blocking.any { it is SheetProblem.NotEnoughFirstSteps } ->
                "这份单子还不能直接开工：第一步动作还不够具体（至少 2 条能当天动手的事）。"

            blocking.any { it is SheetProblem.UnsourcedItem } ->
                "有一条内容找不到出处，我先把它标出来了，你确认下是不是你自己说的。"

            else -> null
        }

    val warningText: String?
        get() = buildList {
            warnings.filterIsInstance<SheetProblem.EmptyColumn>()
                .takeIf { it.isNotEmpty() }
                ?.joinToString("、") { Cols.title(it.col) }
                ?.let { add("这几栏还是空的：$it") }
            warnings.filterIsInstance<SheetProblem.HedgedFirstSteps>()
                .firstOrNull()
                ?.let { add("第 4 栏里有 ${it.count} 条动作还带着没定的前提，严格说还不能直接开工。") }
        }.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

sealed interface SheetProblem {
    data class NotEnoughFirstSteps(val count: Int) : SheetProblem
    data class EmptyColumn(val col: Int) : SheetProblem
    data class UnsourcedItem(val itemId: String) : SheetProblem

    /** 条数够，但动作写着"未定/未确认"——提示用户先确认前提 */
    data class HedgedFirstSteps(val count: Int) : SheetProblem
}