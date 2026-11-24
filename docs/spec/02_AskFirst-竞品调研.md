# AskFirst 竞品调研（2026-11-23）

方法：GitHub Search API（repo 检索 + 仓库详情）+ 网页检索。约 20 组关键词。

## 一句话结论

**你想要的机制在开发者圈子里已经非常流行，但它全部以「一段提示词文本」的形态存在
——塞在编码 agent 里，跑在桌面/终端。手机上的独立 App 形态目前是空的。**

这同时是好消息和最坏的坏消息：需求被真实验证过（几百万下载量级），
但你的直接竞品不是另一款 App，是「复制 5 行字贴进 ChatGPT」。

---

## 一、同一机制：Agent 技能 / 提示词（最危险的竞品）

| 项目 | 量级 | 机制 |
|------|------|------|
| `mattpocock/skills` → **grill-me** | 仓库 33 万 star，技能本身传播量以百万计 | 原文只有 5 行：「 relentlessly 面试我直到达成共识，沿决策树逐个分支解决依赖，**一次只问一题**，每个问题**附上你推荐的答案**，能从代码库查到的别问我」 |
| `obra/superpowers` → brainstorming | 32 万 star | 头脑风暴 → 直接产出 spec 文档 |
| `hhellbusch/ask-me-questions` | 小 | 自适应提问澄清 goal/scope/constraints |
| `Vann4799/ai-prd-generator` | 24 | 「温暖的对话式访谈」→ PRD |
| `scalershare/prd-generator` | 18 | 中文，生产级 PRD，给非技术用户 |
| `Hugo-DDT/clarify-intent` | 20 | 反方向：**教 agent 什么时候别面试人**，最小提问 |
| `TimYuann/Talk-like-a-pro` | 7 | 中文。「帮我弄一下 X」→ 精确 prompt，上下文探测 + 澄清提问，**never blocks** |
| `9thLevelSoftware/legion:questioning-flow` | 72 | 提问 → 生成 PROJECT.md / ROADMAP.md / STATE.md |
| `jasper0507/idea-harness` | 4 | 「给 AI 套上缰绳，别让它猜」：模糊点子 → 精确需求，苏格拉底式 |
| `phong-baruby/ba-skills` | 10 | 访谈 + 结构化 + **逻辑校验需求**，"别再写没人看的 100 页 SRS" |
| `CyberZenithX/Rich-Elicitation-Skill` | 9 | 多轮诱导，开工前消除歧义 |
| `Vlad175cm/task-understanding` | 1 | OpenCode skill：问对问题 + 写详细计划，然后才动代码 |

**读这张表的三个要点**

1. 这套方法已经被反复独立发明，说明痛点是真的，不是你想多了。
2. grill-me 能靠 5 行字病毒式传播，说明**这功能的护城河不在提示词本身**。
3. `legion:questioning-flow`（提问→三份文档）几乎就是你 M1 + M5 的组合，
   但它是终端里的文件，没有历史、没有积累、没有手机。

---

## 二、Spec-Driven Development：同目的，反机制

`Fission-AI/OpenSpec`（7.5 万 star，今天还在推）、`spec-workflow-mcp`（4.3k）、
Gemini `conductor`（3.8k）、`spec-kitty`（1.7k）、`shotgun`、`cabloy`。

**它们和 AskFirst 的根本差别**：SDD 是 **AI 起草 spec → 人 review 改**；
AskFirst 是 **AI 提问 → 人说 → 从人的话里长文档**。

方向相反。SDD 默认「你想不清楚，我先写一版你改」；
你赌的是「你想得清楚，只是没人问」。

这不需要打败它们，是两种世界观。但可以偷它们验证过的东西：
所有 SDD 工具都把 **spec 落到仓库里、可版本化、可被下游 agent 消费**——
这正是你「复制为提示词」应该做重的原因。

---

## 三、已经产品化的独立应用（真正的同类竞品）

| 产品 | 形态 | 与 AskFirst 的距离 |
|------|------|-------------------|
| **Co-Create**（co-create.build，内含 Remy） | Web/桌面 | 最近的同类：「Answer clarifying questions before coding」「用 Ask ?s 把一条 bullet 变成完整 spec」。**但它嵌在 app builder 里，提问是为了它自己造东西**，不是给你带走 |
| **Clarity: AI Thinking Coach**（iOS 6759827041，中日美区都在推） | iOS App | 定位「思考整理教练」，偏日记/自我梳理，**不产出可交付物**。是你要做的东西的「心理按摩版」 |
| `richer-richard/socratic-council` | 本地桌面 | 16 个 agent 辩论 + 论点地图。方向是「多方对撞」，不是「一线索逼问」 |
| `willster000/Socratic-Dialogue-Assistant` | Web | **和你的形状几乎一样**：对话 → 生成文章草稿。只是受众是世界观探索 |
| `nesquikm/mcp-rubber-duck` / `duck-e` / `DebugDuck` / `centsandcode/rubber-duck`（183/57/11/15） | MCP / 桌面 / 云 | 橡皮鸭调试这一大类：**「别给我答案，帮我自己找到」**。是同一需求的另一种满足 |
| Android 客户端：`Operit`（9.8k）、`gpt_mobile`（1.3k）、`Muse`、`aether` | Android | BYOK Android 壳非常拥挤，但**没有一个是提问工作流**。你的差异化不在客户端 |

**空白确认**：`android compose kotlin llm chat` + 「提问 / 苏格拉底 / 需求澄清」
交叉检索，结果为 0。移动端 + 提问工作流 + 确认单资产库 = 当前没人做。

---

## 四、可以立刻偷的 4 条设计（grill-me 已验证）

| # | 别人的做法 | 对 AskFirst 的影响 |
|---|-----------|-------------------|
| 1 | **一次只问一题，绝不打包** | ⚠️ 与你已定的「每轮 2~4 题」直接冲突。手机上一次答 4 题是灾难 |
| 2 | **每个问题附上 AI 的推荐答案** | ⚠️ 直击风险表里「用户懒得答」。做成「点一下接受 / 改一下」，打字量降一个数量级 |
| 3 | **沿决策树 depth-first，一个分支聊透再开下一个** | 你现在的「覆盖判定」是平铺的，应该改成带依赖关系的问题树 |
| 4 | **能自己查到的别问用户** | 移动端等价物：**历史确认单和跨场景引用能答的，不要问我要**。这条让你的 M4 不只是搜索框 |

---

## 五、差异化：为什么这不是「多此一举的 App」

一段 5 行提示词做不到的四件事，就是你的产品：

1. **积累。** 提示词每次从 0 开始；你的 App 三个月后有 50 份确认单，
   第 51 次提问能引上前 50 次的结论。这是唯一真正的护城河。
2. **场景化。** grill-me 的提问是无结构的；你有三套 + 自建的问题树。
3. **交付物。** 提示词结束后什么都留不下；你有确认单 + 方案.md + 提示词。
4. **手机上随手能开工。** 终端里的 skill 要求你已经在电脑前。

## 六、三条新增风险（调研带回来的）

- **替代品是免费的 5 行字。** 亲友装了你之后可能还是去贴提示词。
  → 首启那次必须让 TA 感受到「引上一次结论」的差别，否则留不住
- **整个赛道在向「问完直接造」坍缩**（Co-Create / Remy / SDD 都是）。
  一个只问不做、把成品交给别的 AI 的工具，位置偏弱。
  → 已对冲：「复制为提示词」是第一出口。这条别改
- **提问质量是纯提示词工程**，别人免费开源的 skill 就是你的效果基线。
  → M1 该拿 grill-me / idea-harness 的原文做 A/B 对照，别自己闭门写
