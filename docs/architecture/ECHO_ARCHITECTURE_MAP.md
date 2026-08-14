# ECHO Architecture Map —— 当前架构地图与 Master Prompt 冲突清单

> 状态：v1.0 · ERA 1 审计产出 · 生成基线：commit `11b3e77`（v0.7.4），核对日期 2026-08-14
> 位置：`docs/architecture/ECHO_ARCHITECTURE_MAP.md`

## 1. 当前架构地图（事实）

### 1.1 产品契约层

- `PORTRAIT_CONTRACT.md`（冻结 v1.0）：Observation-only 画像契约 = Master Prompt 定义的 **PORTRAIT_CONTRACT_V1_OBSERVATION**（保留为 Ground Truth Contract）。
- `docs/current/20_Phase6_Onboarding_UX_Spec.md`：PM 冻结的六步 onboarding 规格（**与本 Master Prompt 冲突，将被 ERA 1 取代**）。
- 产品模式：v0.7 本地优先 + 单档订阅（激活码）；免费版 = 全本地功能。

### 1.2 Android（`android/app/src/main/java/com/yunjue/echo/mind/`）

| 域 | 事实 |
|---|---|
| 感知 | `PassiveSensingService`（FGS SPECIAL_USE，START_STICKY）+ `SensingWindowScheduler`（5 分钟窗口，ACK）+ 5 Collector（Sensor/Screen/AppActivity/Notification/Mic）；门控 `coreSensingGatePasses(flag+consent+SENSOR)`，fail-closed |
| 特征 | `FeatureExtractor`（22 维 summary+vector）、`MicFeatureExtractor`（256 维，可选） |
| 端侧画像 | `localportrait/`：LocalAggregateCalculator → LocalBaselineCalculator（0-2 WARMING_UP / 3-6 EARLY_BASELINE / ≥7 READY）→ LocalPortraitEngine（确定性叙事，与服务端镜像）→ LocalPortraitDigest（周小结） |
| 状态 | `AppPreferences`（SharedPreferences：sensingActive 布尔、onboarding 七态、flag 缓存、订阅字段、时间戳）+ `PassiveSensingPrefs`（DataStore：开关/mic/sampling） |
| UI | `EchoMindApp`（4 Tab：今天/能力/趋势/支持 + 紧急 FAB）→ `TodayScreen`（九态 Portrait + sync chip + 本地横幅 + coverage + 反馈行）、`TrendScreen`（7/28 天时间线 + NO_DATA 八原因）、`SkillCardHost`/`SkillListScreen`、`SupportScreen`（订阅/数据与感知/数据权利） |
| 数据 | Room v8（SQLCipher 加密；feature_vectors/consents/outbox/portrait_daily/active_skill_sessions/escalation_requests），Outbox 可靠同步，`SyncWorker` |
| 安全 | `AndroidKeystoreFieldCipher`（Keystore fail-closed）、consent 证据哈希、DSR 本地/云端双路径 |

### 1.3 Backend（`backend/app/`）

- 链路：`ingest_derived_feature → upsert_daily_aggregate（coverage=unique 窗口/DST 动态）→ materialize_dirty（15min debounce）→ generate_portrait（baseline + 6 维 + 确定性叙事 + facts + explain）`。
- API：`/v1/me/portraits/today|?days|rebuild|feedback`、`/v1/me/baseline/status`、`/v1/me/messages`、`/v1/me/subscription`、features/skills/escalations/consent/data_rights 等（57+ 路径）。
- 门禁：feature_flags 3 键 fail-closed；订阅单档 standard（skills/escalations 到期 402）；ingest 永不创建 RiskSignal（fault_injection 断言）。
- 质量：pytest 1071 绿、行覆盖率 93.8%；Android 335 单测 + 3 instrumentation。

## 2. 与 Master Prompt 的冲突清单（ERA 1 要解决/记录）

