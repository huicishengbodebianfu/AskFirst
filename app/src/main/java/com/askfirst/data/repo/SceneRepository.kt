package com.askfirst.data.repo

import androidx.room.withTransaction
import com.askfirst.core.AppError
import com.askfirst.core.AppJson
import com.askfirst.core.AppResult
import com.askfirst.core.Clock
import com.askfirst.core.IdPrefix
import com.askfirst.core.IdSource
import com.askfirst.data.db.AskFirstDatabase
import com.askfirst.data.db.dao.SceneDao
import com.askfirst.data.db.entity.QuestionEntity
import com.askfirst.data.db.entity.SceneEntity
import com.askfirst.data.mapper.toDomain
import com.askfirst.domain.model.QuestionKind
import com.askfirst.domain.model.Scene
import com.askfirst.domain.model.SceneDraft
import com.askfirst.domain.model.SceneSummary
import com.askfirst.domain.model.toDraft
import com.askfirst.domain.scene.SceneJsonCodec
import com.askfirst.domain.scene.toDraft as seedToDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 场景仓库。M1 只有读；M3 加上自建场景的增删改 + JSON 导入导出。
 * 规则（docs/20 §3）：内置场景（builtin=true）只读——启动时种子会按 id 覆盖它们；
 * 自建场景随便改。写入沿用 seeder 的「先删后插」。
 */
