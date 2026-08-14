# Phase 6 — Onboarding 重构 + Permission Degraded + Narrative / Explainability / Today / Trend UX 规格

> 作者：产品经理 许清楚（Alice）｜版本：v0.7 Phase 6｜状态：规格冻结（供工程师实现，不写代码）
> **状态：SUPERSEDED** —— onboarding 已重构为三步 + ECHO Awakening（ERA 1）；本文件仅历史参考。
> 上游输入：`PORTRAIT_CONTRACT.md`（最高产品契约）、`docs/current/00_Phase0_Fresh_Truth_Audit.md`（事实基线 Phase 6.x 发现）、`docs/15_Onboarding产品化规格_v0.6.md`（历史规格）、`android/.../ui/OnboardingScreen.kt`、`android/.../ui/TodayScreen.kt`、`android/.../ui/OtherScreens.kt`、`android/.../sensing/PassiveSensingService.kt`、`backend/app/services/portrait/{narrative,explain,dimensions}.py`、`backend/app/api/portraits.py`
> 产出物：本规格文档（用户可见文案与内部字段分离；文案给「最终用户可见文本」，字段给「内部字段」）

---

## 0. 目标与范围

### 0.1 目标

让 Portrait Core 的主链（Passive Sensing → Derived Features → DailyBehaviorAggregate → PersonalBaseline → DailyPortrait → Today → 7/28 Day Timeline）在首次使用到日常使用全流程中，**与产品契约完全一致**：

1. Onboarding 不再用 L0 / 心理量表 / 心理健康记录话术阻断 Portrait 普通流程；主流程围绕「被动行为节律 → 个人基线 → 每日画像」展开；
2. 权限缺失不再「全有或全无」：核心 Portrait 在传感器可用时即可工作，缺失的只是 optional source 与覆盖度；
3. Narrative 语言通过 Psychology Review 词表（ALLOW / REWRITE / BLOCK）审核，禁止任何心理状态推断；
4. Explainability 展示「事实」而非「指标」，禁止 `movement_index = 0.47`、禁止 near-zero baseline 下的 `+900%`；
5. Today 保持 Portrait First，无假交互；Trend 脱离 legacy Profile；
6. Portrait feedback 走 Outbox 可靠同步；时间线 identity 用服务器 `local_date`。

### 0.2 不在范围

- 不新增心理量表 / 情绪识别 / 主动签到范式；
- 不改 Safety / 人工 Support 链（仅做入口与文案解耦，不改变接管逻辑）；
- 不重写后端画像引擎（仅语言模板与 fact 输出调整 + feedback 端点新增）；
- 不做新的机构后台功能。

### 0.3 最高产品契约（本规格一切决策的判据）

> **Observation，不做 Psychological Interpretation。**
> 画像只比较「今天的你」与「通常的你」，禁止使用全体用户平均值判断个体是否正常（Me vs Me）。
> 维度取值禁止评价性命名（GOOD/BAD/HEALTHY/NORMAL/ABNORMAL），只允许相对性取值。
> 系统必须允许「不知道」：数据不足时 abstain，不硬生成画像。
> 每一句画像文案都必须可追溯（能回答「为什么这么说？」），展示的是事实而非推理。
> 被动感知数据不得用于推断情绪、自杀意图或任何精神疾病；Safety / 人工 Support 与画像链不自动连接。

---

## 1. Onboarding 新主流程（P0）

### 1.1 现状问题（Phase 0 审计结论，须修复）

| # | 问题 | 证据 | 修复方向 |
|---|---|---|---|
| 1 | L0 阻断普通 Portrait onboarding（`WELCOME→CONSENTS→L0→EMERGENCY→DONE`） | `OnboardingScreen.kt` L29、L249-271 | L0 从普通流程**解耦**，仅 Support 用户主动进入时做必要安全流程 |
| 2 | 文案「ECHO Mind 是心理健康记录、筛查提示和审核练习工具」 | `OnboardingScreen.kt` L196 | 改为 Portrait Core 定位文案 |
| 3 | 「我同意处理心理记录与量表信息（核心必选）」 | `OnboardingScreen.kt` L227 | 移除；核心同意围绕被动行为节律 / 派生数据 / 基线 / 画像 |
| 4 | 被动感知标「可选」 | `OnboardingScreen.kt` L231 | 改为**核心同意**（可拒绝，但拒绝即 abstain） |
| 5 | 感知启动三重门控全有才启动 | `PassiveSensingService.passiveSensingGatePasses` | 改为能力降级模型（见 §2），SENSOR 可用即启动 |
| 6 | 紧急联系人步骤阻断流程（可填可不填但占一整个 step） | `OnboardingScreen.kt` L273-288 | 移出主流程，收进「支持」页常驻入口；Onboarding 只保留「紧急入口始终可访问」 |

### 1.2 新主流程（六步）

```text
WELCOME → PORTRAIT EXPLANATION → CORE DATA CONSENT → MINIMUM SENSING → BASELINE WARMING UP → DONE
```

与既有七态状态机（`AppPreferences.kt` L316-327）的映射：

