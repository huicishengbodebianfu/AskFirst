package com.askfirst.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 引用关系（M4，库 v2）：sessionId 引用了 sheetId（被引的是「那份旧确认单」，
 * 属于另一个会话）。一个会话最多 3 行（CitationPolicy 双拦，不在库里约束）。
 */
@Entity(tableName = "citation", indices = [Index(value = ["sessionId"])])
data class CitationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sheetId: String,
    val createdAt: Long,
)
