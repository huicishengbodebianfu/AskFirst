package com.askfirst.remote

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * OkHttp 客户端（方案 §3.6.3 的超时纪律）：
 * - connect 15s / read 90s：流式响应可能很慢，读超时必须比普通 API 宽
 * - callTimeout 不设（=0）：流式请求的总时长由上层（引擎 60s 预算 / 用户取消）控制
 * - 一律不自动重试：401/429 重试只会更快烧完额度（重试纪律在上层写）
 */
object HttpFactory {

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
}