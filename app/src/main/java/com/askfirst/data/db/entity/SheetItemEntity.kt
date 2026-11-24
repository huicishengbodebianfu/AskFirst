package com.askfirst.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sheet_item",
    foreignKeys = [
        ForeignKey(
            entity = SheetEntity::class,
            parentColumns = ["id"],
            childColumns = ["sheetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sheetId", "col", "order"])],
)
data class SheetItemEntity(
    @PrimaryKey val id: String,
    val sheetId: String,
    /** 1..5 */
    val col: Int,
    @ColumnInfo(name = "order") val order: Int,
    val text: String,
    /** JSON 数组，至少一条（D4 不编造的执行层保证） */
    val sourceMessageIds: String,
    val edited: Boolean,
    val createdAt: Long,
)