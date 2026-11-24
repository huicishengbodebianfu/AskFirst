package com.askfirst.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 方案文档（M5，库 v3）：一场会话一份，段落整份存 JSON（段落天生带出处）。
 * 重新生成覆盖同一行 —— 不留版本历史（确认单才是真相来源，见 docs/24 §3 决策 1）。
 */
@Entity(tableName = "plan", indices = [Index(value = ["sessionId"])])
data class PlanEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sheetId: String,
    val title: String,
    /** `[{"id":"ps_1","order":1,"heading":"…","body":"…","sources":["m_1"]}]` */
    val sectionsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
