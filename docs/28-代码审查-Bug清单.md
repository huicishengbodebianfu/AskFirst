# 代码审查 · Bug 清单（逐文件通读）

审查范围：`app/src/main/java`（全部 137 个 Kotlin 文件，逐行读完）、`app/src/main/assets`、`app/src/main/res`、
`AndroidManifest.xml`、Gradle 配置、`app/src/test` + `app/src/androidTest`、`tools/*.py`。
未改动任何业务代码。

## 基线

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest`（`--no-build-cache --rerun-tasks`） | **256 个用例全部通过**，0 失败 0 跳过 |
| `:app:assembleDebug` | 通过 |
| Room 迁移链 v1→v2→v3→v4 | 完整，DDL 与 `app/schemas/.../4.json` 逐表一致 |
| 所有 `@Query` 投影 | 与 `Projections.kt` 的行类型逐列一致 |

**结论：这 20 个 bug 全都在单测覆盖不到的路径上**（UI / 仓库 / 网络边界 / prompt 契约 / 资源）。
纯函数层（引擎解析、方案、调研规则、Sheet 规则）质量确实很高，这也是测试全绿的原因。

---

# P0（会把 App 弄崩，或静默存进错数据）

## P0-1 地址少写 `https://` 直接把 App 打崩，且此后每一轮提问都崩

**位置**
- `app/src/main/java/com/askfirst/remote/OpenAiCompatibleProvider.kt:70`（ping）、`:114`（stream）
- `app/src/main/java/com/askfirst/ui/screens/settings/SettingsViewModel.kt:112`、`:119`

**代码**
```kotlin
// OpenAiCompatibleProvider.kt:70-78  —— Request 建在 try 外面
val request = Request.Builder()
    .url(EndpointBuilder.chatCompletions(config.baseUrl))
    .header("Authorization", "Bearer ${config.apiKey}")
    ...
    .build()
val pingClient = clientFor(PING_TIMEOUT_MS)
val call = pingClient.newCall(request)
try {                                    // ← try 从第 78 行才开始
```
```kotlin
// SettingsViewModel.kt:112-119  —— 整段没有 try/catch
viewModelScope.launch {
    ...
    val result = provider.ping()
```

**为什么是 bug**
`EndpointBuilder.chatCompletions` 只做 `trim()` + 拼 `/chat/completions`（`EndpointBuilder.kt:10-16`），
`SettingsViewModel.validate` 只判 `isNotBlank()`（`:200-201`），`SettingsStore.saveConnection` 只 `trim().trimEnd('/')`
（`SettingsStore.kt:24`）。所以用户把 `api.deepseek.com/v1`（漏 `https://`，最常见的填错方式）填进去——
校验通过、**已落库**、然后 OkHttp 的 `url()` 抛 `IllegalArgumentException: Expected URL scheme...`。
这个异常在建 `Request` 时抛出，在 `try` 之外，`ping()` 没有 catch，`viewModelScope.launch` 里也没有 catch
→ 未捕获异常 → `CrashLogger` 记一笔后进程被杀。

更糟的是 `saveConnection` 在 `ping()` **之前**执行（`:117`），坏地址已经存进 DataStore，所以用户从崩溃里
重进 App、回首页、开始提问时，`stream()` 的同一条语句（`:114`）会再崩一次。等于**填错一次地址，App 永久崩**
（直到他回设置页把地址改对，但"测试连通"按钮本身也会崩，等于没有反馈）。

**同一工程里的正确写法**（说明这是漏写，不是设计）：
```kotlin
// OkHttpFetcher.kt:36-45
val request = try {
    Request.Builder().url(url).get()...
} catch (t: IllegalArgumentException) {
    // 地址填错（代理 base 少了 https:// 之类）不该炸整个调研
    return@withContext FetchResult(code = 0, failure = FetchFailure.DNS)
}
```

**修法**
1. `EndpointBuilder` 里规范化：无 scheme 时补 `https://`，非 `http/https` 直接返回配置错误；
2. `ping()` / `stream()` 把 `Request.Builder()` 挪进 `try`，映射成 `AppError.Config` / `AppError.Network`。

---

## P0-2 条目的「出处 id」是模型编的，全链路没人校验 —— 「看条目出处」永远空白

**位置**
- `app/src/main/java/com/askfirst/domain/prompt/PromptBuilder.kt:135-142`
- `app/src/main/assets/prompts/engine_system.md:61-62`
- `app/src/main/java/com/askfirst/domain/engine/QuestionEngine.kt:142-158`
- `app/src/main/java/com/askfirst/domain/sheet/SheetGrower.kt:33`
- `app/src/main/java/com/askfirst/domain/sheet/SheetValidator.kt:29-31`
- `app/src/main/java/com/askfirst/domain/sheet/SourceCollector.kt:14`
- 用户可见症状在 `app/src/main/java/com/askfirst/ui/screens/sheet/SheetScreen.kt:129-130`

