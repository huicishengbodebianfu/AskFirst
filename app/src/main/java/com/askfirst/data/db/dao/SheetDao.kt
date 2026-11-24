package com.askfirst.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.askfirst.data.db.entity.SheetEntity
import com.askfirst.data.db.entity.SheetItemEntity

@Dao
interface SheetDao {

    @Insert
    suspend fun insertSheet(entity: SheetEntity)

    @Query("SELECT * FROM sheet WHERE id = :id")
    suspend fun sheetById(id: String): SheetEntity?

    @Query("SELECT * FROM sheet WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestSheetOf(sessionId: String): SheetEntity?

    @Query("SELECT * FROM sheet WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun sheetsOf(sessionId: String): List<SheetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<SheetItemEntity>)

    @Query("SELECT * FROM sheet_item WHERE sheetId = :sheetId ORDER BY col ASC, `order` ASC")
    suspend fun itemsOf(sheetId: String): List<SheetItemEntity>

    @Query("SELECT * FROM sheet_item WHERE sheetId = :sheetId AND col = :col ORDER BY `order` ASC")
    suspend fun itemsOfColumn(sheetId: String, col: Int): List<SheetItemEntity>

    @Query("SELECT * FROM sheet_item WHERE id = :id")
    suspend fun itemById(id: String): SheetItemEntity?

    @Query("UPDATE sheet_item SET text = :text, edited = 1 WHERE id = :id")
    suspend fun editItem(id: String, text: String)

    @Query("UPDATE sheet SET summary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun patchSummary(id: String, summary: String, now: Long)

    @Query("DELETE FROM sheet_item WHERE sheetId = :sheetId AND col = :col")
    suspend fun clearColumn(sheetId: String, col: Int)

    /** 重新出单时清掉"模型生成、用户没改过"的条目；编辑过的必须留着。 */
    @Query("DELETE FROM sheet_item WHERE sheetId = :sheetId AND edited = 0")
    suspend fun deleteUnedited(sheetId: String)
}