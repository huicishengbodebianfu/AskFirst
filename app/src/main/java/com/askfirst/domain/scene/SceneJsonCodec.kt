package com.askfirst.domain.scene

import com.askfirst.core.AppError
import com.askfirst.core.AppJson
import com.askfirst.core.AppResult
import com.askfirst.data.db.seed.QuestionSeed
import com.askfirst.data.db.seed.SceneSeed
import com.askfirst.domain.model.QuestionDraft
import com.askfirst.domain.model.Question
import com.askfirst.domain.model.QuestionKind
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.SceneDraft
import kotlinx.serialization.json.Json

/**
 * M3 场景 JSON 编解码：与 `assets` 的 `scenes` 目录下那些出厂场景文件同一格式（SceneSeed 是交换格式）。
 * 解码侧容错策略与引擎 JSON 一致（`AppJson`：未知字段忽略、宽松引号），
 * 但语义校验从严——导进来的场景是给真模型用的，坏题干会污染整场提问。
 */
object SceneJsonCodec {

    /** 导出用：同 AppJson 的语义，加缩进（发微信能直接看）。 */
    private val prettyJson: Json = Json(from = AppJson) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun decode(text: String): AppResult<SceneSeed> {
        val seed = try {
            AppJson.decodeFromString(SceneSeed.serializer(), text)
        } catch (t: Throwable) {
            return AppResult.Err(
                AppError.Import("这不是一份能读的场景文件（${t.message ?: "格式不对"}）"),
            )
        }
        // 交换格式兜底：id 缺失时给空串，让下面的校验用中文说清楚
        val draft = seed.toDraft()
        val issues = draft.validate()
        if (issues.isNotEmpty()) {
            return AppResult.Err(AppError.Import(issues.first()))
        }
        if (seed.id.isBlank()) {
            return AppResult.Err(AppError.Import("缺了场景标识（id），让对方用导出按钮重新发一份"))
        }
        return AppResult.Ok(seed)
    }

    /** 导出：字段顺序稳定 + 缩进，人能直接看懂、能发微信。 */
    fun encode(scene: Scene): String = prettyJson.encodeToString(scene.toSeed())

    /**
     * 导入撞车处理：id 已存在时换一个新 id（不改名字）。
     * 朋友间传场景最常见的失败就是两边的 id 一样。
     */
    fun resolveImportId(seedId: String, existingIds: Set<String>, mint: () -> String): String =
        if (seedId !in existingIds) seedId else mint()
}

/** 库里的场景 → 交换格式（题号按当前顺序重排 1..n）。 */
internal fun Scene.toSeed(): SceneSeed = SceneSeed(
    schemaVersion = 1,
    id = id,
    name = name,
    focus = focus,
    planSkeleton = planSkeleton,
    questions = questions.mapIndexed { index, q ->
        q.toSeed(index + 1)
    },
)

internal fun Question.toSeed(order: Int): QuestionSeed = QuestionSeed(
    id = id,
    order = order,
    text = text,
    why = why,
    required = required,
    kind = kind.db,
    trigger = trigger?.takeIf { it.isNotBlank() },
)

/** 交换格式 → 编辑草稿（校验与编辑器同一套）。 */
internal fun SceneSeed.toDraft(): SceneDraft = SceneDraft(
    name = name,
    focus = focus,
    questions = questions.map { q ->
        QuestionDraft(
            text = q.text,
            why = q.why,
            required = q.required,
            trigger = q.trigger,
        )
    },
)
