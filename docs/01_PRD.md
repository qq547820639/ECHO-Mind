# PRD：ECHO Mind Android Path A MVP

## 目标

为 18 岁以上用户提供低负担的被动感知日常状态理解、自进化能力卡片下发、个人叙事趋势和确定性人工支持入口。

> v0.2 范式迁移：从主动输入（签到/日记/量表）转为被动感知 + 自进化沙箱范式。主动录入入口已停用（返回 410 Gone），改由端侧被动采集派生特征驱动叙事与能力下发。

> **Portrait Core（v0.7）产品收敛**：产品核心合同固定为「每日个人画像」——ECHO-Mind 在用户主动授权后学习**用户自己的日常基线**，每天回答："今天的我，和通常的我有什么不同？"。Skill、人工支持、Safety、Workbench、Sandbox 全部保留但降级为**外围能力**，不占据产品主叙事。详见仓库根目录 `PORTRAIT_CONTRACT.md`。

> **v0.7 本地优先 + 订阅制（产品模式修订）**：默认本地使用（无账号/激活码门槛，画像由端侧引擎生成、数据只保存在本机）；可选订阅开通云端同步、长周期分析消息与专业支持。订阅激活码复用 verify-code 机制；支付集成与契约修订（PORTRAIT_CONTRACT v1.0）属外部发布门。

## 成功指标

- 首次流程中位时长 ≤5 分钟
- 被动感知范式下用户零主动输入负担（无签到/日记/量表录入）
- 被动行为特征**绝不**推断危机/自杀意图（PRD v0.6 契约点 1：ingest 不触发任何被动危机链；危机信号仅来自用户主动求助、L0 准入与专业人员事件）
- 拒绝可选权限（如麦克风）不影响核心功能
- 人工工作台能展示证据、等待时间和责任人，而非单一 AI 分数
- 沙箱每日自进化回路能从感知缺口归纳新 Skill 并下发

## 核心故事

1. 用户能理解 AI 身份和非诊断边界后进入应用（L0 准入门禁保留）。
2. 用户授权被动感知后，端侧自动采集屏幕/通知/活动/传感器信号，提取派生特征（summary + vector），不上传原始传感数据。
3. 后端基于派生特征生成每日行为聚合（`DailyBehaviorAggregate`，按用户 local timezone 切日）、个人基线（`PersonalBaseline`，近 28 天 robust statistics + 冷启动三态 + weekday/weekend 分桶）与每日画像（`DailyPortrait`，5 维度 + 确定性模板叙事 + confidence/abstention），驱动「今天」页与 7/28 天画像时间线。
4. 自进化沙箱每日夜间运行：审计当日数据 → 识别感知缺口 → 生成候选 Tool → 验证 → 归纳为 Skill → 脱敏下发。（外围能力，不再自动主链推荐）
5. 用户在「能力」Tab 主动浏览已下发 Skill 卡片（WebView 安全沙箱渲染），点击触发能力。
6. 用户出现明确危机信号时，应用停止普通生成并启动人工链路；危机入口（12356/110/120）常驻可见。
7. 值班人员可确认、接管、记录处置；只有人工可关闭事件。

## Portrait Core（每日个人画像）

- **产品合同**：`PORTRAIT_CONTRACT.md` 冻结"画像是什么/不是什么"。只做行为观察（Observation），不做 Psychological Interpretation；Me vs Me，禁止 Me vs Population。
- **日界线**：以 `User.timezone` 定义"一天"（本地 00:00 → 次日 00:00 换算 UTC 查询窗口）；`DailyPortrait.timezone` 记录 timezone_used，保证可重现。
- **DailyBehaviorAggregate**（daily_behavior_aggregates）：覆盖度/移动/屏幕/App 切换/通知计数等；禁含 mood/anxiety/stress/depression/loneliness/risk 字段。
- **PersonalBaseline**（personal_baselines）：近 28 个有效日（不含当天），median/MAD/P10/P25/P75/P90；冷启动 WARMING_UP(0-2) / EARLY_BASELINE(3-6) / BASELINE_READY(≥7)；weekday/weekend 分桶，不足 fallback all_days；confidence HIGH/MEDIUM/LOW。
- **DailyPortrait**（daily_portraits）：6 维度 RHYTHM / MOVEMENT / SCREEN_AMOUNT / SCREEN_TIMING / DAY_STRUCTURE / STABILITY（禁止 GOOD/BAD/HEALTHY 等评价性取值）；数据缺失维度省略（missing != irregular）；低置信度 abstain（LOW_CONFIDENCE，不硬生成画像）；确定性模板叙事（无 LLM），每句可追溯（Explainability facts）。
- **API**：`GET /v1/portraits/today`、`GET /v1/portraits?days=7|28`、`GET /v1/baseline/status`（全程无副作用）、`POST /v1/portraits/rebuild`（显式重建写路径）。写路径仅 feature ingest / background rebuild / explicit rebuild。
- **语言安全**：被动画像文案禁止出现：焦虑/抑郁/孤独/压力过大/心理异常/风险/精神疾病/社交退缩/不健康（CI 强制）。
- **Android**：Today「今天」页 Portrait first（九态状态机 + 为什么这么说？ + 画像反馈）；Trend 升级为 Portrait Timeline（7/28 日）；Room 缓存 `DailyPortraitEntity`（offline-first）。
- **外围能力边界**：Skill 不做"你移动少→推荐呼吸训练"式自动推荐；Support/Safety 与画像链不自动连接。

