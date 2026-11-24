package com.askfirst.data.mapper

import com.askfirst.core.AppJson
import com.askfirst.data.db.SceneSummaryRow
import com.askfirst.data.db.entity.QuestionEntity
import com.askfirst.data.db.entity.SceneEntity
import com.askfirst.domain.model.Question
import com.askfirst.domain.model.QuestionKind
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.SceneSummary
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val stringList = ListSerializer(String.serializer())

fun QuestionEntity.toDomain(): Question = Question(
    id = id,
    sceneId = sceneId,
    order = order,
    text = text,
    why = why,
    required = required,
    kind = QuestionKind.fromDb(kind),
    trigger = trigger?.takeIf { it.isNotBlank() },
)

fun SceneEntity.toDomain(questions: List<QuestionEntity>): Scene = Scene(
    id = id,
    name = name,
    focus = focus,
    builtin = builtin,
    planSkeleton = planSkeletonJson
        ?.let { runCatching { AppJson.decodeFromString(stringList, it) }.getOrNull() }
        .orEmpty(),
    questions = questions.sortedBy { it.order }.map { it.toDomain() },
)

fun Scene.toEntities(now: Long): Pair<SceneEntity, List<QuestionEntity>> {
    val sceneEntity = SceneEntity(
        id = id,
        name = name,
        focus = focus,
        builtin = builtin,
        planSkeletonJson = if (planSkeleton.isEmpty()) null else AppJson.encodeToString(stringList, planSkeleton),
        createdAt = now,
        updatedAt = now,
    )
    val questionEntities = questions.map { q ->
        QuestionEntity(
            id = q.id,
            sceneId = id,
            order = q.order,
            text = q.text,
            why = q.why,
            required = q.required,
            kind = q.kind.db,
            trigger = q.trigger,
            createdAt = now,
        )
    }
    return sceneEntity to questionEntities
}

fun SceneSummaryRow.toDomain(): SceneSummary = SceneSummary(
    id = id,
    name = name,
    focus = focus,
    builtin = builtin,
    questionCount = questionCount,
    requiredCount = requiredCount,
)