| 新步骤 | 对应状态机推进 | 服务端动作 |
|---|---|---|
| WELCOME（含激活码交换） | NOT_STARTED → ACTIVATING → BOUND | `POST /v1/onboarding/verify-code` |
| PORTRAIT EXPLANATION | （BOUND，无状态变更，纯信息） | 无 |
| CORE DATA CONSENT | BOUND → CONSENT_PENDING（本地已记录） | `POST /v1/onboarding/consents`（portrait_core / passive_sensing） |
| MINIMUM SENSING | （CONSENT_PENDING，渐进授权） | 无（本地能力状态） |
| BASELINE WARMING UP | （CONSENT_PENDING，纯信息） | 无 |
| DONE | CONSENT_PENDING → READY_OFFLINE → READY | 同步收敛后 `serverActivated=true` |

> 建议（采纳任务要求）：**激活码交换保留为 WELCOME 内第一步**——机构激活 → 进入流程。理由：激活是机构试点身份前提，先交换可拿到 `user_id + 短时 token`，后续 consent 证据才能带身份落库；同时保留断网后可离线完成的既有语义（BOUND 已持久化）。

### 1.3 各步骤规格

#### 1.3.1 WELCOME

用户可见文案（最终用户可见文本，必须包含以下核心句）：

> **「ECHO 会在你授权后安静地学习你的日常生活节奏。积累几天以后，它会告诉你今天和平常的自己有什么不同。它不会判断你的情绪，也不会做心理诊断。」**

页面元素：

| 元素 | 类型 | 用户可见文本 | 内部字段 / 行为 |
|---|---|---|---|
| 欢迎核心句 | Text | 见上 | 无 |
| 年龄确认 | Checkbox | 「我已年满 18 周岁」 | `ageConfirmed: Boolean` |
| 边界确认 | Checkbox | 「我理解专业判断和危机处置由人工承担」 | `boundaryConfirmed: Boolean` |
| 机构激活（第一步） | 输入框 + 按钮 | 「请输入机构提供的激活码（由你的机构发放）。」「验证并继续」/「正在验证机构激活信息…」 | `verifyCode()` → `repository.verifyOnboardingCode(code)`；错误文案沿用现有 `invalid_code / restricted / network` 三态 |
| 紧急入口（常驻） | TextButton | 「存在立即危险时，请直接联系身边可信任的人、机构值班人员、110 或 120。」 | 任何步骤可访问 → 打开 SafetyScreen |

#### 1.3.2 PORTRAIT EXPLANATION

用户可见文案（信息页，单一「下一步」按钮）：

> **「ECHO 只比较今天的你和通常的你（Me vs Me）。它不用其他人的平均水平来判断你，也不会把行为数据解读成你的心理状态。」**
> **「积累 7 天后你可以看到一周的趋势，积累 28 天后可以看到更长的时间线。」**
> **「所有同意都可以随时撤回；撤回后 ECHO 会停止学习，已生成的画像会保留在你可管理的范围内。」**

页面元素：

| 元素 | 类型 | 用户可见文本 | 内部字段 / 行为 |
|---|---|---|---|
| 概念图 | 静态图/列表 | 今天的我 vs 通常的我 → 差异 | 无（不做心理解释） |
| 数据链路说明 | 列表 | 被动行为节律 → 个人基线 → 每日画像 | 无 |
| 时间线说明 | 列表 | 「7 / 28 天时间线」 | 无 |
| 可撤回说明 | 列表 | 「所有同意可随时撤回」 | 无 |
| 下一步 | Button | 「继续」 | `step = CORE_DATA_CONSENT` |

#### 1.3.3 CORE DATA CONSENT

用户可见文案（核心同意页）：

> **「为了让 ECHO 能了解你的日常节奏，需要你同意处理以下数据：」**

| 同意项 | 用户可见文本 | 内部字段 | 是否核心 |
|---|---|---|---|
| 被动行为节律 | 「授权 ECHO 在后台采集加速度 / 陀螺仪等运动传感器数据，用于了解你一天的移动与作息节奏。」 | `passiveSensingConsent`（复用 `saveConsent(consentType="passive_sensing")`） | **核心必选**（拒绝则 Portrait abstain） |
| 派生数据 | 「传感器数据只在本机处理成行为摘要（如移动量、屏幕使用时长、应用切换次数），原始传感器数据不落盘、不上传。」 | 同 passive_sensing consent 说明 | 核心 |
| Personal Baseline | 「ECHO 会用你过去几天的数据学习「通常的你」，形成个人基线。」 | 无独立 consent（随被动感知） | 核心 |
| Daily Portrait | 「每天生成「今天的你 vs 通常的你」的画像描述，只做行为观察，不做心理诊断。」 | 无独立 consent（随被动感知） | 核心 |
| 数据保留与撤回 | 「你可以随时撤回同意、申请导出或删除数据；撤回后 ECHO 停止学习。」 | `serviceRevocation` 链路 | 核心 |

**移除项**（不再作为 Portrait 核心数据描述）：
- ❌ 「我同意处理心理记录与量表信息（核心必选）」——量表/Journal 为 legacy 或外围，**不得出现在 Portrait onboarding 核心同意**。
- ❌ 「授权麦克风派生特征（可选，默认关闭）」——MIC 从本页移出，放到 MINIMUM SENSING 的渐进授权最后一步（永远可选）。

页面行为：所有核心同意项必须**同时勾选**才能继续（单一「同意并继续」按钮）；任一未勾选时按钮禁用，并展示：

