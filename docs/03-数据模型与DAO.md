# AskFirst M1 数据模型与 DAO 契约

> 依据：`docs/spec/01_AskFirst-产品方案.md` §四（8 张表）+ `03_机制学习笔记.md` 的 12 条改动。
> 本文只落 **M1 需要的表**：`scene / question / session / message / sheet / sheet_item`。
> `citation / plan / research / evidence / verdict` 在 M2/M4/M5/M6 各自里程碑加表并走迁移。

---

## 0. M1 相对产品方案的 4 处落地改动（必须先认可，再写代码）

| # | 改动 | 原因（来自 03 笔记 / 施工现实） | 影响面 |
|---|---|---|---|
| **D1** | 五栏从 `sheet.col1..col5`（大文本）改成 `sheet` + `sheet_item`（条目行） | 改动 #6「确认单要随回答实时生长」；方案 §3.2 也要求「每栏是条目数组，不是大段散文」。条目行同时天然携带 `source_message_ids`，是 M5 段落溯源的地基 | 表结构、`SheetBuilder`、UI |
| **D2** | `session` 增 `state`（`gathering/needs_precision/blocked/ready`） | 改动 #3 四状态机；原方案的「覆盖判定」太弱 | 表结构、引擎、UI 仅内部使用 |
| **D3** | `question` 增 `trigger`（条件轮）与 `kind`（open/choice） | 改动 #12 条件轮；改动 #2「默认开放一问」需要区分题型 | 表结构、模板编辑器（M3） |
| **D4** | `sheet_item.source_message_ids` 必填（JSON 数组，至少 1 个） | 方案 §3.1 硬规则 3「不编造」；执行层可强制——没有出处的条目不允许入库 | 表结构、`SheetGrower` |

其余字段与方案一致。`sheet.summary` 保留（历史列表一句话摘要）。

---

## 1. 实体关系

```
scene 1 ── n question
scene 1 ── n session
session 1 ── n message
session 1 ── n sheet        (一份会话可因「重新出单」有多份，取最新)
sheet 1 ── n sheet_item    (col=1..5, order 栏内序号)
```

---

## 2. DDL（Room 生成的目标形态，供人工核对；不要手写建表）

```sql
CREATE TABLE scene (
  id TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  focus TEXT NOT NULL,
  builtin INTEGER NOT NULL,
  planSkeletonJson TEXT,          -- M5 用，M1 可为 NULL
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);

CREATE TABLE question (
  id TEXT NOT NULL PRIMARY KEY,
  sceneId TEXT NOT NULL REFERENCES scene(id) ON DELETE CASCADE,
  `order` INTEGER NOT NULL,
  text TEXT NOT NULL,
  why TEXT NOT NULL,
  required INTEGER NOT NULL,      -- 1 = 必问，AI 不许跳过
  kind TEXT NOT NULL DEFAULT 'open',       -- open | choice
  trigger TEXT,                   -- 条件轮：命中才问，NULL = 无条件
  createdAt INTEGER NOT NULL
);
CREATE INDEX index_question_sceneId_order ON question(sceneId, `order`);

CREATE TABLE session (
  id TEXT NOT NULL PRIMARY KEY,
  sceneId TEXT NOT NULL REFERENCES scene(id) ON DELETE RESTRICT,
  intent TEXT NOT NULL,
  status TEXT NOT NULL,           -- open | done | draft
  state TEXT NOT NULL,            -- gathering | needs_precision | blocked | ready
  summary TEXT,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);
CREATE INDEX index_session_status_updatedAt ON session(status, updatedAt);

CREATE TABLE message (
  id TEXT NOT NULL PRIMARY KEY,
  sessionId TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  role TEXT NOT NULL,             -- user | ai
  text TEXT NOT NULL,
  questionsJson TEXT,             -- AI 本轮问的问题（可空）
  coveredJson TEXT,               -- AI 声明的已覆盖问题 id（可空）
  stateAfter TEXT,                -- 这轮之后的状态（可空，便于回放）
  createdAt INTEGER NOT NULL
);
CREATE INDEX index_message_sessionId_createdAt ON message(sessionId, createdAt);

CREATE TABLE sheet (
  id TEXT NOT NULL PRIMARY KEY,
  sessionId TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  summary TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);
CREATE INDEX index_sheet_sessionId_createdAt ON sheet(sessionId, createdAt);

CREATE TABLE sheet_item (
  id TEXT NOT NULL PRIMARY KEY,
  sheetId TEXT NOT NULL REFERENCES sheet(id) ON DELETE CASCADE,
  col INTEGER NOT NULL,           -- 1..5
  `order` INTEGER NOT NULL,
  text TEXT NOT NULL,
  sourceMessageIds TEXT NOT NULL, -- JSON: ["m_12"]  ← D4：不许为空数组
  edited INTEGER NOT NULL,        -- 用户手改过
  createdAt INTEGER NOT NULL
);
CREATE INDEX index_sheet_item_sheetId_col_order ON sheet_item(sheetId, col, `order`);
```

**外键必须开**：`Room.databaseBuilder(...).addCallback(object : Callback() { override fun onOpen(db) { db.setForeignKeyConstraintsEnabled(true) } })`。

