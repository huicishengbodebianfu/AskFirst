package com.askfirst.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "question",
    foreignKeys = [
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["id"],
            childColumns = ["sceneId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sceneId", "order"])],
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val sceneId: String,
    @ColumnInfo(name = "order") val order: Int,
    val text: String,
    val why: String,
    val required: Boolean,
    /** open | choice */
    val kind: String = "open",
    /** 条件轮触发式，null = 无条件 */
    val trigger: String? = null,
    val createdAt: Long,
)