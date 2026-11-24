package com.askfirst.core

import android.util.Log

/**
 * Android 侧日志实现。**只在 Android 运行时加载**（纯 JVM 单测不要碰它，否则 Log 类缺失）。
 * 亲友闪退要能拿到日志（方案 §七.3）：M1 后续在 S7 加文件落地。
 */
object AndroidLogx : Logx {
    private const val MAX = 1200

    override fun d(tag: String, message: String) {
        Log.d(tag, clip(message))
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, clip(message), throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, clip(message), throwable)
    }

    private fun clip(message: String): String =
        if (message.length <= MAX) message else message.take(MAX) + "…(truncated)"
}