**代码**
```kotlin
// PromptBuilder.kt:139-142 —— 引擎上下文里只有 U/A，没有消息 id
var lines = recent.map { turn ->
    val who = if (turn.role == TurnRole.USER) "U" else "A"
    "[$who] ${turn.text.trim()}"
}
```
```markdown
<!-- engine_system.md:61-62 —— 却要求模型填真实消息 id -->
- `sheet_delta`：… 每条必须带 `source_message_ids`（他这条回答的消息 id）。
  **没有出处的条目不要写**——宁可这轮不填。
```
```kotlin
// QuestionEngine.kt:145-147 —— 拿到就用，没有白名单过滤
val sources = if (delta.sourceMessageIds.isNotEmpty()) {
    delta.sourceMessageIds.distinct()
} else { /* 文本重合探测，兜底用真实 lastUser.id */ }
```

**为什么是 bug**
模型在上下文里**根本看不到任何 message id**（只有 `[U]`/`[A]`），所以它填出来的 `source_message_ids`
必然是编的（`"m_1"`、`"msg_abc"`…）。这条编出来的 id 一路畅通：

- `SheetGrower.newItems:33` 只检查 `isEmpty()`；
- `SheetValidator:29-31` 只检查 `isEmpty()` —— 于是「无出处不入库」的 D4 铁律就被"非空"骗过去了；
- 落库后 `SourceCollector.collect:14` 用 `byId[it]` 查不到 → 返回空 → 弹窗显示
  **「找不到原始回答了（可能已经被清掉）。」**（`SheetScreen.kt:130`）——把产品自己的 bug 说成用户的错。

而同一个类里 `planTranscript`（`PromptBuilder.kt:250`）和 `researchTranscript`（`:326`）都印了
`"[${turn.id}] 你：…"`，说明引擎这段是漏了，不是设计。

**补充**：`buildSheet`（走 `SheetAssembler.matchSource`）会重新配真实 id，但它只覆盖 `edited=false` 的条目；
所以"边聊边长的条目"在该轮就是假出处，用户在聊天页点「看单子（N）」（`buildOnOpen=false` 的预览路径）
看到的就是一片"找不到原始回答"。`docs/11-S5` 声称"条目零无出处"，验收时只验了非空。

**修法**
`QuestionEngine.resolve` 里给 `sourceMessageIds` 加白名单：
```kotlin
val known = context.turns.map { it.id }.toSet()
val sources = delta.sourceMessageIds.filter { it in known }.distinct()
```
全被过滤掉时退回现有的 `lastUser.id` 文本重合探测分支。
根治做法是把 `transcript()` 也改成印 `[消息id]`，但那会改 prompt 契约，需同步改 `engine_system.md`。

---

# P1（功能可见地坏掉 / 说了假话）

## P1-1 状态机永远到不了 READY；矛盾提示卡一旦出现就再也消不掉

**位置**：`PrecisionGate.kt:20-27,109-110`、`Lexicon.kt:10-21,27-60`、`StateMachine.kt:45,49`

```kotlin
// PrecisionGate.kt:109-110 —— 每轮都拿"整段历史里的全部用户原话"重扫
fun GateInput.userTexts(): List<String> =
    turns.filter { it.role == TurnRole.USER }.map { it.text }.filter { it.isNotBlank() }
```
```kotlin
// StateMachine.kt:44-49
input.modelSuggested == EngineState.BLOCKED -> EngineState.BLOCKED
hasContradiction -> EngineState.BLOCKED
!requiredAnswered -> EngineState.GATHERING
issues.isNotEmpty() -> EngineState.NEEDS_PRECISION
```

**为什么是 bug**
`Lexicon.ambiguous` 里是 10 个产品讨论里必然出现的词：`用户`、`记录`、`保存`、`同步`、`分享`、
`管理`、`通知`、`搜索`、`简单`、`上线`。只要用户在任何一轮说过其中**任意一个**，`hiddenDefaults()`
就会一直产出 issue —— 因为原话只追加不删除，而这个 issue 从来没有任何"已解决"的记账。
于是 `issues.isNotEmpty()` 恒真 → `NEEDS_PRECISION` 恒真 → **READY 在所有正常对话里不可达**。

矛盾更严重：`Lexicon.contradictions` 的 4 条规则（"不用登录" + "我的数据"等）一旦命中，
`hasContradiction` 每轮都重算为真 → 永久 `BLOCKED`，聊天页那条红色卡片
（`ChatScreen.kt:201-215`）会一直挂着，用户按它说的回答"哪条是你真正要的"也**消不掉**。

**为什么验收没抓到**：`MainActivity.runDemo` 的三句演示回答（`:124-128`）和 `docs/11/16` 的验收话术
恰好一个都没命中这 10 个词，所以 `gathering→ready` 在验收时是真的走通了。