> 「如果不授权这些数据，ECHO 将无法生成你的每日画像。」

（不阻止用户离开；拒绝 = abstain，不是阻断。）

#### 1.3.4 MINIMUM SENSING

用户可见文案：

> **「ECHO 只需要最少的权限就能开始工作。以下权限可以逐步开启，缺失的部分只会让画像少一些细节，不会让 ECHO 停止。」**

渐进授权顺序（见 §2.4 的授权顺序规格）：

| 顺序 | 能力 | 用户可见文本 | 可否跳过 |
|---|---|---|---|
| 1 | SENSOR（加速度/陀螺仪） | 「运动传感器（加速度 / 陀螺仪）：用于了解移动与作息节奏。这是 ECHO 的核心。」 | 不可跳过（核心） |
| 2 | SCREEN（屏幕状态） | 「屏幕状态：用于了解一天中的屏幕使用分布。无需额外权限。」 | 不可跳过（核心，无权限） |
| 3 | USAGE（使用情况访问） | 「应用使用情况：用于了解你切换应用的次数与最常使用的应用时长（不读取应用内容）。」 | 可跳过（optional source） |
| 4 | NOTIFICATION（通知使用权） | 「通知使用情况：只统计通知数量与类别，不读取通知内容。」 | 可跳过（optional source） |
| 5 | MIC（麦克风派生特征） | 「麦克风：可选，默认关闭。开启后仅在本机提取音量 / 语速等特征，不记录、不上传录音。」 | 可跳过（永远 optional） |

页面元素：每个能力一行（能力名 + 说明 + 状态 + 授权按钮 / 跳过）；底部「继续」在 SENSOR 可用或用户确认跳过 optional 后可用。

#### 1.3.5 BASELINE WARMING UP

用户可见文案（信息页，对应服务端 `WARMING_UP` 状态）：

> **「ECHO 需要积累几天数据来学习「通常的你」。这段时间里，「今天」页面会显示学习进度；基线形成后，它就会开始比较今天与平常的你。」**

页面元素：进度示意（非真实进度条，只说明「几天」）；「进入应用」按钮 → DONE。

#### 1.3.6 DONE

用户可见文案（完成态）：

> **「准备好了。ECHO 会在后台安静地了解你的日常节奏，每天在「今天」页面告诉你：今天的你，和通常的你有什么不同。」**

页面元素：

| 元素 | 类型 | 用户可见文本 | 行为 |
|---|---|---|---|
| 完成标题 | Text | 「准备好了」 | — |
| 已授权摘要 | Text | 「已开启：被动行为节律。可随时在「支持与设置」中查看或撤回。」 | 只列核心；不再列出「心理数据、量表信息」 |
| 紧急入口 | Button（常驻） | 「紧急支持」 | 打开 SafetyScreen；**任何步骤可访问** |
| 进入应用 | Button | 「进入应用」 | `finishOnboarding()` → `READY_OFFLINE` |

### 1.4 L0 解耦规格

| 项 | 规格 |
|---|---|
| L0 移除 | 普通 Portrait onboarding **不再包含 L0 步骤**（`OnboardingStep.L0` 从主流程删除）。 |
| 保留逻辑 | L0 后端端点 `POST /v1/onboarding/l0` 与 `l0_decision` 保留（机构契约不变），但**不再作为 Portrait 使用的前置阻断**。 |
| 主动进入 | Support 用户（或机构要求）在「支持」页通过显式入口主动进入安全流程时才做必要 L0 确认。 |
| 危机边界 fail-safe | 任何 Onboarding 步骤不因用户勾选/未勾选而**推断心理状态**；不做任何心理推断。只有安全入口（SafetyScreen / 电话 / 机构值班）。 |
| 兼容 | `l0OnboardingBlocked` 纯函数保留（供 Support 页复用与单测），但不再参与普通 Onboarding 的按钮 enabled 判定。 |

### 1.5 Onboarding 文案红线（Psychology Review 应用于 Onboarding）

- 禁止出现「心理健康记录」「筛查提示」「心理记录与量表信息」等 legacy 定位；
- 禁止「我们会监测你的心理状态」「帮你筛查情绪问题」等表述；
- 允许「了解日常节奏」「比较今天与平常」「不做心理诊断」等契约表述。

---

## 2. Permission Degraded 能力模型（P0）

### 2.1 能力与状态定义

**内部字段**（Android 侧枚举，建议放 `model/PortraitCore.kt` 或新 `sensing/SensingCapabilities.kt`）：

```kotlin
enum class SensingCapability { SENSOR, SCREEN, USAGE, NOTIFICATION, MIC }
enum class CapabilityState { AVAILABLE, DENIED, UNAVAILABLE, DISABLED }
```

| 状态 | 语义 | 典型来源 |
|---|---|---|
| `AVAILABLE` | 能力可用且已授权，正在采集 / 可采集 | 权限已授予、传感器存在、开关已开 |
| `DENIED` | 用户 / 系统撤销了该能力的授权 | 系统权限被拒、通知使用权被关、使用情况访问被关 |
| `UNAVAILABLE` | 设备 / 系统版本不支持该能力 | 无陀螺仪、无麦克风硬件、API < 33 无 POST_NOTIFICATIONS 概念 |
| `DISABLED` | 用户关闭了该功能的 consent / 开关，或租户 flag 关闭 | 被动感知总开关关、麦克风开关关、`passive_sensing_enabled=false` |

