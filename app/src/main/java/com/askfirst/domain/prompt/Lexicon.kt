package com.askfirst.domain.prompt

/**
 * 内置语言学知识（改动 #5）：歧义词追问表 + 矛盾规则对 + 内部术语黑名单。
 * 纯数据，不依赖 Android / 网络 / 模型——它让"精确度检查"能在离线状态下跑。
 */
object Lexicon {

    /** 一个词 → 它可能指代的几种意思。追问时必须二选一，不许开放式泛问。 */
    val ambiguous: Map<String, List<String>> = linkedMapOf(
        "简单" to listOf("功能少", "步骤少", "界面干净"),
        "保存" to listOf("存本地文件", "存数据库", "存云端"),
        "同步" to listOf("多设备实时同步", "只是备份", "定时上传"),
        "分享" to listOf("发链接", "发文件", "截图给对方"),
        "管理" to listOf("增删改查", "批量操作", "只是看得清"),
        "通知" to listOf("系统推送", "App 内提醒", "邮件/短信"),
        "记录" to listOf("随手记一条", "结构化表单", "自动采集"),
        "搜索" to listOf("按关键词搜", "按时间翻", "按标签筛"),
        "用户" to listOf("我自己", "亲友小范围", "公开用户"),
        "上线" to listOf("自己能用", "发给朋友", "公开分发"),
    )

    /**
     * 矛盾规则对：A 与 B 同时出现在用户回答里，就是"两条不能同时成立"。
     * `question` 是给用户的提问（把两句原话并排引出来问"哪个是你真正要的"，不许两边都圆）。
     */
    val contradictions: List<ContradictionRule> = listOf(
        ContradictionRule(
            id = "no_login_but_personal",
            a = Regex("不(用|需要)?登录|无账号|不要账号"),
            b = Regex("每(个)?人(都)?(要)?(能|可以)?看(到)?(自己|各自)|各自(的)?(数据|内容)|我的数据"),
            aLabel = "不要登录/账号",
            bLabel = "每个人看自己的内容",
            question = "你说不用登录，又说每个人看自己的内容——这两条不能同时成立。哪条是你真正要的？",
        ),
        ContradictionRule(
            id = "local_but_multi_device",
            a = Regex("纯本地|不联网|不要云|不上云"),
            b = Regex("多端|多设备|远程访问|手机电脑都能"),
            aLabel = "纯本地/不联网",
            bLabel = "多设备/远程",
            question = "你说纯本地不联网，又要多设备——要么加同步，要么砍掉多设备。你更舍不得哪一个？",
        ),
        ContradictionRule(
            id = "simplest_but_more",
            a = Regex("最简|最简单|功能越少越好|别搞复杂"),
            b = Regex("还要|以及|顺便(再)?做|再加上"),
            aLabel = "越简单越好",
            bLabel = "还要再加功能",
            question = "你说越简单越好，又列了要加的东西——如果要砍掉一半，你先砍哪个？",
        ),
        ContradictionRule(
            id = "no_cost_but_paid",
            a = Regex("不花(钱|费)|零成本|白嫖|免费"),
            b = Regex("买|采购|付费|订阅费|付费服务"),
            aLabel = "不花钱",
            bLabel = "要采购/付费",
            question = "你说不花钱，又提到买服务——这笔钱到底花不花？",
        ),
    )

    /** 界面与提示词里都不许出现的内部术语（改动 #11）。 */
    val bannedTerms: List<String> = listOf(
        "门槛", "状态机", "精确度", "缺口", "维度", "轮次", "覆盖", "schema", "payload",
        "结构化", "赋能", "抓手", "对齐", "心智", "沉淀", "闭环", "收敛",
    )

    /** 扫描一段文本命中的歧义词（带原句片段，便于并排引用）。 */
    fun ambiguousHits(text: String): List<AmbiguousHit> =
        ambiguous.entries.mapNotNull { (term, options) ->
            val index = text.indexOf(term)
            if (index < 0) return@mapNotNull null
            AmbiguousHit(
                term = term,
                options = options,
                quote = sentenceAround(text, index),
            )
        }

    private fun sentenceAround(text: String, index: Int): String {
        val start = text.lastIndexOfAny(charArrayOf('。', '！', '？', '；', '\n'), index).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOfAny(charArrayOf('。', '！', '？', '；', '\n'), index).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim().ifBlank { text.take(40) }
    }
}

data class AmbiguousHit(val term: String, val options: List<String>, val quote: String)

data class ContradictionRule(
    val id: String,
    val a: Regex,
    val b: Regex,
    val aLabel: String,
    val bLabel: String,
    val question: String,
)