**修法**：给文本派生的 issue 记账（"这一条已经追问过/用户已经回应过"），只对**未消解**的命中
触发 NEEDS_PRECISION / BLOCKED；或者把 `hiddenDefaults` 限制在"最近 N 轮"内。

---

## P1-2 换词复核还没发请求就记成"复核过" → 网络全挂时产出假的「无人区」

**位置**：`app/src/main/java/com/askfirst/domain/research/ResearchPipeline.kt:223-233`

```kotlin
if (found.isEmpty() && input.recheckEnabled && remainingMs(start) > MIN_STEP_MS) {
    rechecked += question.idx          // ← 先记账
    emit(ResearchEvent.Step("换个说法再查一次：${question.text}"))
    evidence += collect(               // ← 后发请求，失败只往 failures 记一行
        question = question, source = "换词复核", ...
```

**为什么是 bug**
`found.isEmpty()` 在两种情况下都成立：真的 0 命中，**以及三个源全部请求失败**（`collect` 出错返回空列表，
`ResearchPipeline.kt:257-260`）。此时 `rechecked` 已经包含这个 idx，于是 `ResearchRules.kt:111-121` 的
`!rechecked.contains(...)` 为假、`hasEvidence` 也为假 → 判定被保留成 `UNEXPLORED`。
结果：一个**连一个请求都没成功过**的问题，报告写成「无人区 · 已覆盖关键词 …」，还会写进第 5 栏。
这正好违反本文件头注释第 34 行的「失败必须可见：没有任何证据时不许产出『无人区』」，
也是产品最核心的那句承诺（"说没人做过之前先查，查不到就说没查到"）被打破。

**修法**：只在复核拿到 `FetchOutcome.Ok` 且确实 0 命中时才 `rechecked += question.idx`。

---

## P1-3 编辑一次场景 = 换掉所有问题 id，已有会话的「已覆盖」全部失效

**位置**：`app/src/main/java/com/askfirst/data/repo/SceneRepository.kt:147-162`

```kotlin
dao.deleteQuestionsOf(sceneId)
dao.upsertQuestions(
    draft.questions.mapIndexed { index, q ->
        QuestionEntity(
            id = ids.newId(IdPrefix.QUESTION),   // ← 每次保存都发新 id
```

**为什么是 bug**
"哪些问题答过了"是**按问题 id 存**的：`message.coveredJson`，比对在两处
——`StateMachine.kt:35-37`（`requiredAnswered = … .all { it.id in input.covered }`）和
`PromptBuilder.kt:124`（`.filterNot { it.id in covered }`）。
用户中途去改一下自建场景的措辞再回来，所有老 id 都对不上 → 必问题全部"没答过" →
引擎重新问一遍已答过的题、状态退回 `GATHERING`、`READY` 不可达。

**修法**：保存时按位置复用原 id（第 i 题内容没变就沿用旧 id），只给新增的题发新 id。

---

## P1-4 报错态里的「先看看单子」会真的调一次模型出单

**位置**：`app/src/main/java/com/askfirst/ui/screens/chat/ChatScreen.kt:120` 与 `:196`

```kotlin
// :120
onStop = { onOpenSheet(sessionId, true) },
// :196  —— 这一行在 state.error != null 的分支里
OutlinedButton(onClick = onStop) { Text("先看看单子") }
```

**为什么是 bug**
`onStop` 带着 `build=true` 进入 `Route.sheet`，`SheetViewModel.loadAndMaybeBuild` 里
`if (buildOnOpen) build()`（`SheetViewModel.kt:117`）就是一次真模型调用。
而这个按钮只在**上一轮刚失败**（`state.error != null`）时出现 —— 用户想的是"我别再等了，先看单子"，
实际却又发了一次大概率同样失败的请求，而且看不到预览。同文件顶栏的「看单子（N）」（`:71`）
传的是 `false`，`SheetViewModel.kt:116` 的注释也写着「"看单子"只是预览，不许偷偷调模型」。

**修法**：这个按钮单独一个回调，传 `false`。

---

## P1-5 调研保存：先删旧报告，失败后仍报"存好了"

**位置**：`app/src/main/java/com/askfirst/data/repo/ResearchRepository.kt:40-55`

```kotlin
return try {
    dao.deleteBySession(research.sessionId)      // ① 删
    dao.upsertResearch(research.toEntity())      // ② 写（各自一个事务）
    ...
} catch (t: Throwable) {
    log.e(TAG, "保存调研失败", t)
    research                                     // ← 当成功返回
}
```

**为什么是 bug**
三步是三个独立事务；`catch (Throwable)` 连 `CancellationException` 一起吞。用户在保存进行中退出调研页
（`viewModelScope` 取消）→ ① 已执行、②③ 抛取消异常被吞 → **上一份报告被删掉了、新的没写进去**，
而 `AskFirstFacade.kt:455-456` 拿到这个返回值照常 `emit(ResearchOutcome.Done(saved, …))`，页面说"已保存"。

