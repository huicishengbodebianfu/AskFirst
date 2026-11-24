package com.askfirst.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.askfirst.data.db.SceneSummaryRow
import com.askfirst.data.db.entity.QuestionEntity
import com.askfirst.data.db.entity.SceneEntity
import kotlinx.coroutines.flow.Flow

private const val SUMMARY_SELECT = """
    SELECT s.id AS id, s.name AS name, s.focus AS focus, s.builtin AS builtin,
           (SELECT COUNT(*) FROM question q WHERE q.sceneId = s.id) AS questionCount,
           (SELECT COUNT(*) FROM question q WHERE q.sceneId = s.id AND q.required = 1) AS requiredCount
    FROM scene s
"""

@Dao
interface SceneDao {

    @Query("$SUMMARY_SELECT ORDER BY s.builtin DESC, s.createdAt ASC")
    fun observeSummaries(): Flow<List<SceneSummaryRow>>

    @Query("$SUMMARY_SELECT ORDER BY s.builtin DESC, s.createdAt ASC")
    suspend fun summaries(): List<SceneSummaryRow>

    @Query("SELECT * FROM scene ORDER BY builtin DESC, createdAt ASC")
    fun observeAll(): Flow<List<SceneEntity>>

    @Query("SELECT * FROM scene WHERE id = :id")
    suspend fun byId(id: String): SceneEntity?

    @Query("SELECT * FROM question WHERE sceneId = :sceneId ORDER BY `order` ASC")
    suspend fun questionsOf(sceneId: String): List<QuestionEntity>

    @Query("SELECT COUNT(*) FROM scene")
    suspend fun sceneCount(): Int

    @Query("SELECT COUNT(*) FROM question")
    suspend fun questionCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScenes(items: List<SceneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuestions(items: List<QuestionEntity>)

    @Query("DELETE FROM question WHERE sceneId = :sceneId")
    suspend fun deleteQuestionsOf(sceneId: String)

    // ---------- M3：自建场景 ----------

    @Query("DELETE FROM scene WHERE id = :id")
    suspend fun deleteScene(id: String)

    /** 导入前的 id 撞车检查。 */
    @Query("SELECT id FROM scene")
    suspend fun allIds(): List<String>
}