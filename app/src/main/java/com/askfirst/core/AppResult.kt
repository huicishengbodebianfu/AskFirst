package com.askfirst.core

/**
 * 全工程统一的结果类型。所有可能失败的易用操作都返回它，而不是抛异常——
 * 抛异常在 UI 层最难处理，而这个 App 的失败面（网络 / 模型 / JSON）特别多。
 */
sealed interface AppResult<out T> {
    data class Ok<out T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}

fun <T> AppResult<T>.valueOrNull(): T? = when (this) {
    is AppResult.Ok -> value
    is AppResult.Err -> null
}

fun AppResult<*>.errorOrNull(): AppError? = (this as? AppResult.Err)?.error

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

inline fun <T> AppResult<T>.onOk(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Ok) block(value)
    return this
}

/**
 * 错误家族。message 是**给用户看的**中文文案（见 prompts/glossary_ban.md §3）：
 * 必须说清"发生了什么 + 下一步做什么"，禁止"操作失败"这类五无文案。
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 连接层失败：超时 / 拒连 / DNS / 断流 */
    class Network(val detail: String, cause: Throwable? = null) :
        AppError("连不上你填的地址：$detail。检查网络，或回设置看看地址对不对。", cause)

    /** 401/403：Key 不对 */
    class Auth(val detail: String) :
        AppError("模型服务不认这个 Key：$detail。可能是复制漏了，或者它过期了。")

    /** 429：限流 / 额度用完 */
    class RateLimited(val retryAfterSec: Int? = null) :
        AppError(
            if (retryAfterSec != null) "模型服务限流了，$retryAfterSec 秒后再试。" else "模型服务限流或额度用完了。过一会儿再试，或者换一个 Key。",
        )

    /** 其他 4xx/5xx */
    class Server(val code: Int, val detail: String) :
        AppError("模型服务返回 $code：$detail。这不是你能修的问题，稍后重试或换个服务。")

    /** 请求超时（整体预算用尽） */
    class Timeout(val elapsedMs: Long) :
        AppError("等了 ${elapsedMs / 1000} 秒没回音。可能是网络，也可能是模型太慢。")

    /** 模型回了非 JSON（原文保留，便于回看与重试） */
    class BadJson(val raw: String, val reason: String) :
        AppError("模型这次没按格式回：$reason。我已经把它的原话留下了，点重试再试一次。")

    /** 设置不完整 */
    class Config(val hint: String) : AppError("还差一步：$hint")

    /** 本地存储失败（数据库 / 文件） */
    class Storage(val detail: String, cause: Throwable? = null) :
        AppError("本地数据读写失败：$detail。", cause)

    /** 内置场景灌库失败 */
    class Seed(val detail: String, cause: Throwable? = null) :
        AppError("内置场景没读出来，App 现在不能开始：$detail。", cause)

    /** 没有网络（需求 §3.6.3 的网络预判） */
    class Offline : AppError("手机现在没有网络。可以先用手里的内容出单，或连上网络再试。")

    /** M3：场景文件导入失败（坏 JSON / 缺字段 / 校验不过） */
    class Import(val detail: String) :
        AppError("这份场景文件用不了：$detail。让对方重新导出一份，或者自己新建一套。")

    /** M3：场景编辑规则（内置场景只读、删不掉被引用的场景等），detail 已是完整中文文案 */
    class SceneRule(val detail: String) : AppError(detail)

    /**
     * M5：方案生成的前置条件 / 模型输出不适用，detail 已是完整中文文案。
     * 方案页没有「重试」按钮（只有生成 / 重新生成 / 接着写剩下的），所以不走 [BadJson] 那句通用文案。
     */
    class PlanRule(val detail: String) : AppError(detail)

    /**
     * M6：调研的前置条件 / 取证不成立（没网、没关键词、一条证据都没拿到），detail 已是完整中文文案。
     * 调研页的按钮是「再查一次 / 拿去桌面端查」，跟提问流的「重试」不是一回事。
     */
    class ResearchRule(val detail: String) : AppError(detail)
}