**修法**：三步包进 `db.withTransaction`；`CancellationException` 原样抛出；真失败返回 `AppResult.Err`。

---

## P1-6 「精确度缺口」的字段结构从没写进提示词，模型报的问题全被丢掉

**位置**：`app/src/main/assets/prompts/engine_system.md:41,65-66` 对
`app/src/main/java/com/askfirst/domain/engine/QuestionEngine.kt:160-164`、`TurnParser.kt:43-48`

```markdown
  "precision_issues": [],          <!-- 示例里只有空数组，没有对象结构 -->
- `precision_issues`：`state` 不是 `gathering` 时才填。`kind` ∈ … 。`quoted` 必须是他的原话片段。
```
```kotlin
val modelIssues = payload.precisionIssues.mapNotNull { issue ->
    val kind = PrecisionKind.fromApi(issue.kind) ?: return@mapNotNull null
    if (issue.detail.isBlank()) return@mapNotNull null      // ← detail 必填，但契约里没这个字段
    PrecisionIssue(kind = kind, target = issue.target, detail = issue.detail, quoted = issue.quoted)
```

**为什么是 bug**
提示词只点名了 `kind` 和 `quoted`，没提 `detail`，也没提 `target`（而 `target` 是
`StateVerdict.goalNotLanded` 判 `"col1"` 的依据）。`PrecisionIssuePayload.detail` 默认 `""`，
照契约办事的模型产出的 issue 会被这一行静默丢掉。结果：模型的"隐藏默认值/矛盾/走查"发现永远进不来，
`needs_precision` 只能由 P1-1 那套离线规则驱动 —— 与"改动 #4 四项检查"的设计意图相反。

**修法**：在 `engine_system.md` 里把对象结构写全（`{"kind":"…","target":"col2","detail":"…","quoted":"…"}`，
并注明 `detail`、`target` 必填）。

---

## P1-7 `allowBackup="true"` + 空备份规则 = 整库上云，与「数据只在本机」相反

**位置**：`app/src/main/AndroidManifest.xml:10`、`app/src/main/res/xml/backup_rules.xml:2-4`、
`app/src/main/res/xml/data_extraction_rules.xml:3-6`

```xml
android:allowBackup="true"
```
```xml
<!-- Android 11 及以下的 Auto Backup 规则：空规则，暂不备份任何内容 -->
<full-backup-content>
</full-backup-content>
```
```xml
<data-extraction-rules>
    <cloud-backup />
    <device-transfer />
</data-extraction-rules>
```

**为什么是 bug**
Android 的 Auto Backup 只有在出现 `<include>` 之后才切换成"只备份列出的"；只有 `<exclude>` 和
一个空的 `<full-backup-content>` 时，**默认行为是备份全部应用数据**（`no_backup`、cache 除外）。
所以 `askfirst.db`（全部会话、五栏单、方案、调研记录）会被 Auto Backup 上传到用户账号的云备份里，
而 App 自己导出的文档底部印着「由 AskFirst 生成 · 数据只在本机」
（`MarkdownExporter.kt:61`、`PlanMarkdown.kt:57`）。注释写的"暂不备份任何内容"是错的。

**修法**：`android:allowBackup="false"`；或者真的写 `<exclude domain="database" path="."/>` 等规则，
并把注释改成事实。

---

## P1-8 明文网关 Key 提交进仓库

**位置**：`tools/bench_models.py:13`（该文件**已入库**：`git ls-files tools/` 有它，`.gitignore` 未覆盖 `tools/`）

```python
BASE = "http://127.0.0.1:3425/v1/chat/completions"
KEY = "<网关 key>"
```

**为什么是 bug**
不管用途如何，这是写进版本库的凭据，与 `glossary_ban.md` 的「Key 只存在本机、不进日志、不进导出文件」
冲突；仓库一旦分享/ fork/ 备份，这把 key 就跟着走。减轻因素：它是本机 magpie 网关（`127.0.0.1`）的 key，
外网不可直接利用。

**修法**：删字面量，改 `os.environ["MAGPIE_KEY"]`；这把 key 轮换一次。
（同一条纪律下，`keystore.properties` 里的 `storePassword`/`keyPassword` 也是明文，但已被 `.gitignore`
正确排除、`git ls-files` 确认未入库，所以只作为提醒。）

---

# P2（边界情况 / 弱化体验 / 数据不全）

