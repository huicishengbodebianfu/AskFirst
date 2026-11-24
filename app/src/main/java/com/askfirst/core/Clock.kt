package com.askfirst.core

/** 时间来源。禁止在各处直接调用 System.currentTimeMillis()——单测要能固定时间。 */
fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}