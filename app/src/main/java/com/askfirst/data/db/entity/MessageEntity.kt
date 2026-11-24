package com.askfirst.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** user | ai */
    val role: String,
    val text: String,
    /** AI 本轮问题（JSON 数组），用户消息为 null */
    val questionsJson: String? = null,
    /** AI 声明的已覆盖问题 id（JSON 数组） */
    val coveredJson: String? = null,
    /** 这轮之后的状态，便于回放 */
    val stateAfter: String? = null,
    val createdAt: Long,
)