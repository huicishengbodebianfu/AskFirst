package com.askfirst.ui.screens.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** 历史列表的时间展示（纯函数，可单测）：今天给到分钟，更久给到天。 */
object HistoryFormat {

    private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm")
    private val monthDay = DateTimeFormatter.ofPattern("M月d日")
    private val yearMonthDay = DateTimeFormatter.ofPattern("yyyy年M月d日")

    fun relative(nowMs: Long, atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        return when (abs(now.toEpochDay() - at.toLocalDate().toEpochDay())) {
            0L -> "今天 " + at.format(timeOfDay)
            1L -> "昨天 " + at.format(timeOfDay)
            else -> if (at.year == now.year) at.format(monthDay) else at.format(yearMonthDay)
        }
    }

    /** 详情页头部用的完整时间。 */
    fun full(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(atMs).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
}
