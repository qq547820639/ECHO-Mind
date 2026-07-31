# PRD：ECHO Mind Android Path A MVP

## 目标

为 18 岁以上机构用户提供低负担的被动感知日常状态理解、自进化能力卡片下发、个人叙事趋势和确定性人工支持入口。

> v0.2 范式迁移：从主动输入（签到/日记/量表）转为被动感知 + 自进化沙箱范式。主动录入入口已停用（返回 410 Gone），改由端侧被动采集派生特征驱动叙事与能力下发。

## 成功指标

- 首次流程中位时长 ≤5 分钟
- 被动感知范式下用户零主动输入负担（无签到/日记/量表录入）
- 明确高危信号（被动 RED）一轮内冻结普通生成并创建 L3 事件
- 拒绝可选权限（如麦克风）不影响核心功能
- 人工工作台能展示证据、等待时间和责任人，而非单一 AI 分数
- 沙箱每日自进化回路能从感知缺口归纳新 Skill 并下发

## 核心故事

1. 用户能理解 AI 身份和非诊断边界后进入应用（L0 准入门禁保留）。
2. 用户授权被动感知后，端侧自动采集屏幕/通知/活动/传感器信号，提取派生特征（summary + vector），不上传原始传感数据。
3. 后端基于派生特征生成每日叙事（DailyNarrative）与用户画像（UserProfile），驱动趋势视图。
4. 自进化沙箱每日夜间运行：审计当日数据 → 识别感知缺口 → 生成候选 Tool → 验证 → 归纳为 Skill → 脱敏下发。
5. 用户在「能力」Tab 查看已下发 Skill 卡片（WebView 安全沙箱渲染），点击触发能力。
6. 用户出现明确危机信号时，应用停止普通生成并启动人工链路；危机入口（12356/110/120）常驻可见。
7. 值班人员可确认、接管、记录处置；只有人工可关闭事件。

## 被动感知范式

- **端侧采集**：传感器（加速度/陀螺仪）、屏幕开关、通知、App 活跃度；麦克风为可选模块（默认关，显式授权）。
- **特征提取**：5 分钟窗口聚合 → 中文自然语言 summary（≤4000 字）+ vector（≤256 维 float），对齐 `DerivedFeatureIn` 契约。
- **隐私强约束**：端侧提取后原始传感数据即丢弃，不上云；后端 `DerivedFeatureIn` schema 拒绝任何额外字段（`extra="forbid"`），防止误传原始 payload。
- **分项同意**：`passive_sensing` 同意独立于其他同意类型；撤回后 ingest 返回 412 Precondition Failed。
- **麦克风授权证据闭环**：麦克风开关切换时写入 `voice_features` 类型 consent（含 SHA-256 证据哈希），端侧 `OnPermissionsChangeListener` 监听系统权限撤回并写 revoked consent；后端对 `source=mic_opt` 特征校验 `voice_features` consent 有效，撤销返回 412。
- **安全门禁**：被动 RED 信号（命中 `PASSIVE_RED_TERMS`）触发冻结 + escalation（trigger=passive_red_signal）。

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
