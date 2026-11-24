package com.askfirst.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.askfirst.core.AppResult
import com.askfirst.core.Logx
import com.askfirst.core.Text
import com.askfirst.data.prefs.SettingsStore
import com.askfirst.data.secret.SecretStore
import com.askfirst.di.AppContainer
import com.askfirst.remote.LlmProvider
import com.askfirst.remote.SpeedTester
import com.askfirst.research.FetchOutcome
import com.askfirst.research.GitHubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val provider: LlmProvider,
    private val speedTester: SpeedTester,
    private val github: GitHubClient,
    private val log: Logx,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val current = settings.current()
            // 加密存储每次读都要过 Keystore + 解密：放 IO 线程，别在主线程做（docs/28 P2-13）
            val snapshot = withContext(Dispatchers.IO) {
                SecretsSnapshot(
                    maskedLlmKey = secrets.maskedLlmKey(),
                    githubPat = secrets.githubPat(),
                    hasLlmKey = secrets.hasLlmKey(),
                    usingBuiltInLlmKey = secrets.usingBuiltInLlmKey(),
                    usingBuiltInGithubPat = secrets.usingBuiltInGithubPat(),
                )
            }
            // 只打掩码，永不打明文（docs/prompts/glossary_ban.md 与方案 §3.6.2 红线）
            log.d(
                TAG,
                "设置加载 base=${current.baseUrl} model=${current.model} " +
                    "key=${snapshot.maskedLlmKey.ifBlank { "(未填)" }}" +
                    " pat=${Text.maskToken(snapshot.githubPat).ifBlank { "(未填)" }}" +
                    " builtInKey=${snapshot.usingBuiltInLlmKey}",
            )
            _ui.update {
                it.copy(
                    loaded = true,
                    baseUrl = current.baseUrl,
                    model = current.model,
                    maskedKey = snapshot.maskedLlmKey,
                    hasKey = snapshot.hasLlmKey,
                    usingBuiltInKey = snapshot.usingBuiltInLlmKey,
                    canSave = current.baseUrl.isNotBlank() && current.model.isNotBlank(),
                    maskedPat = Text.maskToken(snapshot.githubPat),
                    hasPat = snapshot.githubPat != null,
                    usingBuiltInPat = snapshot.usingBuiltInGithubPat,
                    githubProxy = current.githubProxyBase,
                    researchRecheck = current.researchRecheck,
                )
            }
        }
    }

    /** 密钥相关的读取结果（一次 IO 线程里读全，避免逐项跨线程）。 */
    private data class SecretsSnapshot(
        val maskedLlmKey: String,
        val githubPat: String?,
        val hasLlmKey: Boolean,
        val usingBuiltInLlmKey: Boolean,
        val usingBuiltInGithubPat: Boolean,
    )

    fun onBaseUrlChange(value: String) {
        _ui.update { it.copy(baseUrl = value, message = null, canSave = validate(it.copy(baseUrl = value))) }
    }

    fun onModelChange(value: String) {
        _ui.update { it.copy(model = value, message = null, canSave = validate(it.copy(model = value))) }
    }

    fun onKeyChange(value: String) {
        _ui.update { it.copy(keyInput = value, message = null) }
    }

    /** 保存：Key 只写进 SecretStore，明文不落 DataStore、不进日志。 */
    fun save(onSaved: (() -> Unit)? = null) {
        val state = _ui.value
        if (!state.canSave) {
            _ui.update { it.copy(message = "base_url 和 model 都要填", messageIsError = true) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            if (state.keyInput.isNotBlank()) {
                secrets.putLlmKey(state.keyInput)
            }
            val keyFacts = withContext(Dispatchers.IO) {
                Triple(secrets.hasLlmKey(), secrets.maskedLlmKey(), secrets.usingBuiltInLlmKey())
            }
            settings.saveConnection(
                baseUrl = state.baseUrl,
                model = state.model,
                hasApiKey = keyFacts.first,
            )
            _ui.update {
                it.copy(
                    busy = false,
                    keyInput = "",
                    maskedKey = keyFacts.second,
                    hasKey = keyFacts.first,
                    usingBuiltInKey = keyFacts.third,
                    message = if (keyFacts.third) {
                        "已保存。现在用的是「自用」里写死的内置 Key（想换就在上面粘新的）。"
                    } else {
                        "已保存。Key 只存在本机加密存储里。"
                    },
                    messageIsError = false,
                )
            }
            onSaved?.invoke()
        }
    }

    /**
     * 「拉取模型列表」：先落库当前地址（和「测试连通」同一个纪律），再问网关要 `GET /v1/models`，
     * 拿到就弹选择框，选一个立刻换上去。
     */
    fun loadModels() {
        val state = _ui.value
        if (state.loadingModels) return
        if (state.baseUrl.isBlank()) {
            _ui.update { it.copy(modelsMessage = "先把 base_url 填上", modelsMessageIsError = true) }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(loadingModels = true, modelsMessage = null, modelsMessageIsError = false)
            }
            if (state.keyInput.isNotBlank()) {
                secrets.putLlmKey(state.keyInput)
            }
            // 地址/Key 先落库，否则 provider 读到的还是上一次的配置
            settings.saveConnection(
                baseUrl = state.baseUrl,
                model = state.model.ifBlank { currentModelFallback(state) },
                hasApiKey = withContext(Dispatchers.IO) { secrets.hasLlmKey() },
            )
            when (val result = provider.listModels()) {
                is AppResult.Ok -> _ui.update {
                    it.copy(
                        loadingModels = false,
                        models = result.value,
                        modelsMessage = "拿到 ${result.value.size} 个模型，点一个就换上。",
                        modelsMessageIsError = false,
                    )
                }

                is AppResult.Err -> _ui.update {
                    it.copy(
                        loadingModels = false,
                        models = emptyList(),
                        modelsMessage = result.error.message,
                        modelsMessageIsError = true,
                    )
                }
            }
        }
    }

    /** 选中列表里的某个模型：立刻写进 model 字段并落库（换模型不用再点一次保存）。 */
    fun pickModel(name: String) {
        val state = _ui.value
        val chosen = name.trim()
        if (chosen.isBlank()) return
        _ui.update {
            it.copy(
                model = chosen,
                models = emptyList(),
                canSave = validate(it.copy(model = chosen)),
                message = "模型已换成 $chosen（再点「测试连通」确认一下它真能用）",
                messageIsError = false,
            )
        }
        viewModelScope.launch {
            val hasKey = withContext(Dispatchers.IO) { secrets.hasLlmKey() }
            settings.saveConnection(state.baseUrl, chosen, hasKey)
        }
    }

    /** 关掉模型选择框（不选）。 */
    fun dismissModels() {
        _ui.update { it.copy(models = emptyList()) }
    }

    /** model 空着也能拉列表：落库时给一个占位，别让 saveConnection 写空串。 */
    private fun currentModelFallback(state: SettingsUiState): String =
        state.model.ifBlank { com.askfirst.data.prefs.Settings.DEFAULT_MODEL }

    fun testConnection() {
        val state = _ui.value
        if (!state.canSave) {
            _ui.update { it.copy(message = "先把地址和 model 填上", messageIsError = true) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, message = null, testOk = null, busy = true) }
            if (state.keyInput.isNotBlank()) {
                secrets.putLlmKey(state.keyInput)
            }
            settings.saveConnection(state.baseUrl, state.model, secrets.hasLlmKey())

            val result = provider.ping()
            when (result) {
                is AppResult.Ok -> _ui.update {
                    it.copy(
                        testing = false,
                        busy = false,
                        testOk = true,
                        maskedKey = secrets.maskedLlmKey(),
                        hasKey = secrets.hasLlmKey(),
                        keyInput = "",
                        message = result.value,
                        messageIsError = false,
                    )
                }

                is AppResult.Err -> _ui.update {
                    it.copy(
                        testing = false,
                        busy = false,
                        testOk = false,
                        maskedKey = secrets.maskedLlmKey(),
                        hasKey = secrets.hasLlmKey(),
                        keyInput = "",
                        message = result.error.message,
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /** 测速：一次 64 token 的小请求，量首字延迟与输出速度（30 秒超时）。 */
    fun speedTest() {
        val state = _ui.value
        if (!state.canSave || state.speedTesting || state.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(speedTesting = true, speedText = null, message = null, messageIsError = false) }
            if (state.keyInput.isNotBlank()) secrets.putLlmKey(state.keyInput)
            settings.saveConnection(state.baseUrl, state.model, secrets.hasLlmKey())

            when (val result = speedTester.measure()) {
                is AppResult.Ok -> _ui.update {
                    it.copy(
                        speedTesting = false,
                        maskedKey = secrets.maskedLlmKey(),
                        hasKey = secrets.hasLlmKey(),
                        keyInput = "",
                        speedText = result.value.readable(),
                        message = null,
                        messageIsError = false,
                    )
                }

                is AppResult.Err -> _ui.update {
                    it.copy(
                        speedTesting = false,
                        speedText = null,
                        message = result.error.message,
                        messageIsError = true,
                    )
                }
            }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            secrets.clearLlmKey()
            settings.saveConnection(_ui.value.baseUrl, _ui.value.model, hasApiKey = false)
            _ui.update {
                it.copy(
                    maskedKey = "",
                    hasKey = false,
                    usingBuiltInKey = false,
                    testOk = null,
                    message = "Key 已从本机清除，内置默认 Key 也一并停用。要恢复就重新粘一把。",
                    messageIsError = false,
                )
            }
        }
    }

    private fun validate(state: SettingsUiState): Boolean =
        state.baseUrl.isNotBlank() && state.model.isNotBlank()

    // ------------------------------------------------------------------ M6 查资料（GitHub）

    fun onPatChange(value: String) =
        _ui.update { it.copy(patInput = value, researchMessage = null, researchMessageIsError = false) }

    /** 存访问凭证：明文只进 SecretStore，界面只留后 4 位。 */
    fun savePat() {
        val raw = _ui.value.patInput.trim()
        if (raw.isBlank()) {
            _ui.update {
                it.copy(researchMessage = "先把访问凭证粘到上面那格，再点保存。", researchMessageIsError = true)
            }
            return
        }
        viewModelScope.launch {
            secrets.putGithubPat(raw)
            val (stored, builtIn) = withContext(Dispatchers.IO) {
                secrets.githubPat() to secrets.usingBuiltInGithubPat()
            }
            _ui.update {
                it.copy(
                    patInput = "",
                    maskedPat = Text.maskToken(stored),
                    hasPat = stored != null,
                    usingBuiltInPat = builtIn,
                    researchMessage = "存好了。以后查资料会带上它，界面只显示后 4 位。",
                    researchMessageIsError = false,
                )
            }
        }
    }

    /** 清除：连内存里现取的那份一起没了，下一次检索就是匿名。 */
    fun clearPat() {
        viewModelScope.launch {
            secrets.clearGithubPat()
            _ui.update {
                it.copy(
                    maskedPat = "",
                    hasPat = false,
                    usingBuiltInPat = false,
                    githubTestText = null,
                    githubTestOk = null,
                    researchMessage = "已清除，内置默认凭证也一并停用。之后匿名查：额度更紧，代码这一类也查不了。",
                    researchMessageIsError = false,
                )
            }
        }
    }

    /** 「测试检索」：能问到额度就说明这条路通（成功 = 能查，失败 = 说原因 + 下一步）。 */
    fun testGithub() {
        if (_ui.value.testingGithub) return
        viewModelScope.launch {
            _ui.update { it.copy(testingGithub = true, githubTestText = null, githubTestOk = null) }
            when (val result = github.rateLimit()) {
                is FetchOutcome.Ok -> _ui.update {
                    it.copy(
                        testingGithub = false,
                        githubTestOk = true,
                        githubTestText = "能查（额度没满）",
                    )
                }

                is FetchOutcome.Failed -> _ui.update {
                    it.copy(
                        testingGithub = false,
                        githubTestOk = false,
                        githubTestText = result.reason + if (result.needsToken) {
                            "。先在上面填一个访问凭证再试。"
                        } else {
                            "。看看手机有没有网；直连不稳就在下面填个代理再试。"
                        },
                    )
                }
            }
        }
    }

    fun onProxyChange(value: String) =
        _ui.update { it.copy(githubProxy = value, researchMessage = null, researchMessageIsError = false) }

    fun onRecheckChange(value: Boolean) =
        _ui.update { it.copy(researchRecheck = value, researchMessage = null, researchMessageIsError = false) }

    /** 代理与「没搜到就换个词再查一次」一起存（只进 DataStore，不放密钥）。 */
    fun saveResearchSettings() {
        viewModelScope.launch {
            settings.saveResearch(_ui.value.githubProxy, _ui.value.researchRecheck)
            _ui.update {
                it.copy(
                    researchMessage = if (it.researchRecheck) {
                        "存好了。没搜到的时候会换个说法再查一次。"
                    } else {
                        "存好了，复核关着：报告里只能说「没查到」，不能说「没人做过」。"
                    },
                    researchMessageIsError = false,
                )
            }
        }
    }

    companion object {
        private const val TAG = "AskFirstSettings"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settings = container.settingsStore,
                    secrets = container.secretStore,
                    provider = container.llmProvider,
                    speedTester = container.speedTester,
                    github = container.githubClient,
                    log = container.log,
                )
            }
        }
    }
}