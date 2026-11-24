package com.askfirst.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.askfirst.data.db.dao.CitationDao
import com.askfirst.data.db.dao.MessageDao
import com.askfirst.data.db.dao.PlanDao
import com.askfirst.data.db.dao.ResearchDao
import com.askfirst.data.db.dao.SceneDao
import com.askfirst.data.db.dao.SessionDao
import com.askfirst.data.db.dao.SheetDao
import com.askfirst.data.db.entity.CitationEntity
import com.askfirst.data.db.entity.MessageEntity
import com.askfirst.data.db.entity.PlanEntity
import com.askfirst.data.db.entity.QuestionEntity
import com.askfirst.data.db.entity.ResearchCacheEntity
import com.askfirst.data.db.entity.ResearchEntity
import com.askfirst.data.db.entity.SceneEntity
import com.askfirst.data.db.entity.SessionEntity
import com.askfirst.data.db.entity.SheetEntity
import com.askfirst.data.db.entity.SheetItemEntity
import com.askfirst.data.db.entity.VerdictEntity

/**
 * 版本策略见 docs/03 §7：
 * - exportSchema = true，schema 落在 app/schemas（入版本库，迁移可校验）
 * - 禁止 fallbackToDestructiveMigration（用户的思路数据不许因为升级被清空）
 */
@Database(
    entities = [
        SceneEntity::class,
        QuestionEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        SheetEntity::class,
        SheetItemEntity::class,
        CitationEntity::class,
        PlanEntity::class,
        ResearchEntity::class,
        VerdictEntity::class,
        ResearchCacheEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AskFirstDatabase : RoomDatabase() {
    abstract fun sceneDao(): SceneDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun sheetDao(): SheetDao
    abstract fun citationDao(): CitationDao
    abstract fun planDao(): PlanDao
    abstract fun researchDao(): ResearchDao

    companion object {
        /**
         * v1 → v2（M4）：加 `citation` 表。
         * docs/03 §7 原计划 v2 = M2 `lastReadAt`，M2 实际没用（历史列表用 updatedAt 排序），
         * 不再补空列（施工清单 22 §3 决策 7）。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `citation` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`sheetId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_citation_sessionId` ON `citation` (`sessionId`)")
            }
        }

        /**
         * v2 → v3（M5）：加 `plan` 表（一场会话一份方案）。段落整份存 JSON，
         * 和 `scene.planSkeletonJson` 一个套路——段落是渲染产物，拆表没有查询收益。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`sheetId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`sectionsJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_sessionId` ON `plan` (`sessionId`)")
            }
        }

        /**
         * v3 → v4（M6）：加 `research` / `verdict` / `research_cache` 三张表。
         *
         * **`verdict.appliedTo` 的 CHECK 是这条里程碑的铁律在数据库层的落点**：
         * 证据只许进第 2 栏（约束与前提）、第 5 栏（还没想清楚的问题）或附录，
         * 第 3 栏（选定方案）连 SQL 都写不进去（docs/26 §3.1）。
         * 注意：Room 的实体声明表达不了 CHECK，所以这层只在手写迁移里；
         * 以后如果重建这张表，必须把它一起带上。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `research` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `sheetId` TEXT NOT NULL, " +
                        "`planId` TEXT, `title` TEXT NOT NULL, " +
                        "`questionsJson` TEXT NOT NULL, `evidenceJson` TEXT NOT NULL, " +
                        "`failuresJson` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`elapsedMs` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                        "`authenticated` INTEGER NOT NULL, `recheckedJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_research_sessionId` ON `research` (`sessionId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `verdict` (" +
                        "`id` TEXT NOT NULL, `researchId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`questionIdx` INTEGER NOT NULL, `judgement` TEXT NOT NULL, " +
                        "`rationale` TEXT NOT NULL, `quotedText` TEXT NOT NULL, " +
                        "`quotedUrl` TEXT NOT NULL, " +
                        "`appliedTo` TEXT NOT NULL CHECK(`appliedTo` IN ('col2','col5','appendix')), " +
                        "`evidenceIdsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`researchId`) REFERENCES `research`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_verdict_researchId` ON `verdict` (`researchId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `research_cache` (" +
                        "`url` TEXT NOT NULL, `body` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`url`))",
                )
            }
        }
    }
}