class SceneRepository(
    private val db: AskFirstDatabase,
    private val dao: SceneDao,
    private val ids: IdSource,
    private val clock: Clock,
) {

    fun observeSummaries(): Flow<List<SceneSummary>> =
        dao.observeSummaries().map { rows -> rows.map { it.toDomain() } }

    suspend fun summaries(): List<SceneSummary> = dao.summaries().map { it.toDomain() }

    suspend fun scene(id: String): Scene? {
        val entity = dao.byId(id) ?: return null
        return entity.toDomain(dao.questionsOf(id))
    }

    /** S1 自检用：库里到底灌进来几条。 */
    suspend fun counts(): DatabaseCounts = DatabaseCounts(
        scenes = dao.sceneCount(),
        questions = dao.questionCount(),
    )

    // ---------- M3：自建场景 ----------

    /** 新建自建场景（编辑器保存前已校验过，这里再拦一道脏数据）。 */
    suspend fun create(draft: SceneDraft): AppResult<Scene> {
        val issues = draft.validate()
        if (issues.isNotEmpty()) return AppResult.Err(AppError.SceneRule(issues.first()))
        return persistNew(id = ids.newId(IdPrefix.SCENE), draft = draft)
    }

    /** 「复制为自建」：内置场景整体拷一份（planSkeleton 一起带走），新 id 可编辑。 */
    suspend fun duplicate(sceneId: String): AppResult<Scene> {
        val source = scene(sceneId) ?: return AppResult.Err(missingScene())
        return persistNew(id = ids.newId(IdPrefix.SCENE), draft = source.toDraft(), planSkeleton = source.planSkeleton)
    }

    /** 编辑自建场景。内置场景拒绝（会被下一次启动的种子覆盖，改了必丢）。 */
    suspend fun update(sceneId: String, draft: SceneDraft): AppResult<Scene> {
        val existing = dao.byId(sceneId) ?: return AppResult.Err(missingScene())
        if (existing.builtin) {
            return AppResult.Err(
                AppError.SceneRule("内置场景改了会被升级覆盖。先用「复制为自建」拷一份，再改那份。"),
            )
        }
        val issues = draft.validate()
        if (issues.isNotEmpty()) return AppResult.Err(AppError.SceneRule(issues.first()))
        write(sceneId, draft, builtin = false, createdAt = existing.createdAt, planSkeletonJson = existing.planSkeletonJson)
        return AppResult.Ok(scene(sceneId) ?: return AppResult.Err(missingScene()))
    }

    suspend fun delete(sceneId: String): AppResult<Unit> {
        val existing = dao.byId(sceneId) ?: return AppResult.Err(missingScene())
        if (existing.builtin) {
            return AppResult.Err(
                AppError.SceneRule("内置场景是出厂带的，删不掉。不想看到就别选它。"),
            )
        }
        db.withTransaction {
            dao.deleteQuestionsOf(sceneId)
            dao.deleteScene(sceneId)
        }
        return AppResult.Ok(Unit)
    }

    /** 导入：容错解析 + 校验 + id 撞车自动换新 id，落库永远是自建。 */
    suspend fun importScene(text: String): AppResult<Scene> {
        val seed = when (val decoded = SceneJsonCodec.decode(text)) {
            is AppResult.Ok -> decoded.value
            is AppResult.Err -> return decoded
        }
        val existingIds = dao.allIds().toSet()
        val id = SceneJsonCodec.resolveImportId(seed.id, existingIds) { ids.newId(IdPrefix.SCENE) }
        write(
            sceneId = id,
            draft = seed.seedToDraft(),
            builtin = false,
            createdAt = null,
            planSkeletonJson = planSkeletonJsonOf(seed.planSkeleton),
        )
        return AppResult.Ok(scene(id) ?: return AppResult.Err(missingScene()))
    }

    /** 导出正文：库里场景 → 与 assets 同格式的 JSON（题目按当前顺序 1..n 重排）。 */
    fun exportJson(scene: Scene): String = SceneJsonCodec.encode(scene)

    // ---------- 内部 ----------

    private suspend fun persistNew(
        id: String,
        draft: SceneDraft,
        planSkeleton: List<String> = emptyList(),
    ): AppResult<Scene> {
        write(sceneId = id, draft = draft, builtin = false, createdAt = null, planSkeletonJson = planSkeletonJsonOf(planSkeleton))
        return AppResult.Ok(scene(id) ?: return AppResult.Err(missingScene()))
    }

    private suspend fun write(
        sceneId: String,
        draft: SceneDraft,
        builtin: Boolean,
        createdAt: Long?,
        planSkeletonJson: String?,
    ) {
        val now = clock.now()
        // 旧问题先读出来：要按位置+题干复用它们的 id（见下）
        val existing = dao.questionsOf(sceneId)
        val questions = draft.questions.mapIndexed { index, q ->
            val text = q.text.trim()
            val why = q.why.trim()
            val required = q.required
            val trigger = q.trigger?.takeIf { it.isNotBlank() }
            QuestionEntity(
                // 复用旧 id：见 QuestionIdReuse 的说明（docs/28 P1-3）
                id = QuestionIdReuse.idOf(
                    order = index + 1,
                    text = text,
                    why = why,
                    required = required,
                    trigger = trigger,
                    existing = existing,
                ) { ids.newId(IdPrefix.QUESTION) },
                sceneId = sceneId,
                order = index + 1,
                text = text,
                why = why,
                required = required,
                kind = QuestionKind.OPEN.db,
                trigger = trigger,
                createdAt = now,
            )
        }
        // 删 + 写必须在一个事务里：中途被取消（用户退出编辑器）会留下「零问题的场景」，
        // 而空问题集会让 StateMachine 的 all{} 恒真、直接跳到 ready（docs/28 P2-17）。
        db.withTransaction {
            dao.upsertScenes(
                listOf(
                    SceneEntity(
                        id = sceneId,
                        name = draft.name.trim(),
                        focus = draft.focus.trim(),
                        builtin = builtin,
                        planSkeletonJson = planSkeletonJson,
                        createdAt = createdAt ?: now,
                        updatedAt = now,
                    ),
                ),
            )
            // 先删后插：顺序/措辞每次保存都重写（seeder 同款策略）
            dao.deleteQuestionsOf(sceneId)
            dao.upsertQuestions(questions)
        }
    }

    private fun planSkeletonJsonOf(skeleton: List<String>): String? =
        if (skeleton.isEmpty()) null else AppJson.encodeToString(ListSerializer(String.serializer()), skeleton)

    private fun missingScene(): AppError =
        AppError.Storage("这套场景不见了（可能刚被删掉）。回到首页刷新一下。")
}

data class DatabaseCounts(val scenes: Int, val questions: Int)
