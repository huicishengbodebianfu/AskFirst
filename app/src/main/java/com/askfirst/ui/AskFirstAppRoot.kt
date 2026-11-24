package com.askfirst.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.Modifier
import com.askfirst.di.AppContainer
import com.askfirst.ui.screens.chat.ChatScreen
import com.askfirst.ui.screens.cite.CitePickerScreen
import com.askfirst.ui.screens.history.HistoryDetailScreen
import com.askfirst.ui.screens.history.HistoryScreen
import com.askfirst.ui.screens.home.HomeScreen
import com.askfirst.ui.screens.onboarding.OnboardingScreen
import com.askfirst.ui.screens.plan.PlanScreen
import com.askfirst.ui.screens.research.ResearchScreen
import com.askfirst.ui.screens.scene_edit.SceneEditScreen
import com.askfirst.ui.screens.settings.SettingsScreen
import com.askfirst.ui.screens.sheet.SheetScreen
import kotlinx.coroutines.launch

/**
 * M1 导航图：首页（选场景 + 一句话）→ 提问流 → 确认单；设置独立入口；首启先看引导。
 */
@Composable
fun AskFirstAppRoot(
    container: AppContainer,
    startInSettings: Boolean = false,
    startInChat: String? = null,
    startInSheet: String? = null,
    startInHistory: Boolean = false,
    startInSceneEdit: String? = null,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()

    // null = 还在读设置；false = 没看过引导（首启）
    var onboardingDone by remember { mutableStateOf<Boolean?>(null) }
    var pendingSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onboardingDone = container.settingsStore.current().onboardingDone
    }

    val done = onboardingDone
    if (done == null) {
        // 设置还在读：先给个空白占位。
        // 老实现只拦 false，于是首启用户会先闪一下真首页（连库都读了）再被引导页替换（docs/28 P2-10）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
        return
    }
    if (!done) {
        OnboardingScreen(
            onOpenSettings = {
                scope.launch { container.settingsStore.setOnboardingDone(true) }
                pendingSettings = true
                onboardingDone = true
            },
            onStartUsing = {
                scope.launch { container.settingsStore.setOnboardingDone(true) }
                onboardingDone = true
            },
        )
        return
    }

    NavHost(navController = nav, startDestination = Route.HOME) {
        composable(Route.HOME) { entry ->
            val citeResult by entry.savedStateHandle
                .getStateFlow("cited_sheet_ids", "")
                .collectAsState()
            HomeScreen(
                container = container,
                onOpenChat = { sessionId -> nav.navigate(Route.chat(sessionId)) },
                onOpenSettings = { nav.navigate(Route.SETTINGS) },
                onOpenHistory = { nav.navigate(Route.HISTORY) },
                onOpenSceneEdit = { sceneId -> nav.navigate(Route.sceneEdit(sceneId)) },
                onOpenCitePicker = { selected -> nav.navigate(Route.citePicker(selected)) },
                citedResult = citeResult.takeIf { it.isNotBlank() },
                onConsumeCiteResult = { entry.savedStateHandle["cited_sheet_ids"] = "" },
            )
        }
        composable(
            route = Route.CITE_PICKER,
            arguments = listOf(navArgument("selected") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            val selected = entry.arguments?.getString("selected")
                ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            CitePickerScreen(
                container = container,
                initialSelected = selected,
                onConfirm = { picked ->
                    nav.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("cited_sheet_ids", picked.joinToString(","))
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Route.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            ChatScreen(
                container = container,
                sessionId = sessionId,
                onOpenSheet = { id, build -> nav.navigate(Route.sheet(id, build)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Route.SHEET,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("build") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val build = entry.arguments?.getBoolean("build") ?: false
            SheetScreen(
                container = container,
                sessionId = sessionId,
                buildOnOpen = build,
                onOpenPlan = { nav.navigate(Route.plan(sessionId)) },
                onOpenResearch = { nav.navigate(Route.research(sessionId)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Route.PLAN,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            PlanScreen(
                container = container,
                sessionId = sessionId,
                onOpenResearch = { nav.navigate(Route.research(sessionId)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Route.RESEARCH,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            ResearchScreen(
                container = container,
                sessionId = sessionId,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Route.HISTORY) {
            HistoryScreen(
                container = container,
                onOpenDetail = { sessionId -> nav.navigate(Route.historyDetail(sessionId)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Route.HISTORY_DETAIL,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            HistoryDetailScreen(
                container = container,
                sessionId = sessionId,
                onOpenChat = { id -> nav.navigate(Route.chat(id)) },
                onOpenSheet = { id -> nav.navigate(Route.sheet(id, build = false)) },
                onOpenDetail = { id -> nav.navigate(Route.historyDetail(id)) },
                onOpenPlan = { id -> nav.navigate(Route.plan(id)) },
                onOpenResearch = { id -> nav.navigate(Route.research(id)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Route.SCENE_EDIT,
            arguments = listOf(navArgument("sceneId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            val sceneId = entry.arguments?.getString("sceneId")
            SceneEditScreen(
                container = container,
                sceneId = sceneId,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Route.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { nav.popBackStack() },
                onShowOnboarding = { onboardingDone = false },
            )
        }
    }

    // 首启点了「去填 Key」：等 NavHost 挂上之后再跳，避免对未挂载的导航图操作
    LaunchedEffect(pendingSettings) {
        if (pendingSettings) {
            pendingSettings = false
            nav.navigate(Route.SETTINGS)
        }
    }
    if (startInSettings) {
        LaunchedEffect(Unit) { nav.navigate(Route.SETTINGS) }
    }
    if (startInChat != null) {
        LaunchedEffect(startInChat) { nav.navigate(Route.chat(startInChat)) }
    }
    if (startInSheet != null) {
        LaunchedEffect(startInSheet) { nav.navigate(Route.sheet(startInSheet, build = false)) }
    }
    if (startInHistory) {
        LaunchedEffect(Unit) { nav.navigate(Route.HISTORY) }
    }
    if (startInSceneEdit != null) {
        val target = startInSceneEdit.takeUnless { it.isBlank() || it == "new" }
        LaunchedEffect(startInSceneEdit) { nav.navigate(Route.sceneEdit(target)) }
    }
}