**内部判定函数**（替换 `passiveSensingGatePasses` 三重门控，保留纯函数便于单测）：

```kotlin
// 核心门控：flag + consent + SENSOR 可用 → 服务可启动（SCREEN 为广播接收，无权限，视为始终可用）
internal fun coreSensingGatePasses(
    flagEnabled: Boolean,
    consentGranted: Boolean,
    sensorAvailable: Boolean
): Boolean = flagEnabled && consentGranted && sensorAvailable

// 逐能力状态（供 UI / diagnostics 使用）
internal fun capabilityState(capability: SensingCapability): CapabilityState
```

> 关键契约：**核心 Portrait 在 SENSOR 可用时就能工作；拒绝 NOTIFICATION / USAGE / MIC 只是 missing source + coverage 下降 + confidence 降低，不得整个 sensing 停止。**

### 2.2 各能力对 Portrait 的影响矩阵

| 能力 | 类别 | 影响的维度 / 特征 | 缺失后果（用户可见） | 是否阻断核心 |
|---|---|---|---|---|
| SENSOR（加速度/陀螺仪） | **CORE** | RHYTHM、MOVEMENT、DAY_STRUCTURE、STABILITY | 画像无法生成 → **abstain**（Today 显示 WARMING_UP/数据不足，不做推断） | **是**（不满足则服务不启动） |
| SCREEN（屏幕状态广播） | **CORE**（无运行时权限） | SCREEN_PATTERN 的一部分（screen_on_minutes / 屏幕时段） | 屏幕维度缺失，覆盖度下降 | 否（始终可用，无撤销路径） |
| USAGE（PACKAGE_USAGE_STATS） | **OPTIONAL source** | app_activity → 应用切换计数、行为分布；间接影响 SCREEN_PATTERN | missing source + coverage 下降 + confidence 降低 | 否 |
| NOTIFICATION（通知使用权） | **OPTIONAL source** | notification count（total/social/other） | missing source + coverage 下降 + confidence 降低 | 否 |
| MIC（RECORD_AUDIO） | **OPTIONAL（永远可选）** | mic_opt 派生特征（音量/语速/停顿/基频） | 无 mic 特征；**不影响其余画像** | 否 |

### 2.3 降级文案（用户可见）

统一原则：**陈述事实 + 给恢复路径，不焦虑不推断**。

| 能力 | 降级文案（DENIED / DISABLED 时） |
|---|---|
| SENSOR | 「运动传感器未开启。开启后 ECHO 才能开始了解你的日常节奏。」 |
| SCREEN | （无权限概念，不展示降级） |
| USAGE | 「未开启「应用使用情况」。ECHO 仍可工作，但行为分布会更粗略。」 |
| NOTIFICATION | 「未开启「通知使用权」。ECHO 仍可工作，但通知使用情况不会被记录。」 |
| MIC | 「麦克风未开启（可选）。这不影响每日画像的生成。」 |

**Today 页「数据不完整」横幅**（PARTIAL_DATA / LOW_CONFIDENCE 时，替换/补充现有文案）：

> 「今天的数据还不完整，以下画像仅反映已经采集到的部分。缺失来源不会影响已有部分的有效性。」

**Today 页 SENSING_DISABLED 态**（仅当核心门控不满足时显示，替换现有「被动感知已关闭」）：

> 「被动感知已关闭，ECHO 暂时无法生成画像。重新开启后，它会继续学习你的日常节奏。」

### 2.4 渐进授权建议顺序（MINIMUM SENSING 与支持页共用）

```text
SENSOR / SCREEN（核心，先） → USAGE / NOTIFICATION（optional source，次） → MIC（最后，永远 optional）
```

理由：
- SENSOR/SCREEN 无额外系统设置跳转成本（SCREEN 甚至无权限），先授权让核心立刻可用；
- USAGE/NOTIFICATION 需跳系统设置（使用情况访问 / 通知使用权），属「增强细节」，放第二梯队；
- MIC 涉及音频敏感权限 + 需二次确认对话框（既有 `showMicConfirm` 逻辑），永远最后且可跳过。

### 2.5 权限恢复入口（Permission Recovery）UI 规格

| 位置 | 触发 | 内容 |
|---|---|---|
| 「支持与设置」页「数据与感知」卡 | 常驻 | 每个能力一行：能力名 + 状态（已开启/未开启/设备不支持）+ 说明文案（§2.3）+ 恢复按钮（跳系统设置 `appSettingsIntent` / 通知使用权设置 / 使用情况访问设置 / 麦克风开关） |
| Today 页 PARTIAL_DATA / LOW_CONFIDENCE | 数据不完整时 | 轻量提示「部分数据来源未开启 → 查看」→ 跳支持页 permission recovery 区块 |
| Onboarding MINIMUM SENSING | 渐进授权时 | 见 §1.3.4 |
| Trend 页 PERMISSION_DISABLED / NO_DATA(PERMISSION) | 现状已有 | 保留「前往系统设置修复权限」CTA，文案改为能力级（见 §2.3） |

**恢复按钮文案（用户可见）**：

