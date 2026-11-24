package com.askfirst.data.repo

import com.askfirst.core.Clock
import com.askfirst.core.IdPrefix
import com.askfirst.core.IdSource
import com.askfirst.core.Text
import com.askfirst.data.db.dao.MessageDao
import com.askfirst.data.db.dao.SessionDao
import com.askfirst.data.mapper.toDomain
import com.askfirst.data.mapper.toEntity
import com.askfirst.domain.model.AskSession
import com.askfirst.domain.model.EngineQuestion
import com.askfirst.domain.model.EngineState
import com.askfirst.domain.model.SessionStatus
import com.askfirst.domain.model.Turn
import com.askfirst.domain.model.TurnRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 会话仓库：**每一轮问答都落库**（方案 §四）。中途被杀进程也不许丢用户的回答。
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val clock: Clock,
    private val ids: IdSource,
) {

    suspend fun start(sceneId: String, intent: String): AskSession {
        val now = clock.now()
        val session = AskSession(
            id = ids.newId(IdPrefix.SESSION),
            sceneId = sceneId,
            intent = Text.normalize(intent),
            status = SessionStatus.OPEN,
            state = EngineState.GATHERING,
            summary = null,
            createdAt = now,
            updatedAt = now,
        )
        sessionDao.insert(session.toEntity())
        return session
    }

    suspend fun resumable(): AskSession? = sessionDao.resumable()?.toDomain()

    suspend fun byId(id: String): AskSession? = sessionDao.byId(id)?.toDomain()

    fun observeTurns(sessionId: String): Flow<List<Turn>> =
        messageDao.observeSession(sessionId).map { rows -> rows.map { it.toDomain() } }

    suspend fun transcript(sessionId: String): List<Turn> =
        messageDao.ofSession(sessionId).map { it.toDomain() }

    suspend fun appendUser(sessionId: String, text: String): Turn {
        val turn = Turn(
            id = ids.newId(IdPrefix.MESSAGE),
            sessionId = sessionId,
            role = TurnRole.USER,
            text = Text.normalize(text),
            createdAt = clock.now(),
        )
        messageDao.insert(turn.toEntity())
        sessionDao.patchState(sessionId, state = EngineState.GATHERING.api, status = SessionStatus.OPEN.db, now = clock.now())
        return turn
    }

    suspend fun appendAi(
        sessionId: String,
        text: String,
        questions: List<EngineQuestion>,
        covered: Set<String>,
        stateAfter: EngineState,
    ): Turn {
        val turn = Turn(
            id = ids.newId(IdPrefix.MESSAGE),
            sessionId = sessionId,
            role = TurnRole.AI,
            text = text,
            questions = questions,
            covered = covered,
            stateAfter = stateAfter,
            createdAt = clock.now(),
        )
        messageDao.insert(turn.toEntity())
        sessionDao.patchState(
            id = sessionId,
            state = stateAfter.api,
            status = SessionStatus.OPEN.db,
            now = clock.now(),
        )
        return turn
    }

    suspend fun patchState(sessionId: String, state: EngineState, status: SessionStatus) {
        sessionDao.patchState(sessionId, state.api, status.db, clock.now())
    }

    suspend fun patchSummary(sessionId: String, summary: String) {
        sessionDao.patchSummary(sessionId, summary, clock.now())
    }

    suspend fun markDone(sessionId: String) {
        sessionDao.patchState(sessionId, EngineState.READY.api, SessionStatus.DONE.db, clock.now())
    }

    /** M3：删除场景前的引用检查——用过这套场景的会话数。 */
    suspend fun countByScene(sceneId: String): Int = sessionDao.countByScene(sceneId)
}