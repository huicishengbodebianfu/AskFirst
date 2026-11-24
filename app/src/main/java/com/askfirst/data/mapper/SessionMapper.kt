package com.askfirst.data.mapper

import com.askfirst.core.AppJson
import com.askfirst.data.db.SessionHistoryRow
import com.askfirst.data.db.entity.MessageEntity
import com.askfirst.data.db.entity.SessionEntity
import com.askfirst.domain.model.AskSession
import com.askfirst.domain.model.EngineQuestion
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.model.HistoryEntry
import com.askfirst.domain.model.SessionStatus
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val questionList = ListSerializer(EngineQuestion.serializer())
private val stringList = ListSerializer(String.serializer())

fun SessionEntity.toDomain(): AskSession = AskSession(
    id = id,
    sceneId = sceneId,
    intent = intent,
    status = SessionStatus.fromDb(status),
    state = EngineState.fromDb(state),
    summary = summary,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AskSession.toEntity(): SessionEntity = SessionEntity(
    id = id,
    sceneId = sceneId,
    intent = intent,
    status = status.db,
    state = state.api,
    summary = summary,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun MessageEntity.toDomain(): Turn = Turn(
    id = id,
    sessionId = sessionId,
    role = TurnRole.fromDb(role),
    text = text,
    questions = questionsJson
        ?.let { runCatching { AppJson.decodeFromString(questionList, it) }.getOrNull() }
        .orEmpty(),
    covered = coveredJson
        ?.let { runCatching { AppJson.decodeFromString(stringList, it) }.getOrNull() }
        ?.toSet()
        .orEmpty(),
    stateAfter = stateAfter?.let { runCatching { EngineState.fromDb(it) }.getOrNull() },
    createdAt = createdAt,
)

fun Turn.toEntity(): MessageEntity = MessageEntity(
    id = id,
    sessionId = sessionId,
    role = role.db,
    text = text,
    questionsJson = if (questions.isEmpty()) null else AppJson.encodeToString(questionList, questions),
    coveredJson = if (covered.isEmpty()) null else AppJson.encodeToString(stringList, covered.toList()),
    stateAfter = stateAfter?.api,
    createdAt = createdAt,
)

/** M2 历史列表行 → 领域模型。status/state 的 fromDb 容忍不了脏数据，历史列表必须更宽容（旧库升级场景）。 */
fun SessionHistoryRow.toDomain(): HistoryEntry = HistoryEntry(
    id = id,
    sceneId = sceneId,
    sceneName = sceneName,
    intent = intent,
    summary = summary,
    status = runCatching { SessionStatus.fromDb(status) }.getOrDefault(SessionStatus.OPEN),
    state = runCatching { EngineState.fromDb(state) }.getOrDefault(EngineState.GATHERING),
    updatedAt = updatedAt,
    turnCount = turnCount,
    itemCount = itemCount,
)