| # | 位置 | 问题 |
|---|---|---|
| 1 | `remote/OpenAiCompatibleProvider.kt:145-170` | SSE 循环结束后**从不调用 `parser.flush()`**。`SseParser` 只在空行时 dispatch（`SseParser.kt:20`），所以服务端/代理在最后一个 `data:` 行后直接断流（少了结尾空行）时，最后一帧（通常是 `finish_reason`，`response_format=json_object` 时还会带走 JSON 尾部）被丢弃 → `Done(fullText)` 是残的。`SseParserTest` 里"末尾缺空行"那条能过，只因为 `feedAll` 自己调了 `flush()`（`:64`）。 |
| 2 | `domain/engine/JsonRepair.kt:9-14` + `core/Text.kt:33-39` | 围栏和 JSON 写在同一行时（```` ```json{"state":"gathering"}``` ````），`substringAfter('\n', "")` 返回空串 → `clean()` 返回 null → `TurnParser` 报"找不到完整的 JSON 对象"并触发一次低温重试。`Text.stripCodeFence` 是同一段代码，`EvidenceParse`/`OfflineResearch`/`QuestionDrafting`/`VerdictParse` 一起中招。 |
| 3 | `domain/plan/PlanValidator.kt:68-72` | 剥列表序号的正则 `^\s*(#{1,6}\s*)?(\d+[.、)]\|[-*+])\s*` 会把行首小数当序号：`- 3.5 天上线` → `3.5 天上线` → `5 天上线`。于是"硬答案保护"既报一个原话里不存在的 `5`，又完全没校验 `3.5`。 |
| 4 | `domain/research/ResearchPipeline.kt:112-117`、`usecase/AskFirstFacade.kt:512-517` | `ResearchRules.normalize` 专门产出 `warnings`（"这条反模式没引到原话…我不替你下这个结论"），两个调用点都只取 `.verdicts`，`failures` 里也不收 → 被驳回的判定从报告和库里彻底消失，报告看起来一条没漏。 |
| 5 | `domain/research/ResearchPipeline.kt:62`、`:109`；渲染在 `ResearchMarkdown.kt:111-114`、`ResearchScreen.kt:857-860` | 两处"整轮级"失败硬编码 `questionIdx = 0`，而真实问题编号是 1..n → 报告里出现 `❌ 「」 · 全部：…`、`❌ 「」 · 对照：…`，空标题。 |
| 6 | `domain/model/AskSession.kt:70-78` + `usecase/AskFirstFacade.kt:623-629` | `blockReason` 非空时占位文案不写、`blockReason` 也不进正文；当 `clarify==null`、问题都问完（`fallbackQuestion` 返回 null）、状态 BLOCKED 时 `toAssistantText()` 返回 `""` → 落库一条**空气泡**，下一轮 prompt 里还多一行空 `[A]`。 |
| 7 | `domain/research/EvidenceParse.kt:112` | `<[^>]{1,200}>` 当 HTML 标签剥，`[^>]` 也吃换行和代码里的尖括号：`Map<String, List<Int>> cache` → `Map > cache`，跨行的 `a < b … c > d` 中间整段被删。而"读原文不读简介"要的正是那段代码。 |
| 8 | `domain/engine/StateMachine.kt:42,68` | `userEditedConfirmed` 分支在生产代码里**没有任何地方置 true**（只有 `StateMachineTest.kt:34` 传过）。文档承诺的"用户回改已确认内容 → 退回 gathering"实际上不存在：手改条目后会话仍是 READY。 |
| 9 | `ui/screens/chat/ChatScreen.kt:60-62` | 自动滚动 `animateScrollToItem(state.turns.lastIndex)` 少算了前面的 `item{}`：LazyColumn 的 0 号是"你说要做"，有引用时 1 号是引用横幅，最后还有个占位 item。所以每次滚到的是最新消息**上面 1~2 行**，最新那条 AI 回复不进视野（只有 1 条消息时滚到的是标题）。 |
| 10 | `ui/AskFirstAppRoot.kt:46,53-69` | `onboardingDone` 的初值 `null`（还在读 DataStore）也走 `NavHost` 分支，只有 `false` 才拦。首启用户会先看到真首页（并触发 `HomeViewModel.refreshSilently()` 读库）再被引导页替换。 |
| 11 | `di/AppContainer.kt:135,236-238` + `ui/screens/home/HomeViewModel.kt:48-51` | `seedResult` 是 async 起的，但**没有任何 UI await 它**（`warmUp()` 只 await 然后丢弃结果），`HomeViewModel.init` 直接查库 → 冷启动首页可能读到空 `scene` 表（要等 `ON_RESUME` 那次刷新才补上）；更严重的是 `AppError.Seed` 被 `warmUp()` 丢掉，asset 坏掉时首页永远空着且**没有任何提示**，违反 `BuiltinSceneSeeder.kt:21` 自己的注释。另外 `seedIfNeeded()` 没有"已灌就跳过"的判断（`SceneDao.sceneCount()` 写了却没人用），每次冷启动都重读 3 个 asset 并重写 28 道题。 |
| 12 | `research/GitHubClient.kt:80-81` + `:71` | `rateLimit()`（设置页"测试检索"）也走 `call()`，而 `call()` **先查 24h 缓存**。点过一次之后再断网点，仍显示"能查（额度没满）"——一个本该验证连通性的按钮在说谎。 |
| 13 | `data/secret/SecretStore.kt:23,58,66` | `EncryptedSharedPreferences` 每次读都要解密（Keystore 操作 + 磁盘）。所有调用方都在主线程：`SettingsViewModel.init:36-39`、`:96-97`、`MainActivity.kt:71`。打开设置页 = 在 UI 线程做 IO + Keystore 运算。 |
| 14 | `data/db/seed/SceneSeedModels.kt:12` + `domain/scene/SceneJsonCodec.kt:36-44` | `SceneSeed.id` 没有默认值 → 缺 `id` 的文件在 `decodeFromString` 就抛 `MissingFieldException`，被 `:31` 的 catch 抓住，`:42-44` 那句"缺了场景标识（id），让对方用导出按钮重新发一份"是**死代码**。用户（亲友）实际看到的是 `这不是一份能读的场景文件（Field 'id' is required for type with serial name 'com.askfirst.data.db.seed.SceneSeed'…）`。 |
| 15 | `app/src/test/.../SceneJsonCodecTest.kt:77-82` | `assertTrue(result is AppResult.Err \|\| (result as? AppResult.Ok)?.value?.questions?.isEmpty() == true)` —— 名字说"空 questions 被拦下"，断言却允许"成功解码出空列表"，即把 bug 当通过。`SceneDraft.validate()` 那条"至少要有一道问题"的规则删掉这个测试也不会红。 |
| 16 | `ui/screens/research/ResearchScreen.kt:137-141` + `ResearchViewModel.kt:148-152` | 保存前先 `editing = null` 关弹窗，再交给 `updateQuestion` 校验；文本短于 6 字时 ViewModel 直接 return，弹窗已经关了、输入丢了，只剩页面顶部一行提示。对比 `addQuestion` 是返回 `Boolean` 且"加成功了才清空"。 |
| 17 | `data/repo/SceneRepository.kt:147-148` | `deleteQuestionsOf` + `upsertQuestions` 是两个事务。保存过程中退出编辑器（协程取消）会留下**零个问题**的场景；自建场景没人会修复它，而 `StateMachine` 对空问题集 `all{}` 恒真 → 直接 READY。 |

