package com.askfirst.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 全局 Json 配置：LLM 输出必然脏（多余字段、漏字段、字符串化的 null、单引号）。
 * 容错在这里开，格式语义在 TurnParser / SheetParser 里校验（docs/04 §6）。
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

private val stringListSerializer = ListSerializer(String.serializer())

/** 存库用：把 id 列表写成 JSON 数组字符串。 */
fun List<String>.encodeJsonArray(): String = AppJson.encodeToString(stringListSerializer, this)

/** 读库用：坏数据返回空列表（读库不该炸，写库才该校验）。 */
fun String?.decodeJsonStringList(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return runCatching { AppJson.decodeFromString(stringListSerializer, this) }.getOrDefault(emptyList())
}