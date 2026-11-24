package com.askfirst.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.askfirst.core.AndroidLogx
import com.askfirst.core.AppResult
import com.askfirst.core.Clock
import com.askfirst.core.IdSource
import com.askfirst.core.Logx
import com.askfirst.core.SystemClock
import com.askfirst.core.UuidIdSource
import com.askfirst.data.db.AskFirstDatabase
import com.askfirst.data.db.dao.CitationDao
import com.askfirst.data.db.dao.MessageDao
import com.askfirst.data.db.dao.PlanDao
import com.askfirst.data.db.dao.ResearchDao
import com.askfirst.data.db.dao.SceneDao
import com.askfirst.data.db.dao.SessionDao
import com.askfirst.data.db.dao.SheetDao
import com.askfirst.data.db.seed.BuiltinSceneSeeder
import com.askfirst.data.prefs.SettingsStore
import com.askfirst.data.repo.CitationRepository
import com.askfirst.data.repo.HistoryRepository
import com.askfirst.data.repo.PlanRepository
import com.askfirst.data.repo.ResearchRepository
import com.askfirst.data.repo.SceneRepository
import com.askfirst.data.repo.SessionRepository
import com.askfirst.data.repo.SheetRepository
import com.askfirst.data.secret.SecretStore
import com.askfirst.domain.engine.QuestionEngine
import com.askfirst.domain.engine.StateMachine
import com.askfirst.domain.engine.TurnParser
import com.askfirst.domain.plan.PlanBuilder
import com.askfirst.domain.plan.PlanMarkdown
import com.askfirst.domain.plan.PlanParser
import com.askfirst.domain.plan.PlanValidator
import com.askfirst.domain.research.ResearchBuilder
import com.askfirst.domain.research.ResearchMarkdown
import com.askfirst.domain.research.ResearchPipeline
import com.askfirst.domain.research.ResearchQuestionBuilder
import com.askfirst.domain.prompt.PromptBuilder
import com.askfirst.domain.sheet.PromptExporter
import com.askfirst.domain.sheet.MarkdownExporter
import com.askfirst.domain.sheet.SheetAssembler
import com.askfirst.domain.sheet.SheetBuilder
import com.askfirst.domain.sheet.SheetGrower
import com.askfirst.domain.sheet.SheetParser
import com.askfirst.domain.sheet.SheetValidator
import com.askfirst.domain.usecase.AskFirstFacade
import com.askfirst.remote.HttpFactory
import com.askfirst.remote.LlmProvider
import com.askfirst.remote.OpenAiCompatibleProvider
import com.askfirst.remote.OkHttpFetcher
import com.askfirst.remote.SpeedTester
import com.askfirst.research.GitHubClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 手工依赖注入（决策见 docs/01 §5：M1 不引 Hilt）。
 * **初始化顺序有讲究**：后面的字段依赖前面的字段，Kotlin 按声明顺序初始化。
 */
