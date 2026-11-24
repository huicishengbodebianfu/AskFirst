package com.askfirst.domain.model

/** 四状态机（改动 #3）。只用于引擎内部与数据层，界面永不显示这些词。 */
enum class EngineState(val api: String) {
    GATHERING("gathering"),
    NEEDS_PRECISION("needs_precision"),
    BLOCKED("blocked"),
    READY("ready"),
    ;

    companion object {
        fun fromApi(value: String?): EngineState? =
            entries.firstOrNull { it.api == value?.trim()?.lowercase() }

        fun fromDb(value: String): EngineState =
            fromApi(value) ?: throw IllegalStateException("未知的 EngineState: $value")
    }
}

enum class SessionStatus(val db: String) {
    OPEN("open"),
    DONE("done"),
    DRAFT("draft"),
    ;

    companion object {
        fun fromDb(value: String): SessionStatus =
            entries.firstOrNull { it.db == value } ?: throw IllegalStateException("未知的 SessionStatus: $value")
    }
}

enum class QuestionKind(val db: String) {
    /** 默认：开放输入 */
    OPEN("open"),

    /** 只有答案真的对应不同取舍方向时才用，UI 会自动补「我自己写」 */
    CHOICE("choice"),
    ;

    companion object {
        fun fromDb(value: String): QuestionKind =
            entries.firstOrNull { it.db == value } ?: OPEN
    }
}

enum class TurnRole(val db: String) {
    USER("user"),
    AI("ai"),
    ;

    companion object {
        fun fromDb(value: String): TurnRole = when (value) {
            "user" -> USER
            "ai" -> AI
            else -> throw IllegalStateException("未知的 TurnRole: $value")
        }
    }
}

/** 精确度四项检查的四种缺口（改动 #4）。 */
enum class PrecisionKind(val api: String) {
    HIDDEN_DEFAULT("hidden_default"),
    CONTRADICTION("contradiction"),
    WALKTHROUGH_GAP("walkthrough_gap"),
    GOAL_DRIFT("goal_drift"),
    ;

    companion object {
        fun fromApi(value: String?): PrecisionKind? = entries.firstOrNull { it.api == value?.trim() }
    }
}

/** 五栏定义。col 是数据库里的列号，1..5。 */
object Cols {
    const val GOAL = 1
    const val CONSTRAINTS = 2
    const val OPTIONS = 3
    const val FIRST_STEPS = 4
    const val OPEN_QUESTIONS = 5

    val ALL = listOf(GOAL, CONSTRAINTS, OPTIONS, FIRST_STEPS, OPEN_QUESTIONS)

    val titles: Map<Int, String> = mapOf(
        GOAL to "目标与成功标准",
        CONSTRAINTS to "约束与前提",
        OPTIONS to "选定方案与被否备选",
        FIRST_STEPS to "第一步动作清单",
        OPEN_QUESTIONS to "还没想清楚的问题",
    )

    fun title(col: Int): String = titles[col] ?: "第 $col 栏"
}