| 目标 | 按钮文案 |
|---|---|
| 应用详情页 | 「前往系统设置」 |
| 通知使用权 | 「开启通知使用权」 |
| 使用情况访问 | 「开启使用情况访问」 |
| 麦克风 | 「开启麦克风（可选）」 |

---

## 3. Narrative 语言规范（P0）

### 3.1 Psychology Review 词表

| 词 / 短语 | 分类 | 备注（判断规则） |
|---|---|---|
| 移动较少 | **ALLOW** | 行为观察性，指 MOVEMENT=LESS |
| 移动较多 | **ALLOW** | 行为观察性，指 MOVEMENT=MORE |
| 开始较晚 | **ALLOW** | 行为观察性，指 RHYTHM=LATER |
| 开始较早 | **ALLOW** | 行为观察性，指 RHYTHM=EARLIER |
| 屏幕偏晚 | **ALLOW** | 行为观察性，指 SCREEN_TIMING=LATER |
| 与近期接近 | **ALLOW** | 相对性取值，指 SIMILAR 类 |
| 和近期有些不同 | **ALLOW** | 相对性取值，指 SLIGHTLY_DIFFERENT |
| **安静** | **REWRITE**（条件 ALLOW） | **判断规则**：仅当 MOVEMENT=LESS 且由 `movement_index` 行为事实驱动时 ALLOW（含义 = 「移动较少」）；若语境暗示「情绪低落 / 状态不好」则 **BLOCK**。禁止与任何情绪 / 心理词汇连用。**建议**：第一版直接替换为「移动较少」以消除歧义 |
| **活跃** | **REWRITE**（条件 ALLOW） | 仅当 MOVEMENT=MORE 且由行为事实驱动时 ALLOW（含义 = 「移动较多」）；禁止暗示「精力好 / 状态好」。**建议**：第一版替换为「移动较多」 |
| **稳定** | **REWRITE**（条件 ALLOW） | 仅当 STABILITY=VERY_SIMILAR 且明确指「作息 / 节律接近」时 ALLOW；禁止暗示「情绪稳定 / 心理稳定」。**建议**：第一版替换为「接近」 |
| 焦虑 | **BLOCK** | 心理状态推断，任何语境禁止 |
| 抑郁 | **BLOCK** | 同上 |
| 孤独 | **BLOCK** | 同上 |
| 压力过大 | **BLOCK** | 同上 |
| 情绪低落 | **BLOCK** | 同上 |
| 社交退缩 | **BLOCK** | 同上 |
| 心理异常 | **BLOCK** | 同上 |
| 心理风险 | **BLOCK** | 同上 |
| 精神疾病 | **BLOCK** | 同上 |
| 自杀 / 自伤判断 | **BLOCK** | 画像链绝不输出；仅 Safety 入口存在 |

### 3.2 维度 → 取值 → 文案映射建议（完整）

**内部字段**：维度键 `RHYTHM / MOVEMENT / SCREEN_AMOUNT / SCREEN_TIMING / DAY_STRUCTURE / STABILITY`；取值见下表。

**用户可见文案（summary 句子 + headline 标签）**：

| 维度 | 取值 | Headline（第一版用户语言优先） | Summary 句子 |
|---|---|---|---|
| RHYTHM | EARLIER | 开始较早 | 今天开始活跃的时间比你最近的习惯早 |
| RHYTHM | LATER | 开始较晚 | 今天开始活跃的时间比你最近的习惯稍晚 |
| RHYTHM | SIMILAR | 与近期接近 | 今天的作息时间和你最近的习惯比较接近 |
| RHYTHM | IRREGULAR | 节奏不规律 | 今天开始活跃的节奏不太规律 |
| MOVEMENT | LESS | **移动较少**（替换「安静」） | 白天整体移动少了一些 |
| MOVEMENT | MORE | **移动较多**（替换「活跃」） | 白天整体移动比平常多一些 |
| MOVEMENT | SIMILAR | 与近期接近 | 白天的移动情况和你的平常比较接近 |
| SCREEN_AMOUNT | LESS | 屏幕偏少 | 屏幕互动比平常少一些 |
| SCREEN_AMOUNT | MORE | 屏幕偏多 | 屏幕互动比平常多一些 |
| SCREEN_AMOUNT | SIMILAR | 与近期接近 | 屏幕互动方式和你的平常比较接近 |
| SCREEN_TIMING | LATER | 屏幕偏晚 | 晚间屏幕互动比通常集中 |
| DAY_STRUCTURE | MORE_CONCENTRATED | 更集中 | 今天的行为比较集中 |
| DAY_STRUCTURE | SIMILAR | 与近期接近 | 今天的行为分布和平时差不多 |
| DAY_STRUCTURE | MORE_FRAGMENTED | 更零散 | 今天的行为比较零散 |
| STABILITY | VERY_SIMILAR | **接近**（替换「稳定」） | 整体来看，今天和通常的你非常接近 |
| STABILITY | SLIGHTLY_DIFFERENT | 有些不同 | 整体来看，今天和通常的你有一些小变化 |
| STABILITY | CLEARLY_DIFFERENT | 明显不同 | 整体来看，今天和通常的你相比有明显变化 |

