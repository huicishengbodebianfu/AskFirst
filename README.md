# AskFirst

> 一个会反过来问你的 App。开工前先打开它，它按场景一轮一轮追问你；你说「够了」，它把整场聊天压成一份能直接开工的思路确认单。

Android 原生（Kotlin + Compose + Room）。**数据全在本机**，模型走你自己填的 OpenAI 兼容 API。

它不做的事，比做的事更能说明它是什么：

- 不替你给方案、不列建议清单、不替你拍板；
- 不编造：确认单里每一条都能点开看「你当时那句原话」；
- 不催你停：停不停由你决定，它只负责问对问题。

## 功能

| | 内容 |
|---|---|
| 提问引擎 | 四状态机（收集 / 精确度 / 阻断 / 可出单）+ 四项离线精确度检查（目标漂移、隐藏默认值、场景走查、矛盾扫描） |
| 五栏确认单 | 目标与成功标准 / 约束与前提 / 选定方案与被否备选 / 第一步动作清单 / 还没想清楚的问题，条目实时生长、可手改、可溯源 |
| 历史 | 全部会话可回看、按场景筛选、接着深挖、导出 Markdown |
| 场景 | 3 套内置（技术方案 / 项目立项 / 复盘）+ 自建编辑器 + JSON 导入导出 |
| 跨场景引用 | 挑几份旧结论带进新会话（≤3 份），引擎只读摘要 |
| 方案文档 | 按场景骨架逐节生成 `方案.md`，段落带脚注溯源，存进你选的文件夹 |
| 调研验证 | GitHub 公开检索取证 + 四判定（常见做法 / 反模式 / 已知坑 / 无人区），结论只许进第 2、5 栏 |

## 快速开始

需要 JDK 17、Android SDK（compileSdk 36）。

```bash
git clone https://github.com/huicishengbodebianfu/AskFirst.git && cd AskFirst
./gradlew :app:assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 纯 JVM 单测（不需要设备）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

第一次打开会有一页引导。点「去填 Key」填三样东西，再点「测试连通」看到 ✅ 就能用：

| 字段 | 说明 |
|---|---|
| `base_url` | 到 `/v1` 为止，例如 `https://api.deepseek.com/v1`（**漏写 `https://` 会自动补上**） |
| `api_key` | 你自己在服务商控制台创建的 Key |
| `model` | 模型名，例如 `deepseek-chat`。不知道填什么就点 **「拉取模型列表」**，从网关实际返回的模型里点一个 |

想换模型随时回设置页点「拉取模型列表」——它会 `GET {base_url}/models`，把网关上可用的模型列出来，点一个就换上（自动落库，不用再点保存）。

> 便携网关 / 自签证书：release 构建只许 HTTPS 但**允许用户安装的 CA**；debug 构建额外放行明文。
> 详见 [docs/17-网络与证书配置.md](docs/17-网络与证书配置.md)。

## 个人自用配置（可选，不进版本库）

仓库里**没有任何凭据**。如果你想要「装上就能用、不用每次填四样」，在本地建两个文件（都已在 `.gitignore` 里）：

1. `自用/personal.properties`：

   ```properties
   base_url=https://your-gateway:3425/v1
   model=your-model
   api_key=sk-...
   github_pat=ghp_...
   ```

2. 其它私人材料（网关 CA、接入说明）也放 `自用/` 下。

构建时 `app/build.gradle.kts` 会把它们注入 `BuildConfig.PERSONAL_*`，由
[`PersonalDefaults`](app/src/main/java/com/askfirst/local/PersonalDefaults.kt) 读取。
**源码树里不会出现明文**；没有这两个文件时四项全为空，App 就要求你自己填。
优先级永远是：**设置页填过的 > 这个文件里的**。

想彻底关掉这层：把 `PersonalDefaults.ENABLED` 改成 `false`。

## 工程结构

```
app/src/main/java/com/askfirst/
├── core/       结果类型、日志、时钟、id、文本处理（无 Android 依赖）
├── data/       Room 实体/DAO/仓库、DataStore 设置、Keystore 密钥存储
├── domain/     纯 Kotlin：引擎、状态机、方案、调研规则、确认单规则、提示词组装
│               └── 这一层不碰 Context、不碰 android.util.Log，必须能纯 JVM 单测
├── remote/     OpenAI 兼容通道（SSE 流式、模型列表、错误映射）
├── research/   GitHub 取证（退避重试、24h 缓存、token 白名单 host）
└── ui/         Compose 界面 + ViewModel（唯一入口是 domain/usecase/AskFirstFacade）
```

纪律：**引擎与解析层是纯 Kotlin**，所以 `docs/` 里那些"真机验收"之外的东西几乎都能在单测里撞边界
（当前 288 个用例，`./gradlew :app:testDebugUnitTest` 秒级跑完）。

## 文档

`docs/` 里有完整的施工与验收记录，从数据模型、引擎契约到每个里程碑的真机验收：

| 从哪读起 | 内容 |
|---|---|
| [docs/01-M1-施工清单.md](docs/01-M1-施工清单.md) | 主文档：范围、目录树到文件、函数签名、开发顺序、风险 |
| [docs/03-数据模型与DAO.md](docs/03-数据模型与DAO.md) | 表结构、实体、DAO、迁移策略 |
| [docs/04-引擎契约与提示词.md](docs/04-引擎契约与提示词.md) | 引擎 JSON 契约、四状态机、精确度检查、容错解析 |
| [docs/17-网络与证书配置.md](docs/17-网络与证书配置.md) | `network_security_config`：release 允许用户 CA，debug 放行明文 |
| [docs/28-代码审查-Bug清单.md](docs/28-代码审查-Bug清单.md) | 一次逐文件审查的产物：2 个 P0、8 个 P1、17 个 P2，含修法与验证记录 |
| [docs/spec/](docs/spec/) | 原始调研与产品方案（只读参考） |

> 文档里的绝对路径是原作者机器上的布局，替换成你自己的即可。

## 隐私

- 会话、确认单、方案、调研记录**只存在本机**；没有账号、没有云端、没有遥测。
- `android:allowBackup="false"`：不参与云备份与设备迁移（数据库不会跟着账号走）。
- API Key 存 `EncryptedSharedPreferences`（Android Keystore 主密钥），界面与日志只出现后 4 位。
- 请求只发往你自己填的地址；GitHub 取证只发白名单 host，第三方文件域（raw 等）一律不带 token。

## 已知限制

- 只做实机验收（HONOR TFY-AN00 / Android 12），没有平板/横屏适配。
- 模型输出必须是可以容错解析的 JSON；两次都解析不出来会明确报错并保留原文（不会假装成功）。
- 调研限 60 秒硬预算，超了就用手里的证据出残本，并如实标「证据覆盖 N/M」。

## 参与

Issue / PR 都欢迎。两条基本要求：

1. `./gradlew :app:testDebugUnitTest` 必须全绿，新逻辑请补纯 JVM 单测；
2. 界面文案不许出现内部术语（门槛、状态机、精确度、维度、覆盖…），词表见
   [app/src/main/assets/prompts/glossary_ban.md](app/src/main/assets/prompts/glossary_ban.md)。

## 许可证

[MIT](LICENSE)