---

## 3. 实体代码（`data/db/entity/`，一文件一实体）

```kotlin
// SceneEntity.kt
@Entity(tableName = "scene")
data class SceneEntity(
    @PrimaryKey val id: String,
    val name: String,
    val focus: String,
    val builtin: Boolean,
    val planSkeletonJson: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

// QuestionEntity.kt
@Entity(
    tableName = "question",
    foreignKeys = [ForeignKey(
        entity = SceneEntity::class, parentColumns = ["id"], childColumns = ["sceneId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sceneId", "order"])],
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val sceneId: String,
    @ColumnInfo(name = "order") val order: Int,
    val text: String,
    val why: String,
    val required: Boolean,
    /** open | choice —— 改动 #2：默认开放式，选择题必须有理由 */
    val kind: String = "open",
    /** 条件轮触发式：如 "付款|付费|订阅"；null = 无条件 */
    val trigger: String? = null,
    val createdAt: Long,
)

// SessionEntity.kt
@Entity(tableName = "session", indices = [Index(value = ["status", "updatedAt"])])
data class SessionEntity(
    @PrimaryKey val id: String,
    val sceneId: String,
    val intent: String,
    /** open | done | draft */
    val status: String,
    /** gathering | needs_precision | blocked | ready —— 改动 #3 */
    val state: String,
    val summary: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

// MessageEntity.kt
@Entity(
    tableName = "message",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val questionsJson: String? = null,
    val coveredJson: String? = null,
    val stateAfter: String? = null,
    val createdAt: Long,
)

// SheetEntity.kt
@Entity(tableName = "sheet", indices = [Index(value = ["sessionId", "createdAt"])])
data class SheetEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long,
)

// SheetItemEntity.kt
@Entity(
    tableName = "sheet_item",
    foreignKeys = [ForeignKey(SheetEntity::class, ["id"], ["sheetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sheetId", "col", "order"])],
)
data class SheetItemEntity(
    @PrimaryKey val id: String,
    val sheetId: String,
    /** 1..5 */
    val col: Int,
    @ColumnInfo(name = "order") val order: Int,
    val text: String,
    /** JSON 数组，至少一条 —— D4 不编造的执行层保证 */
    val sourceMessageIds: String,
    val edited: Boolean,
    val createdAt: Long,
)
```

---

## 4. DAO 签名（`data/db/dao/`）

```kotlin
@Dao
interface SceneDao {
    @Query("SELECT * FROM scene ORDER BY builtin DESC, createdAt ASC")
    fun observeAll(): Flow<List<SceneEntity>>

    @Query("SELECT * FROM scene WHERE id = :id")
    suspend fun byId(id: String): SceneEntity?

    @Query("SELECT * FROM question WHERE sceneId = :sceneId ORDER BY `order` ASC")
    suspend fun questionsOf(sceneId: String): List<QuestionEntity>

    @Query("SELECT COUNT(*) FROM scene")
    suspend fun sceneCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScenes(items: List<SceneEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuestions(items: List<QuestionEntity>)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM session WHERE status = 'open' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun resumable(): SessionEntity?

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Insert
    suspend fun insert(entity: SessionEntity)

    @Query("UPDATE session SET state = :state, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun patchState(id: String, state: String, status: String, now: Long)

    @Query("UPDATE session SET summary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun patchSummary(id: String, summary: String, now: Long)
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(entity: MessageEntity)

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun ofSession(sessionId: String): List<MessageEntity>

    fun observeSession(sessionId: String): Flow<List<MessageEntity>>
}

@Dao
interface SheetDao {
    @Insert
    suspend fun insertSheet(entity: SheetEntity)

    @Query("SELECT * FROM sheet WHERE id = :id")
    suspend fun sheetById(id: String): SheetEntity?

    @Query("SELECT * FROM sheet WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestSheetOf(sessionId: String): SheetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<SheetItemEntity>)

    @Query("SELECT * FROM sheet_item WHERE sheetId = :sheetId ORDER BY col ASC, `order` ASC")
    suspend fun itemsOf(sheetId: String): List<SheetItemEntity>

    @Query("UPDATE sheet_item SET text = :text, edited = 1 WHERE id = :id")
    suspend fun editItem(id: String, text: String)

    @Transaction
    suspend fun replaceSheetWithItems(sheet: SheetEntity, items: List<SheetItemEntity>) {
        insertSheet(sheet)
        upsertItems(items)
    }
}
```

---

## 5. 内置场景种子数据

三套出厂场景以 **JSON asset** 保存（同时也是 M3「场景导入导出」的格式），
首次启动由 `BuiltinSceneSeeder` 灌库；`scene.id` 固定，便于跨版本升级时按 id 覆盖而不重复插入。

`app/src/main/assets/scenes/tech_arch.json`（同构，另两个文件命名 `kickoff.json` / `retro.json`）：