---

# 附：次要观察（不影响功能，但值得知道）

- `core/Text.kt:16-19` `truncate` 按 UTF-16 单元截断，`take(max-1)` 可能切裂代理对（emoji 开头的那句话做标题时会出"半个字符"）。
- `ui/screens/history/HistoryFormat.kt:19` 用 `abs()` 算天数差，未来时间（设备时间被回拨过）会显示成"昨天 HH:mm"。
- `ui/AskFirstAppRoot.kt:214-229` 的 5 个 `startIn*` 深链在 Activity 重建（旋转）时会重复 `navigate`，返回栈里多一份同样的页面。这些入口只由 adb 调试 extra 触发，属调试通道。
- `domain/engine/QuestionEngine.kt:189-193` 的兜底问句 `fallbackQuestion` 丢了 `options`/`recommended`；场景题本身没有 options 字段，影响很小。
- `domain/engine/ChatChunkParser.kt:56-60`：同一帧里同时带 `delta.content` 和 `finish_reason` 时，`finish_reason` 被 `when` 的第一支吞掉（上游只用它打日志，影响很小）。
- `domain/engine/SseParser.kt:47-51`：一帧的 data 只有空串时 `dispatch()` 返回 null，该帧被丢弃（W3C 规范里应派发空 data 事件；本应用无影响）。
- `usecase/AskFirstFacade.kt:574` 已经算好了累计覆盖 `covered`，但 `:606` 传给状态机的只有本轮 `event.decision.covered`。提示词要求模型每轮重列累计 id，实际模型确实照做了，但这层没有任何代码保证——漏列一个就会把状态打回 `GATHERING`。建议传并集（`covered + event.decision.covered`），成本为零。
- `domain/engine/QuestionEngine.kt:232-238`：`fallbackQuestion` 用的 `covered` 是本轮的，不是累计的，模型漏列时可能重新问已答过的题。
- `usecase/AskFirstFacade.kt:691-696`：`fallbackSheet` 在 session 为 null 时 `ensureSheet` 调了两遍（第二次命中已有记录，无副作用）。
- `domain/research/ResearchRules.kt:58-64`：`cleaned.appliedTo` 在 KNOWN_PIT/COMMON 被降级成 `UNGATHERED` 后**保留原栏位**（col5/col2），而同为 UNGATHERED 的其它路径落到 `APPENDIX`——"没查够"的结论会混进第 2/5 栏。
- `domain/research/ResearchRules.kt:152`：`verdicts.firstOrNull { it.appliedTo.col == Cols.OPTIONS }` 是死分支（`AppliedTo` 根本没有第 3 栏，`col` 只可能是 2/5/null），作为双保险可以留着。
- `usecase/AskFirstFacade.kt:512-517`（离线外包）把 `rechecked` 设成**全部问题**，等于离线导入的「无人区」绕过了换词复核这道门。如果这是有意为之（桌面端查过了），建议在报告顶部把"未在本机复核"写明。

