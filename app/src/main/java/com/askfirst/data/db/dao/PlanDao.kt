package com.askfirst.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.askfirst.data.db.entity.PlanEntity

@Dao
interface PlanDao {

    @Query("SELECT * FROM plan WHERE sessionId = :sessionId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun bySession(sessionId: String): PlanEntity?

    @Query("SELECT * FROM plan WHERE id = :id")
    suspend fun byId(id: String): PlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlanEntity)

    @Query("DELETE FROM plan WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM plan")
    suspend fun count(): Int
}
