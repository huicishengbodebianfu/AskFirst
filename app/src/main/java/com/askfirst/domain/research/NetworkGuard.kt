package com.askfirst.domain.research

/**
 * 网络预判（方案 §3.6.3 第 6 条）：起调前先看一眼有没有网。
 * 没网就直接走「拿去桌面端查」，不浪费用户 60 秒去等三次超时重试。
 *
 * 为什么是一个接口而不是直接读 ConnectivityManager：这一层必须能在纯 JVM 里测，
 * 也方便真机验收时用固定实现（`{ true }`）。
 */
fun interface NetworkGuard {
    fun isOnline(): Boolean

    companion object {
        /** 不知道就当有网：宁可多试一次，也不许因为判错就把调研拦掉。 */
        val Always: NetworkGuard = NetworkGuard { true }
    }
}