---

# 建议的修复顺序

1. **P0-1**（一行 catch + URL 规范化）——最容易触发、后果最重、改动最小。
2. **P0-2**（给 `sourceMessageIds` 加白名单）——一句话，救回产品的核心承诺。
3. **P1-1**（精确度缺口记账）——决定核心流程能不能收敛。
4. **P1-2 / P1-5**（调研"说了假话"的两处）。
5. 其余按表逐条。

补测试建议：为 P0-1/P0-2/P1-1 各加一个**能红**的单测（目前 256 个用例全绿，恰恰因为这三条都没被测到）：
- `EndpointBuilder` 对无 scheme 输入的处理（纯函数，好写）；
- `QuestionEngine.resolve` 白名单（`FakeLlmProvider` 里塞假 id，断言 delta 被丢或换成真实 id）；
- `StateMachine`：连续两轮，第二轮原话变化后旧缺口应能消解。

---

# 修复与验证记录（2026-11-24）

**动手前备份**：
- git 标签 `backup-pre-fix-20261124-212041`（指向 `022a7a4`）
- 源码压缩包 `F:\安卓\_backup\AskFirstApp-src-20261124-212041.zip`（0.74 MB，不含 build/.gradle/.git）

**单测基线变化**：256 → **279 个用例全绿**（新增 23 条，每条都对准下面的一个修复点）。

## 真机验证（HONOR TFY-AN00 / Android 12）

### P0-1 已修 · 真机复现 + 对照通过

| | 旧包 `dist/askfirst-0.1.0-debug.apk` | 新包（修复后） |
|---|---|---|
| 输入 | `base_url = api.deepseek.com/v1`（漏 scheme） | 同左 |
| 结果 | **FATAL EXCEPTION，进程被杀** | pid 存活，无崩溃 |
| 异常 | `java.lang.IllegalArgumentException: Expected URL scheme 'http' or 'https' but no scheme was found for api.de…`<br>`at okhttp3.Request$Builder.url(Request.kt:184)`<br>`at com.askfirst.remote.OpenAiCompatibleProvider$ping$2.invokeSuspend(OpenAiCompatibleProvider.kt:71)` | — |
| 实际行为 | 建 Request 即崩，用户什么都看不到 | URL 补成 `https://api.deepseek.com/v1` → 真打到 DeepSeek → 收到 401 → 界面显示「模型服务不认这个 Key：Authentication Fails, Your api key: \*\*\*\*-one is invalid… 可能是复制漏了，或者它过期了。」+「❌ 没通，看上面的原因」 |

日志佐证：`LlmPing: ping 401: {"error":{"message":"Authentication Fails, …"}}`。
改的四处：`EndpointBuilder` 加 `normalizeBaseUrl`/`isHttpUrl`；`config()` 校验并补全；
`ping()`/`stream()` 把 `Request.Builder()` 挪进 `try`，`IllegalArgumentException` 映射成 `AppError.Config`。

### P0-2 已修 · 真机端到端 + 直接查库

手法：用 `tools/fake_sse_server.py` 的 `FAKE_BOGUS_SOURCES=1`，让假模型像"照契约办事但拿不到真实 id"
那样回 `source_message_ids: ["msg_fabricated_1","msg_fabricated_2"]`；`adb reverse tcp:8083`，
`base_url=http://127.0.0.1:8083/v1`，跑 `--ez demo_run --ez demo_sheet`（3 轮 + 出单）。

服务端确实发出了编造 id：
```json
"sheet_delta":[{"col":1,"text":"首屏打开就要四秒","source_message_ids":["msg_fabricated_1","msg_fabricated_2"]}]
```

跑完把库拉下来逐条比对（`message` 表 6 条真实 id，`sheet_item` 7 条条目）：

```
col1  首屏 P90 从 4s 降到 1  -> ['m_39d56559bf9b4c5b']
col2  一个人力，两周内上线     -> ['m_985a331562344f76']
col3  选定：本地缓存 + 聚合层  -> ['m_05d4ae8665ae474e']
col3  放弃：只上 CDN（预算不够）-> ['m_05d4ae8665ae474e']
col4  今天：拉首屏接口耗时火焰图 -> ['m_05d4ae8665ae474e']
col4  本周：定聚合层接口契约    -> ['m_05d4ae8665ae474e']
col5  还没定：缓存失效用 TTL   -> ['m_05d4ae8665ae474e']

含编造 id（msg_fabricated_*）的条目 : 0   期望 0    ✅
出处指向不存在消息的条目           : 0   期望 0    ✅
```