**对后端 `narrative.py` / 前端 `PortraitCore.kt` 的落地要求**：
- `HEADLINE_MAP`：`("MOVEMENT","LESS")` → 「移动较少」、`("MOVEMENT","MORE")` → 「移动较多」、`("STABILITY","VERY_SIMILAR")` → 「接近」；
- `dimensionValueText`：`LESS` → 「较少」（或保留「↓ 减少」，但 headline 不再用「安静」）；
- 新增词汇校验（Psychology Review 门禁）：模板输出后断言不含 BLOCK 词表任何词；`safety_eval` 语料同步更新。

---

## 4. Explainability 规格（P0）

### 4.1 原则

- 面向普通用户：「为什么这么说？」展示**事实**而非**指标**；
- 禁止展示原始指标（`movement_index = 0.47`、`+900%` 等）；
- 每个 fact 必须可追溯：`label / today_text / baseline_text / delta_text` 结构（内部字段已存在，见 `Models.kt` `PortraitFactDto`）；
- 用户可见文案统一使用相对 / 时间表达。

### 4.2 示例文案（最终用户可见文本）

| 场景 | 用户可见文案 |
|---|---|
| 起床/开始活跃 | 「开始活跃 今天 09:42，你近期通常约 08:55」 |
| 日间移动 | 「日间移动 比近期典型水平少一些」 |
| 晚间屏幕 | 「晚间屏幕 比近期典型水平多，今晚累计约 95 分钟」 |

### 4.3 数值表达规格（内部规则）

**规则 A：absolute floor（基线接近 0 时不产生无意义百分比）**

内部字段：`ABS_FLOOR` 常量（按指标定义，建议 `movement_index: 0.1`、`late_screen_minutes: 5.0` 分钟等，具体数值由 Phase 5 指标语义确认后冻结）。

```
delta_pct = round(abs(value - med) / max(med, ABS_FLOOR) * 100)
若 med <= ABS_FLOOR 且 value 也在 floor 量级 → 不输出百分比，直接用 coarse wording。
```

**规则 B：bounded percentage（百分比封顶）**

```
delta_pct = min(delta_pct, 90)  // 展示值不超 90%
```

**规则 C：coarse wording（粗粒度措辞，按 z-score 分档，确定性）**

| z 范围 | 用户可见措辞 |
|---|---|
| \|z\| ≤ 0.7 | 和近期水平接近 |
| 0.7 < \|z\| ≤ 1.5 | 比近期略少 / 略多 |
| 1.5 < \|z\| ≤ 2.5 | 比近期少一些 / 多一些 |
| \|z\| > 2.5 | 比近期明显少 / 明显多 |

> 时间类事实（active_start）永远用绝对时间（HH:mm），不用百分比。

### 4.4 facts 三要素规格

**内部字段**（`PortraitFactDto`，已存在）：

| 字段 | 语义 | 展示位置 |
|---|---|---|
| `label` | 事实标签（如「开始活跃」「日间移动」「晚间屏幕」） | 「为什么这么说？」区标题 |
| `today_text` | 今天的观察（时间 / 相对量） | 今天 |
| `baseline_text` | 平常（基线）的观察（时间 / 「暂无基线」） | 平常 |
| `delta_text` | 今天与平常的差异描述（coarse wording，禁止百分比无界） | 变化 |

**示例（内部 JSON 与用户可见渲染）**：

```json
{
  "label": "开始活跃",
  "today_text": "09:42",
  "baseline_text": "约 08:55",
  "delta_text": "比近期晚"
}
```
渲染：「开始活跃 — 今天 09:42，你近期通常约 08:55，比近期晚」

```json
{
  "label": "日间移动",
  "today_text": "比近期典型水平少一些",
  "baseline_text": "近期典型水平",
  "delta_text": "少一些"
}
```
渲染：「日间移动 — 比近期典型水平少一些」

### 4.5 可追溯性要求

- 每个 fact 必须对应真实 aggregate 字段（`active_start_minute / movement_index / late_screen_minutes / screen_on_minutes / notification_count / app_switch_count`）；
- 禁止输出没有数据支撑的「印象式」fact；
- `movement_index` 原始数值只存在于内部计算，任何用户可见界面不得渲染。

---

## 5. Today / Trend 规格（P0）

### 5.1 Today：Portrait First（保持）+ 无假交互

现状问题：`TodayScreen.kt` L253 `AssistChip(onClick = {}, label = ...)` 为**假交互**。

**规格**：

| 项 | 规格 |
|---|---|
| Headline 标签组件 | 用**非交互 semantic component**（`Surface` + `Text`，或 `Text` + `Modifier.semantics {}`），**禁用** `AssistChip(onClick={})` / `Button` / 任何可点击组件 |
| TalkBack contentDescription | `contentDescription = headline 文本`（即「移动较少」「开始较晚」等，读屏直接朗读标签语义） |
| Font scaling | 文本使用默认 `sp`（不写死 `dp` 字号），跟随系统字体缩放；行内不放固定尺寸图标 |
| Focus order | 非交互标签**不进入焦点顺序**（无 onClick / 无 focusable），TalkBack 触摸浏览时按视觉顺序（日期 → headline → summary → 对照表 → 为什么这么说 → 反馈） |
| Loading announcement | 状态切换时用 `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` 播报「画像加载中」/「画像已更新」，不打断焦点 |

