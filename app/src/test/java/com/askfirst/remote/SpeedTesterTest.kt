package com.askfirst.remote

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.domain.engine.FakeLlmProvider
import com.askfirst.domain.engine.FakeResponse
import com.askfirst.domain.engine.HangingProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页「测一下速度」的逻辑（模型测速 + 超时/空输出的处理）。 */
class SpeedTesterTest {

    @Test
    fun `正常回答能给出首字与字数`() = runTest {
        val provider = FakeLlmProvider(listOf(FakeResponse("缓存让重复请求不再回源。")))
        val result = SpeedTester(provider).measure()
        assertTrue("应成功，实际 $result", result is AppResult.Ok)
        val value = (result as AppResult.Ok).value
        assertTrue("要有字数", value.chars > 0)
        assertTrue("首字延迟不小于 0", value.firstTokenMs >= 0)
        assertTrue("文案要能直接给人看", value.readable().contains("字/秒"))
    }

    @Test
    fun `模型只吐空内容时明确报错 而不是显示 0 字每秒`() = runTest {
        val provider = FakeLlmProvider(listOf(FakeResponse("")))
        val result = SpeedTester(provider).measure()
        assertTrue(result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.BadJson)
        assertTrue(error.message!!.contains("没吐出任何正文"))
    }

    @Test
    fun `模型卡住时按 30 秒预算超时`() = runTest {
        val result = SpeedTester(HangingProvider(), timeoutMs = 100).measure()
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.Timeout)
    }

    @Test
    fun `provider 失败时透传可读错误`() = runTest {
        val provider = FakeLlmProvider(
            listOf(FakeResponse("x")),
            failOnCall = AppError.RateLimited(retryAfterSec = 5),
        )
        val result = SpeedTester(provider).measure()
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.RateLimited)
    }
}