| # | 冲突点 | 现状 | Master Prompt 要求 | 处置 |
|---|---|---|---|---|
| C1 | Onboarding 六步 + DONE 页 | `OnboardingScreen.kt`（WELCOME→…→DONE→「进入应用」） | Welcome→Core promise→Privacy→Core permissions→**ECHO Awakening→Scene**（无 Done/Continue/Enter App） | **ERA 1 重构** |
| C2 | 授权状态乐观置位 | usage/notification 先置 true 再 ON_RESUME 回填；SENSOR 纯本地置 true | 系统真实权限状态为唯一事实来源 | **ERA 1 修复** |
| C3 | 无统一运行时状态 | `sensingActive` 布尔（系统杀进程后僵尸 true） | 唯一 `SensingRuntimeStatus` 六态；「关闭」只 = 用户行为 | **ERA 1 新建** |
| C4 | 首页工程噪音 | Today 顶部 sync chip、本地生成横幅、coverage debug | 主视觉只有 ECHO Scene；工程状态进 Me/诊断 | **ERA 1 清理** |
| C5 | Day0 无画报 | Day0 = WARMING_UP 文案 + 进度条 | Day0 必有 SEED ECHO（installationSeed+timeOfDay+coverage+runtime+maturity） | **ERA 1 新建** |
| C6 | 无 EchoPresenceState | 各页各算状态 | 全系统单一 Current ECHO State（App/Wallpaper/Dream 同源） | **ERA 2 新建** |
| C7 | 无视觉引擎 | 纯文本 UI | Generative Visual Engine（EchoPresenceState→EchoVisualParameters→EchoSceneRenderer） | **ERA 2 新建** |
| C8 | Today 是数据页 | 九态文本 + 维度表 | ECHO Scene（Why/Now/Conversation/Action/Memory 从 Scene 展开） | **ERA 2 重构** |
| C9 | 无 Wallpaper/Dream | Manifest 无相关服务 | Presence 五表面（App/Wallpaper/Lock-safe/Dream/通知） | **ERA 3 新建** |
| C10 | 无 AI Provider 抽象 | 无任何 LLM 接入 | BYOM 一等公民；EchoReasoningProvider 抽象；secret 设备端 | **ERA 4 新建** |
| C11 | 无 Context Compiler / 隐私预算 | AI 不存在 | LLM 请求必须经 Context Compiler + Privacy Budget | **ERA 5 新建** |
| C12 | 无 Memory 系统 | 仅画像反馈本地记录 | EchoMemory 7 类 + 生命周期 + What ECHO Knows | **ERA 6 新建** |
| C13 | 能力页=订阅商城感 | skills 402 门禁 + 订阅空态 | Actions 免费基础集；Intervention L0-L3 分级 | **ERA 9 重构** |
| C14 | 五 Tab 信息架构 | 今天/能力/趋势/支持 | 三世界：ECHO / Journey / Me | ERA 2/8 渐进收敛 |
| C15 | 趋势是图表矩阵 | 维度符号矩阵 + 综述 | Journey：Day→Year 时间河流 + Visual Memory River | **ERA 8 重构** |
| C16 | 反馈无原因字段 | LIKE/NOT_LIKE 两值 | 纠错 + 快速原因 → Correction Memory | ERA 6（字段 ERA 1 预留） |
| C17 | 订阅单档 | standard 单档 | 订阅卖持续价值；免费版=完整产品 | 产品评审后重构（非 ERA 1 阻塞） |

## 3. 与 Master Prompt 一致、保留不动的资产

- Observation Core 全链（感知→特征→聚合→基线→画像）——Ground Truth Layer；
- Permission Degraded（拒绝 optional 不停止核心感知）；
- 端侧画像引擎 + 本地优先架构（= 「没有云和 AI 时基础 ECHO 仍然成立」的现状基础）；
- 隐私 fail-closed 系列（SQLCipher、flag 默认关、consent 证据、DSR）；
- 反馈闭环 Outbox 通道（Correction Memory 的雏形）；
- 冷启动三态与 Day7 仪式机制（maturity 成长模型的基础）。

## 4. 目标目录形态（PART 110，渐进收敛，不强行重写）

```text
conceptually:
Observation / Self Model / Memory / Intelligence /
Presence / Scene / Journey / Actions / Privacy
```

物理上保持现状包结构，新增 `presence/`（ERA 2）、`intelligence/`（ERA 4-6）、`memory/`（ERA 6）；dependency direction 必须符合上述概念分层。