class AppContainer(
    context: Context,
    private val scope: CoroutineScope,
    val log: Logx = AndroidLogx,
) {
    private val appContext: Context = context.applicationContext

    val clock: Clock = SystemClock
    val ids: IdSource = UuidIdSource

    // ---- 设置与密钥（S2） ----
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { appContext.preferencesDataStoreFile(SettingsStore.FILE_NAME) },
    )
    val settingsStore: SettingsStore = SettingsStore(dataStore)
    val secretStore: SecretStore = SecretStore(appContext, log).also { store ->
        // 加密存储的初始化（Keystore 主密钥 + 解密文件）放到 IO 线程先做掉，
        // 免得设置页/聊天页第一次读密钥时在主线程上做磁盘 IO（docs/28 P2-13）
        scope.launch(Dispatchers.IO) { store.warmUp() }
    }

    // ---- 模型通道（S2 连通性 + S3 流式） ----
    val httpClient: OkHttpClient = HttpFactory.client()
    val llmProvider: LlmProvider =
        OpenAiCompatibleProvider(httpClient, settingsStore, secretStore, log)
    val speedTester: SpeedTester = SpeedTester(llmProvider)

    // ---- 数据库（S1） ----
    val database: AskFirstDatabase = Room
        .databaseBuilder(appContext, AskFirstDatabase::class.java, DB_NAME)
        .addMigrations(
            AskFirstDatabase.MIGRATION_1_2,
            AskFirstDatabase.MIGRATION_2_3,
            AskFirstDatabase.MIGRATION_3_4,
        )
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // Room 默认不强制外键，必须显式打开（docs/03 §2）
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        })
        .build()

    val sceneDao: SceneDao = database.sceneDao()
    val sessionDao: SessionDao = database.sessionDao()
    val messageDao: MessageDao = database.messageDao()
    val sheetDao: SheetDao = database.sheetDao()
    val citationDao: CitationDao = database.citationDao()
    val planDao: PlanDao = database.planDao()
    val researchDao: ResearchDao = database.researchDao()

    val sceneRepository: SceneRepository = SceneRepository(database, sceneDao, ids, clock)
    val sessionRepository: SessionRepository = SessionRepository(sessionDao, messageDao, clock, ids)
    val historyRepository: HistoryRepository = HistoryRepository(sessionDao)
    val citationRepository: CitationRepository = CitationRepository(citationDao, sessionDao, clock, ids)
    val planRepository: PlanRepository = PlanRepository(planDao, clock, ids)
    val researchRepository: ResearchRepository = ResearchRepository(database, researchDao, clock, log)

    // ---- 确认单规则（S5/S6） ----
    val sheetGrower: SheetGrower = SheetGrower(ids)
    val sheetValidator: SheetValidator = SheetValidator()
    val sheetRepository: SheetRepository =
        SheetRepository(database, sheetDao, clock, ids, sheetGrower, log)

    // ---- 内置场景灌库（S1） ----
    val seeder: BuiltinSceneSeeder = BuiltinSceneSeeder(appContext, sceneDao, clock, log)
    val seedResult: Deferred<AppResult<Int>> = scope.async { seeder.seedIfNeeded() }

    // ---- 引擎与门面（S4/S5） ----
    private val promptBuilder: PromptBuilder = PromptBuilder(
        engineSystemPrompt = readAsset("prompts/engine_system.md"),
        sheetSystemPrompt = readAsset("prompts/sheet_system.md"),
        planSystemPrompt = readAsset("prompts/plan_system.md"),
        researchQuestionsSystemPrompt = readAsset("prompts/research_questions.md"),
        researchVerdictSystemPrompt = readAsset("prompts/research_verdict.md"),
    )
    private val stateMachine: StateMachine = StateMachine()
    val promptExporter: PromptExporter = PromptExporter()
    val markdownExporter: MarkdownExporter = MarkdownExporter()
    val sheetAssembler: SheetAssembler = SheetAssembler(ids, clock)
    val sheetBuilder: SheetBuilder = SheetBuilder(
        provider = llmProvider,
        prompts = promptBuilder,
        parser = SheetParser(),
        assembler = sheetAssembler,
    )

    // ---- M5：方案文档 ----
    val planValidator: PlanValidator = PlanValidator()
    val planMarkdown: PlanMarkdown = PlanMarkdown()
    val planBuilder: PlanBuilder = PlanBuilder(
        provider = llmProvider,
        prompts = promptBuilder,
        parser = PlanParser(),
    )
    val questionEngine: QuestionEngine = QuestionEngine(
        provider = llmProvider,
        prompts = promptBuilder,
        parser = TurnParser(),
        stateMachine = stateMachine,
    )

    // ---- M6：调研验证 ----
    /** 和模型通道分开一个取数实现：GitHub 的单请求预算比流式对话短得多（12s/25s/30s）。 */
    val httpFetcher: OkHttpFetcher = OkHttpFetcher(httpClient)
    val githubClient: GitHubClient = GitHubClient(
        fetcher = httpFetcher,
        // 24h 检索缓存直接落在库里（同一份确认单重跑不该再打网络）
        cache = researchRepository,
        // 每次现取：设置页「清除 token」立刻生效，明文不进任何缓存
        tokenProvider = { secretStore.githubPat() },
        proxyProvider = { settingsStore.current().githubProxyBase.ifBlank { null } },
    )
    val researchQuestionBuilder: ResearchQuestionBuilder = ResearchQuestionBuilder(
        provider = llmProvider,
        prompts = promptBuilder,
    )
    val researchBuilder: ResearchBuilder = ResearchBuilder(
        provider = llmProvider,
        prompts = promptBuilder,
    )
    val researchMarkdown: ResearchMarkdown = ResearchMarkdown()
    val researchPipeline: ResearchPipeline = ResearchPipeline(
        github = githubClient,
        judge = researchBuilder,
        ids = ids,
        clock = clock,
    )

    val facade: AskFirstFacade = AskFirstFacade(
        scenes = sceneRepository,
        sessions = sessionRepository,
        sheets = sheetRepository,
        historyRepo = historyRepository,
        citations = citationRepository,
        engine = questionEngine,
        stateMachine = stateMachine,
        sheetBuilder = sheetBuilder,
        sheetValidator = sheetValidator,
        exporter = promptExporter,
        markdownExporter = markdownExporter,
        plans = planRepository,
        planBuilder = planBuilder,
        planValidator = planValidator,
        planMarkdown = planMarkdown,
        researchRepo = researchRepository,
        researchQuestions = researchQuestionBuilder,
        researchPipeline = researchPipeline,
        researchMarkdown = researchMarkdown,
        network = com.askfirst.domain.research.NetworkGuard { isOnline() },
        ids = ids,
        clock = clock,
    )

    /** 有没有可用的网络（VPN 也算）。判不出来就当有网——宁可多试一次，也别误拦。 */
    private fun isOnline(): Boolean = try {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return true // 服务都拿不到：当成有网（宁可多试一次，也别误拦）
        // 没有活动网络 = 真的没网。这里不能「当成有网」——那样飞行模式下还会白等 60 秒。
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (t: Throwable) {
        log.e("AppContainer", "网络状态读不出来（当成有网）", t)
        true
    }

    fun warmUp() {
        scope.launch {
            // 结果别丢：首页会自己 await seedResult 并按需报错，这里只留一条日志便于排查
            when (val seeded = seedResult.await()) {
                is AppResult.Ok -> log.d("AppContainer", "内置场景灌库完成：${seeded.value} 套")
                is AppResult.Err -> log.e("AppContainer", "内置场景灌库失败", seeded.error)
            }
        }
    }

    private fun readAsset(path: String): String = try {
        appContext.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (t: Throwable) {
        log.e("AppContainer", "读取内置提示词失败: $path", t)
        error("内置提示词缺失：$path（打包时漏了 assets）")
    }

    companion object {
        const val DB_NAME = "askfirst.db"
    }
}
