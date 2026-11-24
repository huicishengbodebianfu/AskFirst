package com.askfirst.core

/**
 * 日志抽象。domain / 引擎 / 解析层**不许**直接依赖 android.util.Log，否则纯 JVM 单测跑不动。
 * Android 侧由 AppContainer 注入 AndroidLogx；单测注入 NoopLogx。
 */
interface Logx {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

object NoopLogx : Logx {
    override fun d(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}