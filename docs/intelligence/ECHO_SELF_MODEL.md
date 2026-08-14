# EchoSelfModel —— 目标领域模型

> 状态：v1.0 · 目标架构定义（ERA 6 实现）· 本文件定义「ECHO 是谁」的正式领域模型，禁止实现成大 JSON Blob。
> 位置：`docs/intelligence/ECHO_SELF_MODEL.md`

## 1. 总图

```text
REAL WORLD
    │
    ├── Passive signals
    ├── System events
    ├── Explicit user input
    └── User corrections
            │
            ↓
    Observation Engine          （现状：PassiveSensingService + FeatureExtractor + localportrait）
            ↓
     Feature Engine             （现状：22 维派生特征 / 5 分钟窗口）
            ↓
    Personal Baseline           （现状：28 天 robust baseline，Me vs Me）
            ↓
      Context Engine            （新增：上下文例外、UNKNOWN_CONTEXT）
            ↓
      EchoSelfModel             （本文件）
      /          \
     ↓            ↓
 EchoNow       EchoMemory
     \            /
      ↓          ↓
   Context Compiler            （LLM 请求前唯一通道）
          ↓
   Reasoning Runtime           （typed reasoning tasks）
          ↓
   Echo Intelligence
          ↓
 ┌────────┼───────────┐
 ↓        ↓           ↓
Scene   Narrative   Actions
```

## 2. EchoSelfModel 逻辑领域

| 领域 | 内容 | 现状映射 | 实现期 |
|---|---|---|---|
| `RhythmModel` | 日间/夜间节律、活跃起止、屏幕/移动节律、工作日/周末、重复周期、季节变化 | `baseline/`（28 天）+ `LocalBaselineCalculator` | 已有（ERA 6 扩展） |
| `ContextModel` | work-like/home-like/travel/holiday/weekend/sick-day/event-day/unusual-day/user-defined + UNKNOWN_CONTEXT | 无 | ERA 6 |
| `BehaviorModel` | 行为模式与 deviation 解释 | aggregate + dimensions（部分） | ERA 6 |
| `PreferenceModel` | 用户偏好（提醒/视觉/模型/通知容忍） | `AppPreferences`（分散） | ERA 6 |
| `MemoryModel` | 见 PERSONAL_INTELLIGENCE_CONTRACT §5（7 类记忆 + 生命周期） | 无（现仅画像反馈本地记录） | ERA 6 |
| `AffectiveModel` | 可选情绪智能（连续 latent state，非 HAPPY/SAD 标签） | 无（契约禁止，待 AFFECTIVE_CONTRACT） | ERA 10 |
| `InteractionModel` | 纠错吸收、交互偏好 | feedback 已有（reason 待加） | ERA 6/9 |
| `PresenceModel` | Identity Genome / Life Season / Daily Composition / Moment | 无 | ERA 2 |

## 3. EchoNow / EchoMemory

- `EchoNow`：当前状态快照（= EchoPresenceState 的认知部分：rhythm/behavior/context/confidence + moment）。
- `EchoMemory`：跨时间沉淀（Observation/Context/User-confirmed/Preference/Correction/Derived Pattern/Temporary）。
- 二者都由 EchoSelfModel 提供；Context Compiler 只从这里取料，不从原始数据库取料。

## 4. Context Compiler（核心资产）

```text
Reasoning Task
↓
Context Policy（该任务的 allowed/prohibited/privacy budget）
↓
Retrieve relevant evidence（Observation Core 派生）
↓
Retrieve relevant memory（EchoMemory）
↓
Retrieve relevant user corrections（Correction Memory）
↓
Remove prohibited data
↓
Apply privacy budget
↓
Compile minimal context
↓
Provider
```

模型默认**不拥有数据库访问权**。

## 5. AffectiveModel 表示原则（ERA 10 才允许激活）

禁止 `HAPPY/SAD/ANXIOUS` 标签作为主要内部表示。优先连续 latent state：

```text
activation / pleasantness / tension / mental_load /
social_load / recovery_need / certainty
```

每个值独立携带 `confidence / source / updatedAt`。状态表达必须支持「不确定」。用户自述（Felt）永远覆盖推断。

## 6. Reasoning Tasks（typed，不是一个大 Prompt）

```text
GENERATE_NOW_INTERPRETATION
EXPLAIN_CURRENT_STATE
FIND_LONGITUDINAL_PATTERN
ANSWER_PERSONAL_QUESTION
SUMMARIZE_WEEK / SUMMARIZE_MONTH
SYNTHESIZE_MEMORY
CLASSIFY_MEMORY_VALUE
PROPOSE_ACTION
INTERPRET_USER_CORRECTION
```

每个任务有独立：context policy、privacy budget、output schema、model requirement、confidence threshold。详见 `docs/intelligence/REASONING_TASKS.md`（待建立）。

## 7. 实现纪律

- 逻辑角色分开，但**不滥用多 Agent**：Observer/Pattern/Narrative/Memory 是不同 Contract 的 typed task，同一模型即可执行。
- 领域模型变更必须带 migration（见 `docs/architecture/MIGRATION_ARCHITECTURE.md`）。
- 禁止把 EchoSelfModel 持久化为一个大 JSON：按领域分表/分文档，provenance 独立。
