package com.askfirst.data.mapper

import com.askfirst.core.AppJson
import com.askfirst.data.db.entity.ResearchEntity
import com.askfirst.data.db.entity.VerdictEntity
import com.askfirst.domain.model.Evidence
import com.askfirst.domain.model.EvidenceKind
import com.askfirst.domain.model.Judgement
import com.askfirst.domain.model.AppliedTo
import com.askfirst.domain.model.Research
import com.askfirst.domain.model.ResearchFailure
import com.askfirst.domain.model.ResearchQuestion
import com.askfirst.domain.model.ResearchSource
import com.askfirst.domain.model.ResearchStatus
import com.askfirst.domain.model.Verdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 调研 ↔ JSON（存 research 的四个 JSON 列与 verdict.evidenceIdsJson）。
 *
 * 解码失败一律当空：调研是「证据的渲染产物」，重查一次就有；
 * 宁可少显示，也不许拿半截 JSON 崩掉历史页/报告页（同 PlanMapper 的取舍）。
 */
@Serializable
internal data class ResearchQuestionJson(
    val idx: Int = 0,
    val text: String = "",
    val keywords: List<String> = emptyList(),
    val why: String = "",
)

@Serializable
internal data class EvidenceJson(
    val id: String = "",
    val questionIdx: Int = 0,
    val kind: String = "repo",
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val repo: String? = null,
    val stars: Int = 0,
    val pushed: String? = null,
)

@Serializable
internal data class FailureJson(
    val questionIdx: Int = 0,
    val source: String = "",
    val reason: String = "",
)

private val questionsSerializer = ListSerializer(ResearchQuestionJson.serializer())
private val evidenceSerializer = ListSerializer(EvidenceJson.serializer())
private val failuresSerializer = ListSerializer(FailureJson.serializer())
private val stringListSerializer = ListSerializer(String.serializer())
private val intListSerializer = ListSerializer(Int.serializer())

internal fun List<ResearchQuestion>.questionsToJson(): String =
    AppJson.encodeToString(questionsSerializer, map {
        ResearchQuestionJson(idx = it.idx, text = it.text, keywords = it.keywords, why = it.why)
    })

internal fun questionsFromJson(raw: String): List<ResearchQuestion> =
    runCatching { AppJson.decodeFromString(questionsSerializer, raw) }.getOrElse { emptyList() }
        .map { ResearchQuestion(idx = it.idx, text = it.text, keywords = it.keywords, why = it.why) }

internal fun List<Evidence>.evidenceToJson(): String =
    AppJson.encodeToString(evidenceSerializer, map {
        EvidenceJson(
            id = it.id,
            questionIdx = it.questionIdx,
            kind = it.kind.db,
            title = it.title,
            url = it.url,
            snippet = it.snippet,
            repo = it.repoFullName,
            stars = it.stars,
            pushed = it.pushedAt,
        )
    })

internal fun evidenceFromJson(raw: String): List<Evidence> =
    runCatching { AppJson.decodeFromString(evidenceSerializer, raw) }.getOrElse { emptyList() }
        .map {
            Evidence(
                id = it.id,
                questionIdx = it.questionIdx,
                kind = EvidenceKind.fromDb(it.kind) ?: EvidenceKind.REPO,
                title = it.title,
                url = it.url,
                snippet = it.snippet,
                repoFullName = it.repo,
                stars = it.stars,
                pushedAt = it.pushed,
            )
        }

internal fun List<ResearchFailure>.failuresToJson(): String =
    AppJson.encodeToString(failuresSerializer, map {
        FailureJson(questionIdx = it.questionIdx, source = it.source, reason = it.reason)
    })

internal fun failuresFromJson(raw: String): List<ResearchFailure> =
    runCatching { AppJson.decodeFromString(failuresSerializer, raw) }.getOrElse { emptyList() }
        .map { ResearchFailure(questionIdx = it.questionIdx, source = it.source, reason = it.reason) }

internal fun List<Int>.intsToJson(): String = AppJson.encodeToString(intListSerializer, this)

internal fun intsFromJson(raw: String): List<Int> =
    runCatching { AppJson.decodeFromString(intListSerializer, raw) }.getOrElse { emptyList() }

internal fun List<String>.stringsToJson(): String = AppJson.encodeToString(stringListSerializer, this)

internal fun stringsFromJson(raw: String): List<String> =
    runCatching { AppJson.decodeFromString(stringListSerializer, raw) }.getOrElse { emptyList() }

fun VerdictEntity.toDomain(): Verdict = Verdict(
    id = id,
    questionIdx = questionIdx,
    judgement = Judgement.fromDb(judgement) ?: Judgement.UNGATHERED,
    rationale = rationale,
    quotedText = quotedText,
    quotedUrl = quotedUrl,
    appliedTo = AppliedTo.fromDb(appliedTo),
    evidenceIds = stringsFromJson(evidenceIdsJson),
)

fun Research.toEntity(): ResearchEntity = ResearchEntity(
    id = id,
    sessionId = sessionId,
    sheetId = sheetId,
    planId = planId,
    title = title,
    questionsJson = questions.questionsToJson(),
    evidenceJson = evidence.evidenceToJson(),
    failuresJson = failures.failuresToJson(),
    status = status.db,
    elapsedMs = elapsedMs,
    source = source.db,
    authenticated = authenticated,
    recheckedJson = recheckedQuestions.intsToJson(),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ResearchEntity.toDomain(verdicts: List<VerdictEntity>): Research = Research(
    id = id,
    sessionId = sessionId,
    sheetId = sheetId,
    planId = planId,
    title = title,
    questions = questionsFromJson(questionsJson),
    evidence = evidenceFromJson(evidenceJson),
    verdicts = verdicts.map { it.toDomain() },
    failures = failuresFromJson(failuresJson),
    status = ResearchStatus.fromDb(status),
    elapsedMs = elapsedMs,
    source = ResearchSource.fromDb(source),
    authenticated = authenticated,
    recheckedQuestions = intsFromJson(recheckedJson),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** 一条判定落库：researchId 由调用方给（同一份调研的所有判定共用一个）。 */
fun Verdict.toEntity(researchId: String, sessionId: String, createdAt: Long): VerdictEntity = VerdictEntity(
    id = id,
    researchId = researchId,
    sessionId = sessionId,
    questionIdx = questionIdx,
    judgement = judgement.db,
    rationale = rationale,
    quotedText = quotedText,
    quotedUrl = quotedUrl,
    appliedTo = appliedTo.db,
    evidenceIdsJson = evidenceIds.stringsToJson(),
    createdAt = createdAt,
)
