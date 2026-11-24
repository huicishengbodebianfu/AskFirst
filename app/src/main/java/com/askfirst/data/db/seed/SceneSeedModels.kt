package com.askfirst.data.db.seed

import kotlinx.serialization.Serializable

/**
 * 场景 JSON 的磁盘格式。它同时是 M3「场景导入/导出」的交换格式，
 * 所以字段名要与 docs/03 §5 的示例保持一致。
 */
@Serializable
data class SceneSeed(
    val schemaVersion: Int = 1,
    /**
     * 场景标识。**必须有默认值**：没有默认值时，缺 `id` 的文件在 `decodeFromString` 就抛
     * `MissingFieldException`，`SceneJsonCodec` 里那句「缺了场景标识（id），让对方用导出按钮重新发一份」
     * 永远走不到，亲友看到的是 kotlinx-serialization 的英文异常（docs/28 P2-14）。
     */
    val id: String = "",
    val name: String,
    val focus: String,
    val planSkeleton: List<String> = emptyList(),
    val questions: List<QuestionSeed> = emptyList(),
)

@Serializable
data class QuestionSeed(
    val id: String,
    val order: Int,
    val text: String,
    val why: String = "",
    val required: Boolean = false,
    val kind: String = "open",
    val trigger: String? = null,
)

/** 内置场景资产清单（按序灌库，id 固定，便于跨版本覆盖升级）。 */
object BuiltinSceneAssets {
    val files = listOf(
        "scenes/tech_arch.json",
        "scenes/kickoff.json",
        "scenes/retro.json",
    )
}