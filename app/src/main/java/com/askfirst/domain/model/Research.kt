package com.askfirst.domain.model

/**
 * M6 调研验证的领域模型（docs/26 §3）。
 *
 * 一条铁律写在类型里而不是写在注释里：**证据只能进第 2/5 栏与附录**，
 * 所以 [AppliedTo] 里根本没有第 3 栏——想让证据去污染「选定方案」，
 * 在这个枚举上就写不出来（库里还有 CHECK 约束兜第二层）。
 */

/** 四种判定 + 一个诚实降级态。降级态不是失败，是「这次没查够」。 */
enum class Judgement(val db: String, val label: String, val mark: String) {
    /** 你的做法与 ≥3 个独立项目一致 */
    COMMON("common", "常见做法", "✅"),

    /** 证据里被明确抱怨/放弃的正是你要做的 */
    ANTIPATTERN("antipattern", "反模式", "⚠️"),

    /** 同类项目的高频 issue 主题 */
    KNOWN_PIT("known_pit", "已知坑", "🔍"),

    /** 换词复核后仍然 0 命中——四种判定里最值钱的那个 */
    UNEXPLORED("unexplored", "无人区", "❓"),

    /** 没查够 / 查不到 / 判定条件不成立：不下结论 */
    UNGATHERED("ungathered", "未取证", "·"),
    ;

    /** 报告里的分组顺序：坏消息在最前（方案 附录 C 的三条格式纪律之一）。 */
    val reportOrder: Int get() = when (this) {
        ANTIPATTERN -> 0
        KNOWN_PIT -> 1
        COMMON -> 2
        UNEXPLORED -> 3
        UNGATHERED -> 4
    }

    companion object {
        fun fromDb(value: String?): Judgement? = entries.firstOrNull { it.db == value?.trim() }
    }
}

/** 结论的落点。**没有 COL3**（docs/26 §3.1）。 */
enum class AppliedTo(val db: String, val label: String) {
    COL2("col2", "写进「约束与前提」"),
    COL5("col5", "写进「还没想清楚的问题」"),
    APPENDIX("appendix", "只放进验证报告"),
    ;

    val col: Int?
        get() = when (this) {
            COL2 -> Cols.CONSTRAINTS
            COL5 -> Cols.OPEN_QUESTIONS
            APPENDIX -> null
        }

    companion object {
        fun fromDb(value: String?): AppliedTo =
            entries.firstOrNull { it.db == value?.trim() } ?: APPENDIX
    }
}

/** 证据种类。READM 原文是「读原文不读简介」那条要求（方案 §3.6.1）的落点。 */
enum class EvidenceKind(val db: String) {
    REPO("repo"),
    ISSUE("issue"),
    CODE("code"),
    README("readme"),
    ;

    companion object {
        fun fromDb(value: String?): EvidenceKind? = entries.firstOrNull { it.db == value?.trim() }
    }
}

enum class ResearchStatus(val db: String) {
    QUEUED("queued"),
    RUNNING("running"),

    /** 部分成功：正常态，不是错误态（方案 §四） */
    PARTIAL("partial"),
    DONE("done"),
    FAILED("failed"),
    ;

    companion object {
        fun fromDb(value: String?): ResearchStatus =
            entries.firstOrNull { it.db == value?.trim() } ?: FAILED
    }
}

enum class ResearchSource(val db: String) {
    /** 本机直连 GitHub 检索 */
    GITHUB("github"),

    /** 离线外包：用户在桌面端跑完，把结果粘回来 */
    OFFLINE("offline"),
    ;

    companion object {
        fun fromDb(value: String?): ResearchSource =
            entries.firstOrNull { it.db == value?.trim() } ?: GITHUB
    }
}

/** 一个可检索的问题。keywords 就是「已覆盖的关键词」——报告里必须能列出来。 */
data class ResearchQuestion(
    val idx: Int,
    val text: String,
    val keywords: List<String>,
    val why: String = "",
)

/**
 * 一条证据。`id` 是本地生成的短 id，模型只许引用这次真抓到的 id（白名单过滤）。
 * `questionIdx` 让覆盖度可以机械算出来：有证据的问题 / 总问题数。
 */
data class Evidence(
    val id: String,
    val questionIdx: Int,
    val kind: EvidenceKind,
    val title: String,
    val url: String,
    val snippet: String = "",
    val repoFullName: String? = null,
    val stars: Int = 0,
    val pushedAt: String? = null,
)

/** 一条判定。`quotedText`/`quotedUrl` 只有反模式必须；`appliedTo` 决定它写进哪一栏。 */
data class Verdict(
    val id: String,
    val questionIdx: Int,
    val judgement: Judgement,
    val rationale: String,
    val quotedText: String = "",
    val quotedUrl: String = "",
    val appliedTo: AppliedTo = AppliedTo.APPENDIX,
    val evidenceIds: List<String> = emptyList(),
)

/** 某个问题在某类源上没查成。**必须进报告**，静默失败的「无人区」是有害的。 */
data class ResearchFailure(
    val questionIdx: Int,
    val source: String,
    val reason: String,
)

/**
 * 一次调研 = 一次落库。`planId` 可空（还没生成方案也能查，只是报告里不提方案）。
 */
data class Research(
    val id: String,
    val sessionId: String,
    val sheetId: String,
    val planId: String? = null,
    val title: String = "",
    val questions: List<ResearchQuestion> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    val verdicts: List<Verdict> = emptyList(),
    val failures: List<ResearchFailure> = emptyList(),
    val status: ResearchStatus = ResearchStatus.QUEUED,
    val elapsedMs: Long = 0,
    val source: ResearchSource = ResearchSource.GITHUB,
    /** 这次是不是带着 GitHub token 查的（报告顶部要如实写「匿名 / 已认证」） */
    val authenticated: Boolean = false,
    /** 做过换词复核的问题号：只有复核过的才敢说「无人区」 */
    val recheckedQuestions: List<Int> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val totalQuestions: Int get() = questions.size

    /** 取得过证据的问题数（不是判定数：判不出来但确实抓到了东西，也算查过） */
    val coveredQuestions: Int
        get() = questions.count { question -> evidence.any { it.questionIdx == question.idx } }

    /** 顶部那行「证据覆盖 3/6」，缺了多少就说什么不成立 */
    val coverageText: String get() = "$coveredQuestions/$totalQuestions"

    val unansweredQuestions: List<ResearchQuestion>
        get() = questions.filter { question -> evidence.none { it.questionIdx == question.idx } }

    fun verdictsOf(judgement: Judgement): List<Verdict> =
        verdicts.filter { it.judgement == judgement }.sortedBy { it.questionIdx }

    fun evidenceOf(verdict: Verdict): List<Evidence> =
        verdict.evidenceIds.mapNotNull { id -> evidence.firstOrNull { it.id == id } }
}

/**
 * 报告的机械检查结果。`blocking` 里的东西页面直接说「这份报告我不认」，
 * `warnings` 是提示（比如某条反模式因为没引到原话被丢了）。
 */
data class ResearchCheck(
    val blocking: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val canShow: Boolean get() = blocking.isEmpty()

    val headline: String
        get() = when {
            blocking.isNotEmpty() -> blocking.first()
            warnings.isNotEmpty() -> warnings.first()
            else -> "查完了，结论在下面"
        }
}