```json
{
  "schemaVersion": 1,
  "id": "scene_tech_arch",
  "name": "技术方案 · 架构选型",
  "focus": "把一个模糊的技术想法逼到能开工：候选方案、规模、退路",
  "planSkeleton": ["背景与问题", "目标与非目标", "约束", "方案对比表", "选定方案与设计要点", "迁移与回滚", "风险与待决问题", "排期里程碑"],
  "questions": [
    {
      "id": "tech_q1",
      "order": 1,
      "text": "这次要解决什么问题？如果不上这个方案，现在最痛的具体表现是什么？",
      "why": "先钉住痛点，后面的选型才有判据",
      "required": true,
      "kind": "open",
      "trigger": null
    }
  ]
}
```

### 三套场景的问题 id 分配（附录 A 共 28 题）

> 方案原文写"27 道"，实际列出来是 A1 9 + A2 10 + A3 9 = **28 道**。已按实数落地，`SceneSeedTest` 断言 28。

| 场景 | id 前缀 | 题数 | 必问（required=true） |
|---|---|---|---|
| 技术方案 / 架构选型 | `tech_q1..tech_q9` | 9 | q1（痛点）、q2（边界条件）、q4（三维差异）、q5（真实规模） |
| 项目立项 / 需求梳理 | `proj_q1..proj_q10` | 10 | q1（谁的问题+具体场景）、q2（现在怎么凑合）、q5（上线 4 周看哪个数字）、q6（最小闭环） |
| 复盘 / 总结 | `retro_q1..retro_q9` | 9 | q1（原定目标引原话）、q2（实际结果要数字）、q3（差距归因） |

**why 字段每一条都必须写**（方案 §3.1：why 要显示出来，用户知道为什么问，回答质量差很多）。

### 条件轮触发器（改动 #12，M1 只做这 4 条）

| 场景 | 题 | trigger 正则 | 含义 |
|---|---|---|---|
| 立项 | `proj_q9`（资源/钱从哪来） | `付|收费|定价|订阅|变现` | 只在提到商业时才问 |
| 立项 | `proj_q8`（怎么验证） | `上线|发布|推广|获客` | 提到上线才问 |
| 技术 | `tech_q7`（有没有人踩过坑） | `迁移|重构|替换|升级` | 涉及存量系统才问 |
| 复盘 | `retro_q9`（结论写进哪件未来事项） | `下个|下次|以后|新项目` | 用户提到后续才问 |

命中但用户答「不知道」→ 不追问，直接进第 5 栏（笔记 §四「允许存在未知，但不允许缺一节」）。

---

## 6. 映射层（实体 ↔ 领域模型）

全部在 `data/mapper/`，一文件一映射，**禁止在 ViewModel 里拼实体**：

```kotlin
fun SceneEntity.toDomain(questions: List<QuestionEntity>): Scene
fun QuestionEntity.toDomain(): Question
fun SessionEntity.toDomain(): AskSession
fun MessageEntity.toDomain(): Turn
fun SheetEntity.toDomain(items: List<SheetItemEntity>): Sheet
fun SheetItemEntity.toDomain(): SheetItem
fun Sheet.toEntities(now: Long): Pair<SheetEntity, List<SheetItemEntity>>
```

枚举的字符串编解码集中在 `data/db/EnumCodec.kt`：
`EngineState.fromDb(String)` / `.toDb()`，`SessionStatus` 同理；遇到未知值直接抛 `IllegalStateException`
（**不要静默 fallback**，静默降级会让状态机行为无法解释）。

---

## 7. 数据库版本策略

| 版本 | 内容 | 迁移 |
|---|---|---|
| v1（M1） | 上述 6 张表 | — |
| v2（M2） | `session.lastReadAt`；新增索引 | `Migration(1, 2)` 显式写 SQL |
| v3（M4） | `citation` 表 | 显式迁移 |
| v4（M5） | `plan` 表 | 显式迁移 |
| v5（M6） | `research/evidence/verdict` | 显式迁移 |

- `exportSchema = true`，schema JSON 落 `app/schemas/`，**入版本库**（否则迁移无从校验）。
- 禁止 `fallbackToDestructiveMigration()`——用户的思路数据不允许因为一次升级被清空。
- M1 起就写一个 `MigrationTest`（纯 JVM 跑不了，先留 TODO 到 M2 接 `androidx.room:room-testing`）。

---

## 8. 种子灌库规则（幂等）

```kotlin
class BuiltinSceneSeeder(
    private val context: Context,
    private val sceneDao: SceneDao,
    private val clock: Clock,
) {
    /** 首次启动灌入；已存在同 id 的场景时按 version 覆盖问题清单，但保留用户自建场景 */
    suspend fun seedIfNeeded()
    private suspend fun readAsset(name: String): SceneSeed
    private fun SceneSeed.toEntities(now: Long): Pair<SceneEntity, List<QuestionEntity>>
}
```

**幂等规则**：`scene.builtin = true` 且 `id` 相同 → 覆盖 `name/focus/planSkeleton/questions`（用户对内置场景的修改
在 M3 才允许，M1 直接覆盖）；用户自建场景（`builtin = false`）永不被 seed 触碰。
灌库失败（asset 解析错误）必须让 App 起不来并打日志，**不允许空场景列表静默进入首页**。