**用户可见文案**（headline 标签）：见 §3.2 headline 列（「移动较少」「开始较晚」等），禁止「安静/活跃/稳定」。

### 5.2 新增数据模型：`PortraitAvailability` / `SensingDiagnostics`

**内部字段定义（Android 端，建议放 `model/PortraitCore.kt` 或新 `model/PortraitAvailability.kt`）**：

```kotlin
data class PortraitAvailability(
    val baselineStatus: String,          // WARMING_UP / EARLY_BASELINE / BASELINE_READY / ...
    val baselineDays: Int,               // 基线有效天数
    val coverage: Float,                 // 今日覆盖度 0..1
    val missingSources: List<String>,    // 缺失 source code（accel/gyro/screen/notification/app_activity/mic_opt）
    val lastCollectedAt: Long,           // 最近成功采集 epoch ms
    val lastSyncedAt: Long,              // 最近成功同步 epoch ms
    val materializationStatus: String,   // materialized / dirty / none（服务端 materialization_state）
    val capabilities: List<CapabilityState> // SENSOR/SCREEN/USAGE/NOTIFICATION/MIC 状态（§2.1）
)

data class SensingDiagnostics(
    val capabilities: Map<SensingCapability, CapabilityState>,
    val sensingActive: Boolean,
    val lastCollectionAt: Long,
    val consecutivePersistenceFailures: Int,
    val pendingUploadCount: Int
)
```

**UI 用途**：

| 字段 | UI 用途 |
|---|---|
| `baselineStatus` | Today WARMING_UP 文案 / 支持页基线状态展示 |
| `baselineDays` | 「已积累 N 天」进度说明 |
| `coverage` | Today PARTIAL_DATA 判定 / Trend 覆盖度 |
| `missingSources` | Today / Trend 降级提示（§2.3） |
| `lastCollectedAt` / `lastSyncedAt` | Trend NO_DATA 态「最近成功采集/同步」展示（替换现有 `repository.lastCollectionTimestamp()` 部分来源） |
| `materializationStatus` | 支持页「画像已生成/待生成」诊断（debug 级） |
| `capabilities` | 权限恢复入口（§2.5）逐能力状态 |

**数据来源**：`GET /v1/me/portraits/today` + `GET /v1/me/baseline/status` + 本地 `SensingDiagnostics`（`PassiveSensingPrefs` / `AppPreferences` / 能力判定函数）。

### 5.3 Trend：脱离 legacy Profile

现状问题：`OtherScreens.kt` L234、L247 依赖 `fetchProfile()`（legacy `UserProfile`）。

**规格**：
- 移除 Trend 对 `ProfileDisplay / fetchProfile / profiles.py` 的依赖；
- `observationDays` 由 `PortraitAvailability.baselineDays`（或 `GET /v1/me/baseline/status` 的 `baseline_days`）替代；
- `sources_present_union` 由 `PortraitAvailability.missingSources`（补集）替代；
- NO_DATA 原因推导（`resolveTrendNoDataReason`）输入改为 `PortraitAvailability` + `SensingDiagnostics`，语义不变；
- 保留 `TREND_DISCLAIMER`（契约点 2 文案锚点）。

---

## 6. Portrait Feedback（P1）

### 6.1 交互与文案

用户可见文案（复用现有）：
- 问题：「这个描述像今天的你吗？」
- 选项：「挺像」(`LIKE`) / 「不太像」(`NOT_LIKE`)
- 已提交：「已记录，感谢反馈。」

**规格**：
- 仅 READY / PARTIAL_DATA 显示（现有逻辑保留）；
- **不默认上传自由文本**（无文本框；如未来需要，单独 consent + P2）。

### 6.2 同步字段（内部）

| 字段 | 类型 | 说明 |
|---|---|---|
| `portrait_id` | string | 画像标识 = `local_date`（yyyy-MM-dd，服务器时区） |
| `feedback` | enum | `LIKE` / `NOT_LIKE` |
| `portrait_schema_version` | string | 画像 schema 版本（对齐 `portrait_schema_version`，用于口径隔离） |
| `created_at` | datetime | 本地产生时间（ISO-8601） |

### 6.3 Outbox 可靠同步

**规格**：
- 新增 outbox 事件类型 `portrait_feedback`；
- `SyncWorker.resolvePath`：`portrait_feedback` → `POST /v1/me/portraits/feedback`（新端点；body 为上述 4 字段 + `event_id` 幂等键）；
- 幂等：`event_id`（本地 UUID）作为幂等键，服务端按 `(user_id, portrait_id, feedback, portrait_schema_version)` 或 `event_id` 去重；
- 与现有 `consent` / `escalation` 同走 Outbox 可靠队列（断网留存、重试、dead-letter 防护）；
- 本地记录（`AppPreferences.recordPortraitFeedback`）保留为即时反馈，Outbox 事件在点击时入队。

### 6.4 指标（内部 telemetry）

| 指标 | 定义 | 用途 |
|---|---|---|
| `portrait_like_rate` | LIKE 数 / 有反馈画像数 | 画像「像不像」健康度 |
| `portrait_mismatch_rate` | NOT_LIKE 数 / 有反馈画像数 | 画像偏差预警 |
| `low_confidence_rate` | LOW_CONFIDENCE 画像数 / 画像总数 | 数据质量 |
| `baseline_ready_rate` | BASELINE_READY 用户数 / 激活用户数 | Onboarding → 基线转化 |
| `portrait_generation_rate` | 有画像天数 / 激活后应生成天数 | 物化链路健康度 |

