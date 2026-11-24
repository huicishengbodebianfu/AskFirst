package com.askfirst.ui

/** M1 四屏 + M2 两屏（历史列表 / 历史详情）+ M3 编辑器 + M4 引用页 + M5 方案页。 */
sealed interface Route {
    data object Home : Route
    data class Chat(val sessionId: String) : Route
    data class Sheet(val sessionId: String, val build: Boolean) : Route
    data object Settings : Route
    data object History : Route
    data class HistoryDetail(val sessionId: String) : Route
    /** M3 场景编辑器：sceneId 为空 = 新建。 */
    data class SceneEdit(val sceneId: String?) : Route

    /** M4 引用选择页：selected = 已勾选的 sheetId（逗号分隔），空 = 没选。 */
    data class CitePicker(val selected: List<String>) : Route

    /** M5 方案文档页（一场会话一份）。 */
    data class Plan(val sessionId: String) : Route

    /** M6 调研页：查查别人做过没（立题 → 查 → 验证报告）。 */
    data class Research(val sessionId: String) : Route

    companion object {
        const val HOME = "home"
        const val CHAT = "chat/{sessionId}"
        const val SHEET = "sheet/{sessionId}?build={build}"
        const val SETTINGS = "settings"
        const val HISTORY = "history"
        const val HISTORY_DETAIL = "history_detail/{sessionId}"
        const val SCENE_EDIT = "scene_edit?sceneId={sceneId}"
        const val CITE_PICKER = "cite_picker?selected={selected}"
        const val PLAN = "plan/{sessionId}"
        const val RESEARCH = "research/{sessionId}"

        fun chat(sessionId: String) = "chat/$sessionId"

        /** build=true：进来就出单（点了「够了」）；false：只是看一眼实时长出来的单子。 */
        fun sheet(sessionId: String, build: Boolean = false) = "sheet/$sessionId?build=$build"

        fun historyDetail(sessionId: String) = "history_detail/$sessionId"

        fun sceneEdit(sceneId: String? = null) =
            if (sceneId.isNullOrBlank()) "scene_edit" else "scene_edit?sceneId=$sceneId"

        fun citePicker(selected: List<String> = emptyList()) =
            if (selected.isEmpty()) "cite_picker" else "cite_picker?selected=${selected.joinToString(",")}"

        fun plan(sessionId: String) = "plan/$sessionId"

        /** M6：调研页（一场会话一份最新报告）。 */
        fun research(sessionId: String) = "research/$sessionId"
    }
}