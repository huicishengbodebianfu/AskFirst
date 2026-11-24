package com.askfirst.domain.engine

import com.askfirst.core.AppError
import com.askfirst.core.AppResult
import com.askfirst.domain.model.Question
import com.askfirst.domain.model.QuestionKind
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.SessionStatus
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import com.askfirst.remote.ChatRequest
import com.askfirst.remote.LlmConfig
import com.askfirst.remote.LlmEvent
import com.askfirst.remote.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** S4 的测试夹具：一套最小场景 + 一条可控的假 provider。 */
object Fixtures {

    const val SCENE_ID = "scene_tech_arch"

    fun question(
        id: String,
        order: Int,
        text: String,
        why: String = "因为要问清楚",
        required: Boolean = false,
        trigger: String? = null,
    ) = Question(
        id = id,
        sceneId = SCENE_ID,
        order = order,
        text = text,
        why = why,
        required = required,
        kind = QuestionKind.OPEN,
        trigger = trigger,
    )

    val techScene = Scene(
        id = SCENE_ID,
        name = "技术方案 · 架构选型",
        focus = "把模糊的技术想法逼到能开工",
        builtin = true,
        questions = listOf(
            question("tech_q1", 1, "这次要解决什么问题？", "先钉住痛点", required = true),
            question("tech_q2", 2, "边界条件哪条是硬的？", "边界决定候选集", required = true),
            question("tech_q3", 3, "候选方案有哪些？", "对比才有判据"),
            question("tech_q4", 4, "有没有人踩过这个坑？", "存量改造要先看先例", trigger = "迁移|重构"),
        ),
    )

    fun userTurn(id: String, text: String, at: Long = 1_000L) =
        Turn(id = id, sessionId = "s1", role = TurnRole.USER, text = text, createdAt = at)

    fun aiTurn(id: String, text: String, covered: Set<String> = emptySet(), at: Long = 2_000L) =
        Turn(
            id = id,
            sessionId = "s1",
            role = TurnRole.AI,
            text = text,
            covered = covered,
            createdAt = at,
        )

    fun context(
        intent: String = "把首屏从 4 秒降到 1 秒",
        covered: Set<String> = emptySet(),
        turns: List<Turn> = emptyList(),
        draft: Map<Int, List<String>> = emptyMap(),
        state: com.askfirst.domain.model.EngineState = com.askfirst.domain.model.EngineState.GATHERING,
    ) = com.askfirst.domain.prompt.EngineContext(
        scene = techScene,
        intent = intent,
        state = state,
        covered = covered,
        turns = turns,
        draftColumns = draft,
    )

    val sessionStatus = SessionStatus.OPEN
}

/**
 * 假 provider：按脚本吐字。`responses` 是一个响应列表，每次调用取下一个（用完取最后一个）。
 * 这样能测"第一次坏 JSON、第二次修好"的重试路径。
 */
class FakeLlmProvider(
    private val responses: List<FakeResponse>,
    private val failOnCall: AppError? = null,
) : LlmProvider {

    var calls = 0
        private set

    override suspend fun config(): AppResult<LlmConfig> =
        AppResult.Ok(LlmConfig(baseUrl = "http://fake", apiKey = "sk-fake", model = "fake-model"))

    override suspend fun ping(): AppResult<String> = AppResult.Ok("连接正常")

    override suspend fun listModels(): AppResult<List<String>> =
        AppResult.Ok(listOf("fake-model", "fake-model-mini"))

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val index = calls
        calls++
        if (failOnCall != null) {
            emit(LlmEvent.Failed(failOnCall))
            return@flow
        }
        val response = responses[minOf(index, responses.lastIndex)]
        response.chunks.forEach { emit(LlmEvent.Delta(it)) }
        emit(LlmEvent.Done(response.chunks.joinToString(""), "stop"))
    }
}

data class FakeResponse(val chunks: List<String>) {
    constructor(text: String, chunkSize: Int = 24) : this(text.chunked(chunkSize))
}

/** 永远不返回、也不报错的 provider：用来验"超时预算"真的生效。 */
class HangingProvider : LlmProvider {

    override suspend fun config(): AppResult<LlmConfig> =
        AppResult.Ok(LlmConfig(baseUrl = "http://hang", apiKey = "sk-x", model = "m"))

    override suspend fun ping(): AppResult<String> = AppResult.Ok("ok")

    override suspend fun listModels(): AppResult<List<String>> =
        AppResult.Err(AppError.Timeout(100))

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        kotlinx.coroutines.awaitCancellation()
    }
}