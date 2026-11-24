package com.askfirst.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条判定（M6，库 v4）。
 *
 * **铁律的第二层**：`appliedTo` 在 [com.askfirst.data.db.AskFirstDatabase.MIGRATION_3_4] 里带了
 * `CHECK (appliedTo IN ('col2','col5','appendix'))` —— 第 3 栏（选定方案）在数据库层就写不进去。
 * 第一层是 [com.askfirst.domain.model.AppliedTo] 枚举里根本没有 COL3。
 */
@Entity(
    tableName = "verdict",
    foreignKeys = [
        ForeignKey(
            entity = ResearchEntity::class,
            parentColumns = ["id"],
            childColumns = ["researchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["researchId"])],
)
data class VerdictEntity(
    @PrimaryKey val id: String,
    val researchId: String,
    /** 冗余一份会话 id：历史页要按会话一次查完，不走 JOIN */
    val sessionId: String,
    val questionIdx: Int,
    /** common / antipattern / known_pit / unexplored / ungathered */
    val judgement: String,
    /** 「这意味着什么」——人话，坏消息直说 */
    val rationale: String,
    val quotedText: String,
    val quotedUrl: String,
    /** col2 / col5 / appendix（CHECK 约束守在这里） */
    val appliedTo: String,
    /** `["ev_q1_repo_1"]` */
    val evidenceIdsJson: String,
    val createdAt: Long,
)
