# Personal Intelligence Contract

> 状态：v1.0 · 冻结 · 本契约管辖**一切**个人智能输出（确定性画像链 + 未来 AI 推理链），是 `PORTRAIT_CONTRACT.md`（Observation Ground Truth）的父集。任何 AI 功能、Provider、插件都必须遵守。
> 位置：`docs/intelligence/PERSONAL_INTELLIGENCE_CONTRACT.md`

## 1. 三层真相模型（核心）

任何一条个人信息必须声明自己的来源层级：

### OBSERVED —— 确定观察到的事实

- 例：「今天开始明显活跃比个人 baseline 晚 53 分钟」
- 来源：Observation Core（Sensing → Features → Aggregate → Baseline）
- 要求：可复现、可追溯（窗口/公式/数据）、携带覆盖度与置信度（统计意义）

### INTERPRETED —— AI 推测

- 例：「这一段可能比通常更紧绷」
- **不是事实。** 每条必须携带：`confidence`、`evidence`、`source`、`timestamp`、`model/version`
- 低 confidence 时：可以轻微影响抽象视觉；**不应**形成明确用户语言；**不应**触发干预；**不应**写入强记忆

### FELT —— 用户自己告诉 ECHO 的感受

- 例：「其实我今天特别开心。」
- 规则：**Felt outranks passive interpretation.** 永远不要让 AI 推断覆盖用户自己的陈述。
- 用户主观状态，AI 不得「纠正」。

## 2. Confidence 是一等公民

任何 interpretation 必须携带：`value`、`confidence`、`sources`、`evidence`、`age`。

允许的三类不确定态：

```text
UNKNOWN
INSUFFICIENT_EVIDENCE
CONFLICTING_EVIDENCE
```

置信度门（默认，可被具体 ReasoningTask 收紧）：

| 置信度 | 视觉影响 | 用户语言 | 干预 | 强记忆 |
|---|---|---|---|---|
| 低 | 允许（抽象、弥散） | 禁止 | 禁止 | 禁止 |
| 中 | 允许 | 允许（带不确定性措辞 + evidence） | 禁止 | 仅临时 |
| 高 | 允许 | 允许 | 仅 L1/L2 且经 Intervention Policy | 允许（可衰减） |

## 3. Provenance（来源可追溯）

- 每条记忆/叙事/回答必须能回答：「这来自哪里？」——观察窗口、baseline 版本、模型与版本、用户陈述、纠错记录。
- 用户可见的每个洞察必须能展开成 evidence（见 Explainability）。
- 禁止把 provenance 当工程噪音藏起来；禁止用「AI 觉得」作为证据。

## 4. 禁区（永久保留，来自 PORTRAIT_CONTRACT_V1_OBSERVATION 并扩展）

- 被动数据不得推断情绪、自杀意图或任何精神疾病；Safety / 人工 Support 与画像链**不自动连接**。
- 禁止评价性命名：GOOD/BAD/HEALTHY/NORMAL/ABNORMAL；只用相对取值（LESS/SIMILAR/MORE、EARLIER/LATER）。
- Me vs Me：禁止用群体平均值判断个体是否正常。
- 语言禁令词表（CI 强制）：焦虑、抑郁、孤独、压力过大、情绪低落、社交退缩、心理异常、心理风险、精神疾病、自杀、自伤 + PRESENCE 禁令（「我正在监测你」「我检测到你」「我发现你现在」）。
- 禁止医疗诊断宣称；禁止把视觉变化描述成医学结论；禁止根据低置信度状态制造严重警报。

## 5. Memory 契约

### 5.1 分类（Memory ≠ 聊天记录）

```text
Observation Memory      来自 Observation Core 的事实沉淀
Context Memory          用户确认的生活上下文（出差/备考/休假…）
User-confirmed Memory   用户明确确认过的事实
Preference Memory       用户偏好（提醒方式/视觉偏好/模型设置…）
Correction Memory       用户纠错记录（prediction ≠ feedback 及其原因）
Derived Pattern Memory  长期稳定模式的提炼
Temporary Interpretation  短时推测（不落强记忆，自动过期）
```

