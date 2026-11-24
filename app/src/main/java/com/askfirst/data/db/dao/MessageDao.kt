package com.askfirst.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.askfirst.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(entity: MessageEntity)

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun ofSession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM message WHERE sessionId = :sessionId")
    suspend fun countOf(sessionId: String): Int
}