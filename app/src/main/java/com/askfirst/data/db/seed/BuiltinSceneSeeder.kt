package com.askfirst.data.db.seed

import android.content.Context
import com.askfirst.core.AppJson
import com.askfirst.core.AppResult
import com.askfirst.core.AppError
import com.askfirst.core.Clock
import com.askfirst.core.Logx
import com.askfirst.data.db.dao.SceneDao
import com.askfirst.data.db.entity.QuestionEntity
import com.askfirst.data.db.entity.SceneEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 内置场景灌库（幂等，见 docs/03 §8）：
 * - builtin 场景按 id 覆盖（M1 用户还不能改内置场景，直接覆盖）
 * - 用户自建场景（builtin=false）永不触碰
 * - asset 解析失败必须让启动流程知道（不许空列表静默进首页）
 */
class BuiltinSceneSeeder(
    private val context: Context,
    private val sceneDao: SceneDao,
    private val clock: Clock,
    private val log: Logx,
) {

    suspend fun seedIfNeeded(): AppResult<Int> = withContext(Dispatchers.IO) {
        val tag = "SceneSeed"
        try {
            var seeded = 0
            BuiltinSceneAssets.files.forEach { path ->
                val raw = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val seed = AppJson.decodeFromString(SceneSeed.serializer(), raw)
                writeScene(seed)
                seeded++
                log.d(tag, "seeded ${seed.id} (${seed.questions.size} questions)")
            }
            AppResult.Ok(seeded)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            log.e(tag, "seed failed", t)
            AppResult.Err(AppError.Seed(t.message ?: t::class.java.simpleName, t))
        }
    }

    private suspend fun writeScene(seed: SceneSeed) {
        val now = clock.now()
        sceneDao.upsertScenes(
            listOf(
                SceneEntity(
                    id = seed.id,
                    name = seed.name,
                    focus = seed.focus,
                    builtin = true,
                    planSkeletonJson = if (seed.planSkeleton.isEmpty()) {
                        null
                    } else {
                        AppJson.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(String.serializer()),
                            seed.planSkeleton,
                        )
                    },
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        // 先删后插：问题顺序/措辞随版本变化，覆盖式更新最省心
        sceneDao.deleteQuestionsOf(seed.id)
        sceneDao.upsertQuestions(
            seed.questions.map { q ->
                QuestionEntity(
                    id = q.id,
                    sceneId = seed.id,
                    order = q.order,
                    text = q.text,
                    why = q.why,
                    required = q.required,
                    kind = q.kind,
                    trigger = q.trigger?.takeIf { it.isNotBlank() },
                    createdAt = now,
                )
            },
        )
    }
}