### 5.2 必带字段

```text
id / type / content / source / confidence / createdAt /
lastConfirmedAt / importance / retentionClass / provenance
```

### 5.3 生命周期

支持：`decay`（衰减）、`reinforcement`（确认强化）、`expiry`（过期）、`delete`（删除）、`user edit`（用户编辑）。

### 5.4 记忆价值门槛

「今天 14:23 切换 App 频繁」→ 不值得记。
「连续数周工作日晚上明显更晚结束」→ Pattern Memory。
「最近一个月在准备考试」（用户说）→ Context Memory。
记忆被写入强记忆前必须通过：evidence + confidence + 可解释 + 可删除。

### 5.5 用户控制

Me → **What ECHO Knows About Me**：按人类可理解的方式展示（你的节律 / 最近变化 / 我已确认的 / 我还不确定的 / 你纠正过我的），支持 correct / forget / edit / confirm。

## 6. Context 契约

- 系统第一次看到异常时，允许且必须允许 `UNKNOWN_CONTEXT`；**禁止假装知道原因。**
- 用户可以告诉 ECHO：「今天在出差。」→ 记录为 `context exception`（时间窗 + 原因 + 用户来源）。
- 上下文候选：work-like / home-like / travel / holiday / weekend / sick-day / event-day / unusual-day / user-defined。
- 上下文只影响解释，不改变 Observation 事实。

## 7. 纠错契约

- 每条较高级判断都允许：👍 像我 / 👎 不太像。
- 「不太像」可选快速原因：工作 / 旅行 / 假期 / 身体不舒服 / 特殊事件 / 只是很专注 / 不想说 / 其他。
- 纠错进入 Correction Memory，之后推理**必须**可检索。
- 系统记录 `prediction ≠ user feedback`，但用户反馈优先（Felt 优先）。

## 8. Provider 边界

- LLM Provider ≠ ECHO（见 Product Constitution §3.1）。
- 模型默认**不拥有数据库访问权**。任何 LLM 请求必须经 `EchoContextCompiler`：Task → Context Policy → 检索相关 evidence/memory/corrections → 剔除禁止数据 → 应用 Privacy Budget → 编译最小上下文 → Provider。
- 不把整个用户数据库塞进 LLM。Personal AI 的价值来自 **Relevant context**，不是 **Maximum context**。
- BYOM 默认网络链路：Android Device → User-selected Provider（ECHO server 默认不知道用户 API Key）。

## 9. Privacy Budget（每个 ReasoningTask 必须定义）

```text
allowed data / prohibited data / retention / provider eligibility
```

示例（EXPLAIN_DAILY_RHYTHM）：
- allowed：DailyAggregate、baseline、相关 context exception
- prohibited：raw notification content、raw audio、无关 memory

## 10. Explainability（可解释个人 AI）

回答提供「依据」，用户可展开：

```text
这次回答参考了：
✓ 今天的活动节律
✓ 最近 28 天个人 baseline
✓ 最近确认的特殊日期
没有使用：
○ 麦克风
○ 消息正文
○ 精确位置
```

目标：任何 AI 输出都能回答「为什么这么说」。

## 11. Fallback Chain（AI 失败不得破坏 ECHO）

```text
AI generated narrative
   ↓ failure
deterministic narrative（现有确定性模板）
   ↓ failure
observation facts
```

用户永远不面对完全空白。Provider key 错误 / quota 用尽 / 网络失败 / 结构化输出错误 → 只降级 deep reasoning / conversation / AI narrative；Observation Core / Presence / Portrait / Journey 继续工作。

## 12. 版本与变更

本契约变更需产品评审。实现侧任何违反本契约的代码必须被 CI（词表断言 + provenance 断言）拦截。