（指标在 telemetry 层聚合，不暴露给普通用户。）

---

## 7. 时间线 identity（P1）

### 7.1 规格

**内部字段**：
- 服务端 `PortraitOut` 已返回 `date`（= `local_date`）与 `timezone_used`（见 `backend/app/api/portraits.py` `_portrait_out`）；
- **后端负责**：返回并持久化 `local_date + timezone_used`（已实现）；
- **Android 负责**：
  - Room 画像缓存以**服务器 `local_date` 为 identity（主键 / 缓存键）**；
  - **禁止**用 `LocalDate.now()` / `ZoneId.systemDefault()` 覆盖服务器日期（现状 `todayLocalDateString(now, ZoneId.systemDefault())` 仅用于「今天」的**请求触发**，不作为缓存 identity 覆盖）；
  - 请求「今天」画像时可用端侧日期发起（获取「服务器眼中的今天」），但**存储与展示 identity 一律用响应的 `local_date`**。

### 7.2 测试场景：旅行与时区切换

| 场景 | 期望行为 |
|---|---|
| 用户从 Asia/Shanghai 飞到 Europe/London（系统时区改变） | 端侧「今天」按新时区请求；服务器按用户 `timezone` 返回 `local_date` 与 `timezone_used`；Room 以服务器 `local_date` 存取，不因端侧时区变化产生重复/丢失日期 |
| 用户在一天内跨时区（如跨国际日期变更线） | 服务器 `local_date` 唯一；端侧不产生两个「同一天」画像缓存 |
| 时区来回切换 | 旧缓存（服务器 local_date）不被误当「今天」；「今天」以服务器视角重新拉取 |
| 离线缓存展示 | 按服务器 `local_date` 排序展示；`timezone_used` 记录画像计算时区，UI 可显示「按 xx 时区计算」 |

---

## 8. 验收标准

### 8.1 P0（必须）

| # | 验收项 |
|---|---|
| 1 | Onboarding 主流程为六步新流程；L0 不在普通流程中；紧急入口任何步骤可访问 |
| 2 | WELCOME 包含契约核心句；CORE DATA CONSENT 不含「心理记录与量表信息」 |
| 3 | `coreSensingGatePasses` 仅要求 flag + consent + SENSOR；拒绝 USAGE/NOTIFICATION/MIC 不停止 sensing |
| 4 | Narrative 输出不含 BLOCK 词；headline 无「安静/活跃/稳定」（第一版替换为「移动较少/移动较多/接近」） |
| 5 | facts 不渲染 `movement_index` 原始值；无界百分比被 absolute floor + bounded + coarse wording 取代 |
| 6 | Today headline 为非交互 semantic 组件（无 `AssistChip(onClick={})`） |
| 7 | Trend 不再调用 `fetchProfile()` |
| 8 | 单测锚点更新：`todayPortraitStateText`、`l0OnboardingBlocked`、`resolveTrendState`、`resolveTrendNoDataReason`、`dimensionValueText`、`safety_eval` 词表 |

### 8.2 P1（应该）

| # | 验收项 |
|---|---|
| 1 | `portrait_feedback` 事件入 Outbox 并同步到 `POST /v1/me/portraits/feedback`；服务端按 `event_id` 幂等 |
| 2 | 5 个 telemetry 指标可聚合 |
| 3 | Room 画像缓存 identity 为服务器 `local_date`；时区切换测试通过（§7.2 场景） |

---

## 9. Open Questions（实现前需确认）

| # | 问题 | 影响 |
|---|---|---|
| 1 | `ABS_FLOOR` 具体数值（movement_index / late_screen_minutes / screen_on_minutes）由 Phase 5 指标语义确认后冻结，本文档给出建议值（0.1 / 5.0 / 5.0） | facts 输出 |
| 2 | `POST /v1/me/portraits/feedback` 端点由 Phase 1 后端契约扩展（engineer）还是 Phase 6 单独新增 | feedback 同步 |
| 3 | `PortraitAvailability` 是否新增服务端聚合端点（如 `GET /v1/me/portraits/availability`）还是由现有 today + baseline/status 组合 | Android 数据来源 |
| 4 | MIC 在 MINIMUM SENSING 中默认「跳过」还是「不展示」（仅支持页可开）——建议默认跳过但展示入口 | Onboarding 页面长度 |

---

## 10. 与既有文档的关系

| 文档 | 关系 |
|---|---|
| `PORTRAIT_CONTRACT.md` | 本规格的唯一契约约束，任何冲突以契约为准 |
| `docs/15_Onboarding产品化规格_v0.6.md` | 历史 v0.6 规格；七态状态机 / 激活码交换保留，L0 / 心理记录话术废弃 |
| `docs/19b_权限矩阵_v0.6.2.md` | 权限事实基线；三重门控 → 能力模型是权限矩阵的升级 |
| `docs/current/00_Phase0_Fresh_Truth_Audit.md` | Phase 6.x 问题清单即本规格 §1.1 / §2 / §3 / §4 / §5 / §6 修复对象 |
