package com.askfirst.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sheet", indices = [Index(value = ["sessionId", "createdAt"])])
data class SheetEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long,
)