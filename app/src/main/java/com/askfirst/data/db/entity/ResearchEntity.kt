package com.askfirst.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次调研（M6，库 v4）。整份重跑覆盖同一场会话的旧记录（和 plan 一个套路），
 * 但**判定拆到 [VerdictEntity]**：`appliedTo` 要用数据库约束守「只进第 2/5 栏」这条铁律，
 * 而且回写/撤销要按条走（docs/26 §3.4）。
 */
@Entity(tableName = "research", indices = [Index(value = ["sessionId"])])
data class ResearchEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sheetId: String,
    /** 可空：还没生成方案时也能查 */
    val planId: String?,
    val title: String,
    /** `[{"idx":1,"text":"…","keywords":["…"],"why":"…"}]` */
    val questionsJson: String,
    /** `[{"id":"ev_q1_repo_1","questionIdx":1,"kind":"repo",…}]` */
    val evidenceJson: String,
    /** `[{"questionIdx":1,"source":"issue 检索","reason":"被限流"}]` */
    val failuresJson: String,
    /** queued / running / partial / done / failed */
    val status: String,
    val elapsedMs: Long,
    /** github / offline */
    val source: String,
    /** 这次是不是带着 token 查的（报告顶部要如实写匿名/已认证） */
    val authenticated: Boolean,
    /** 做过换词复核的问题号，`[1,3]` */
    val recheckedJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
