package com.askfirst.data.repo

import com.askfirst.data.db.dao.SessionDao
import com.askfirst.data.mapper.toDomain
import com.askfirst.domain.model.HistoryEntry
import com.askfirst.domain.model.HistoryFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * M2 历史：只管列表（按场景筛选 + 时间倒序 + 摘要）。
 * 详情回看复用 [com.askfirst.domain.usecase.AskFirstFacade.sessionView]（session+scene+turns+sheet 一次取全），
 * 与 docs/01 §7 的 HistoryRepository 接口相比是落地偏差：detail 放进门面，避免 ViewModel 直连仓库。
 */
class HistoryRepository(
    private val sessionDao: SessionDao,
) {
    fun observe(filter: HistoryFilter): Flow<List<HistoryEntry>> =
        if (filter.all) {
            sessionDao.observeHistory()
        } else {
            sessionDao.observeHistoryOf(filter.sceneId!!)
        }.map { rows -> rows.map { it.toDomain() } }
}
