package com.askfirst.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scene")
data class SceneEntity(
    @PrimaryKey val id: String,
    val name: String,
    val focus: String,
    val builtin: Boolean,
    /** M5 用；M1 可为 null */
    val planSkeletonJson: String?,
    val createdAt: Long,
    val updatedAt: Long,
)