也就是说：编造的 id 全部被白名单切掉，退回"按文本重合找原话"的兜底，落库的出处条条都能点开。
**修复前**这 7 条会各带两个 `msg_fabricated_*`，「看条目出处」全是空白。

### P1-7 已修 · 直接查打包产物

```
$ aapt2 dump xmltree --file AndroidManifest.xml app-debug.apk
E: application
  A: android:allowBackup = false          ← 修复前是 true
  A: android:fullBackupContent = @0x7f0d0000
  A: android:dataExtractionRules = @0x7f0d0001
```
另外把两个规则文件都写上了真正的 `<exclude domain="root" path="."/>`，并改掉了"空规则=不备份"那句错注释。

### P1-8 已修 · 仓库扫描

```
$ git grep -n -I -E "sk-[A-Za-z0-9_-]{16,}" -- .
app/src/androidTest/.../StreamingAndroidTest.kt:50:  secrets.putLlmKey("sk-fake-for-stream-test")
```
只剩 androidTest 里的**假** key；`bench_models.py` 改成 `os.environ["MAGPIE_KEY"]`，缺变量时打印设置方法而不是崩溃。

## 纯 JVM 单测覆盖（其余各项）

| 项 | 验证方式 |
|---|---|
| P1-1 状态机永远到不了 READY | `StateMachineTest` 新增 2 条：矛盾**能被消解**、早期歧义词不阻塞后续轮次 |
| P1-2 假「无人区」 | `ResearchPipelineTest` 新增 2 条：复核请求失败时不标记已复核、网络全挂时模型说无人区必须被降级 |
| P1-3 编辑场景换掉问题 id | 新增 `QuestionIdReuseTest`（7 条）：没改沿用、改了换新、位置变了不复用、`""`≡`null` |
| P2-1 SSE 末帧丢失 | 已改（EOF 补喂空行）；`SseParserTest` 原有"末尾缺空行"用例继续通过 |
| P2-2 单行围栏吃 JSON | `Text.stripCodeFence` 统一实现 + 正则兜住单行；`JsonRepair.stripFence` 改为委托 |
| P2-3 `3.5` 被剥成 `5` | `PlanValidatorTest` 新增 1 条：`- 3.5 天上线` 只认 `3.5`，真序号仍剥干净 |
| P2-4 被驳回的判定静默消失 | `normalize().warnings` 两处调用点都并入 `failures` |
| P2-5 空「」 | `ResearchMarkdownTest` 新增 1 条：整轮级失败渲染成 `【整轮调研】` |
| P2-6 空气泡 | 新增 `EngineDecisionTest`（4 条）：有 blockReason 时正文不许为空 |
| P2-7 泛型被当标签吃 | `EvidenceParseTest` 新增 2 条：`Map<String, List<Int>>` 保住、真标签与徽章照剥 |
| P2-8 `userEditedConfirmed` 死分支 | 删掉死字段，规则改到 `AskFirstFacade.editItem` 真正生效 |
| P2-9 自动滚动静止 | 滚到 `lastIndex + headerCount`（编译通过） |
| P2-10 首启闪首页 | `onboardingDone == null` 时渲染占位而非 NavHost |
| P2-11 首页 race / 吞掉灌库失败 | `HomeViewModel` 注入 `seedResult` 并 `await()`，失败映射成首页错误文案 |
| P2-12 测试检索命中缓存说谎 | `GitHubClient.call()` 加 `useCache`，`rateLimit()` 传 false |
| P2-13 主线程解密 | `SecretStore.warmUp()` + `AppContainer` 在 IO 线程预热；设置页读取改 `withContext(Dispatchers.IO)` |
| P2-14 缺 id 的友好提示是死代码 | `SceneSeed.id` 加默认值，`:42-44` 那句中文提示终于可达 |
| P2-15 永真断言 | `SceneJsonCodecTest` 改成"必须是 Err 且提到「至少」" |
| P2-16 改问题白丢输入 | `updateQuestion` 返回 Boolean，UI 校验通过才关弹窗 |
| P2-17 场景保存非原子 | `SceneRepository.write/delete` 包进 `db.withTransaction` |

## 已知未做

- **P2-9 / P2-10 / P2-13 是 UI/线程层面**，单测覆盖不到，只做了编译验证 + 代码审查；要彻底验证需要
  旋转屏幕 / 首启冷启动 / StrictMode 的真机场景，本次没跑。
- `keystore.properties` 里的明文口令仍未处理（已被 `.gitignore` 正确排除、`git ls-files` 确认未入库），
  属于本机文件卫生问题，不在本次代码修复范围。
- 备份里那个 `hs_err_pid18332.log`（743 KB JVM 崩溃日志）是构建期残留，与产品代码无关。

