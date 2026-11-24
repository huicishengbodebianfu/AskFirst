package com.askfirst.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 检索缓存（M6，库 v4）：按 URL 缓存 24h。
 * 为什么值得一张表：GitHub 匿名 Search 只有 10 次/分钟，同一份确认单重跑调研不该再打网络
 * （方案 §3.6.3 第 5 条）。过期行在每次调研开始时顺手清掉。
 */
@Entity(tableName = "research_cache")
data class ResearchCacheEntity(
    @PrimaryKey val url: String,
    val body: String,
    val fetchedAt: Long,
)