## 被动感知范式

- **端侧采集**：传感器（加速度/陀螺仪）、屏幕开关、通知、App 活跃度；麦克风为可选模块（默认关，显式授权）。
- **特征提取**：5 分钟窗口聚合 → 中文自然语言 summary（≤4000 字）+ vector（≤256 维 float），对齐 `DerivedFeatureIn` 契约。
- **隐私强约束**：端侧提取后原始传感数据即丢弃，不上云；后端 `DerivedFeatureIn` schema 拒绝任何额外字段（`extra="forbid"`），防止误传原始 payload。
- **分项同意**：`passive_sensing` 同意独立于其他同意类型；撤回后 ingest 返回 412 Precondition Failed。
- **麦克风授权证据闭环**：麦克风开关切换时写入 `voice_features` 类型 consent（含 SHA-256 证据哈希），端侧 `OnPermissionsChangeListener` 监听系统权限撤回并写 revoked consent；后端对 `source=mic_opt` 特征校验 `voice_features` consent 有效，撤销返回 412。
- **安全门禁**：被动派生特征（accel/screen/notification/app/mic 摘要）不得用于推断自杀/自伤意图，ingest 永不创建 RiskSignal/Escalation（`escalation_id` 恒为 None，fault_injection 矩阵断言）；危机信号唯一来源为主动文本命中、用户主动求助、L0 准入与专业人员事件。

## 沙箱算力预算

- **单次超时**：`sandbox_timeout_seconds`（默认 120s），超时转 failed（`error_message="timeout after Ns"`）。
- **租户并发上限**：`sandbox_max_concurrent`（默认 4），同租户超限返回 429 `sandbox concurrency limit reached`。
- **每小时速率限制**：`sandbox_rate_limit_per_hour`（默认 10），按 tenant+user 计数，超限返回 429 `sandbox rate limit exceeded`。

## 冷启动兜底文案

- `GET /v1/skills` 空列表时返回 `cold_start_hint`（按 `observation_days` 分阶段）：0 天=`stage_0`/1-3 天=`stage_1_3`/4-7 天=`stage_4_7`/7+ 天=`stage_7_plus`。
- 端侧区分三态：加载中（spinner）、加载失败（重试按钮）、真无 Skill（分阶段文案）。

## 机构去标识群体画像

- `GET /v1/tenant/portrait`（admin/professional/auditor）：聚合本租户 mood_hint 分布、observation_days 统计、近 7 天活跃用户数、escalation 计数、Skill 下发数。
- **去标识保护**：任何聚合桶计数 < 5 时合并到 "other" 桶，防重标识；不返回单个用户 ID/特征。

## 灰度回滚

- `Tenant.feature_flags`（JSON）：`passive_sensing_enabled`/`sandbox_enabled`/`skills_delivery_enabled` 三开关，默认全开。
- 被动感知路由前置 flag 检查，关闭返回 410 Gone。
- `GET /v1/config/flags` 供端侧拉取并联动（停止采集/隐藏卡片）；`POST /v1/skills/batch-retire` 支持批量回滚 Skill。

## 自进化沙箱

- **调度**：按租户时区夜间触发，租户隔离。
- **回路**：audit_day → gap_finder → tool_forge → tool_validator → skill_induct。
- **Skill 治理**：Draft → Reviewed → Signed（admin/professional 审签）；retired 退出。
- **脱敏下发**：Skill 下发前经 sanitizer 移除其他用户数据、原始特征引用，仅保留能力描述。
- **Zero-Skill 冷启动**：无 Skill 时能生成首批候选 Tool，UI 展示冷启动文案。

## 不做

- 面部情绪识别、后台录音（麦克风仅可选模块，原始音频不落盘不上云）、小米手环、iOS、开放式无限陪聊、自动诊断、自动治疗方案、自动药物建议。
- 原始传感数据上云（仅派生特征 summary/vector 上传）。

> **ERA 33 范围演进（2026-08-16 显式记录，非静默变更）**：上一条中"小米手环"仍适用于 **v0.11.0 试点版范围冻结**
> （`pilot-pack/00_试点就绪总控表.md`，试点交付物不含手环）。ERA 33 起作为新 Product Surface
> （ECHO Wrist / Second Body）进入开发：`:feature:wearable` + Vela 快应用 + ANS_FRAME_V1，
> 契约见 `docs/wearable/ECHO_WRIST_CONTRACT.md`，状态见 `docs/STATUS.md`。
> 该表面为 **Developer Preview / Integration Preview**：真机/SDK/生产签名验证前不得宣称
> Band10 Production Verified（四个 BLOCKED_EXTERNAL_* 见能力矩阵 §4）。
> 约束不变：手环不拥有 Memory/SelfModel/Journey/AI Provider/API Key/Identity Genome（ONE ECHO）。
