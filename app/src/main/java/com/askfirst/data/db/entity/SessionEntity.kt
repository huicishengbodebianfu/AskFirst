package com.askfirst.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "session", indices = [Index(value = ["status", "updatedAt"])])
data class SessionEntity(
    @PrimaryKey val id: String,
    val sceneId: String,
    val intent: String,
    /** open | done | draft */
    val status: String,
    /** gathering | needs_precision | blocked | ready */
    val state: String,
    val summary: String?,
    val createdAt: Long,
    val updatedAt: Long,
)