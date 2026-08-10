# 被动感知范式待明确项收尾 Spec

> change-id: `pending-items-resolution`
> 范围：上轮 `passive-paradigm-full-rollout` 标注的 5 个待明确项，逐项落地为可运行能力。
> 前置：T02–T13 全部完成，后端 744 测试通过，Android 代码审查通过。

## Why

上轮全量落地被动感知范式后，架构师标注的 5 个待明确项仍是"暂不影响地基启动"的开放问题。现在主链路已跑通，这 5 项决定了能否进入试点：麦克风授权无证据链、沙箱无算力预算可被拖垮、冷启动体验单薄、机构看不到群体画像、出问题无法灰度回滚。本 spec 逐项收尾。

## What Changes

### 1. 麦克风授权证据闭环
- 移动端麦克风开关切换时，写入 `voice_features` 类型 Consent 到后端（含证据哈希）。
- 移动端监听权限撤回事件（`OnPermissionsChangeListener`），撤回后停止 MicCollector + 写 revoked consent。
- 后端 `POST /v1/features/ingest` 对 `source=mic_opt` 的特征额外校验 `voice_features` consent 有效（撤销则 412）。

### 2. 沙箱算力预算
- `config.py` 新增沙箱配置：`sandbox_timeout_seconds`(默认 120)、`sandbox_max_concurrent`(默认 4)、`sandbox_rate_limit_per_hour`(默认 10)。
- `runner.execute()` 加超时（`concurrent.futures.ThreadPoolExecutor` + `future.result(timeout)`），超时转 failed。
- `scheduler.py` 加租户级并发信号量（同租户最多 `sandbox_max_concurrent` 个并行 run）。
- `POST /v1/sandbox/runs` 路由加每小时速率限制（内存计数器，按 tenant+user）。

### 3. 冷启动兜底文案分阶段
- 冷启动文案抽到 `strings.xml`，支持多档（按 observation_days：0天/1-3天/4-7天/7+天）。
- 后端 `GET /v1/skills` 空列表时返回 `cold_start_hint` 字段（基于该用户 observation_days 推荐文案 key）。
- 前端区分"加载失败"与"真无 Skill"（失败显示重试按钮，真无 Skill 显示分阶段文案）。

### 4. 机构去标识群体画像
- 新增 `GET /v1/tenant/portrait` 路由（admin/professional/auditor 角色，按 principal.tenant_id 聚合）。
- 聚合维度：mood_hint 分布、observation_days 统计、近 7 天活跃用户数、escalation 计数、Skill 下发数。
- 去标识保护：任何聚合桶计数 < 5 时合并为"其他"桶，防重标识；不返回单个用户 ID/特征。
- 新建 `services/tenant_portrait.py` 聚合服务。

### 5. 灰度回滚方案
- `Tenant` 表新增 `feature_flags` JSON 字段（migration），支持 `passive_sensing_enabled`/`sandbox_enabled`/`skills_delivery_enabled` 三个开关。
- 后端被动感知路由（ingest/sandbox/skills）前置检查 tenant feature_flags，关闭则返回 410。
- 新增 `GET /v1/config/flags` 路由（用户拉取本租户 feature flags，用于端侧灰度）。
- 移动端 `AppPreferences` 拉取并缓存 feature flags，关闭时停止被动采集 + 隐藏 Skill 卡片。
- 新增 `POST /v1/skills/batch-retire` 路由（admin 批量回滚 Skill）。

## Impact

- **Affected specs**: R-01（麦克风授权证据）/ R-02（沙箱预算）/ R-04（冷启动 + 灰度回滚）/ 机构画像（新增能力）
- **Affected code (Backend)**:
  - `app/config.py` — 新增沙箱预算配置
  - `app/models.py` — Tenant 加 feature_flags 字段
  - `app/schemas.py` — 新增 TenantPortrait/SkillBatchRetire/ConfigFlags schema
  - `app/api/routes.py` — 新增 3 路由 + ingest 加 voice_features 校验 + 被动路由加 feature flag 前置
  - `app/services/sandbox/runner.py` — 加超时
  - `app/services/sandbox/scheduler.py` — 加并发信号量
  - `app/services/tenant_portrait.py` — 新建聚合服务
  - `app/services/feature_flags.py` — 新建 flag 查询服务
  - `alembic/versions/` — 新增 Tenant feature_flags 迁移
- **Affected code (Android)**:
  - `sensing/MicCollector.kt` — 加权限撤回监听
  - `ui/OtherScreens.kt` — 麦克风开关写 consent
  - `data/LocalRepository.kt` — saveVoiceFeaturesConsent + fetchFeatureFlags
  - `ui/TodayScreen.kt` + `ui/SkillCardHost.kt` — 分阶段冷启动文案 + 失败重试
  - `res/values/strings.xml` — 冷启动文案资源
  - `AppPreferences.kt` — feature flags 缓存

## ADDED Requirements

### Requirement: 麦克风授权证据闭环
系统 SHALL 在麦克风开关切换时写入 `voice_features` 类型 Consent 到后端，并在权限撤回时停止采集 + 记录撤销。后端对 `source=mic_opt` 的派生特征校验 `voice_features` consent 有效。

#### Scenario: 开启麦克风写 consent
- **WHEN** 用户在设置页开启麦克风开关并授予 RECORD_AUDIO
- **THEN** 移动端写入 `voice_features` consent（granted=true + 证据哈希）到后端，MicCollector 启动

#### Scenario: 权限撤回停止采集
- **WHEN** 用户在系统设置撤回 RECORD_AUDIO 权限
- **THEN** MicCollector 监听到撤回事件后立即停止，写入 `voice_features` consent（granted=false）

#### Scenario: mic_opt 特征校验 consent
- **WHEN** 后端收到 `source=mic_opt` 的派生特征但 `voice_features` consent 已撤销
- **THEN** 返回 412 "active voice-features consent required"

### Requirement: 沙箱算力预算
系统 SHALL 对自进化沙箱施加算力预算：单次 run 超时、租户级并发上限、每用户每小时速率限制。

#### Scenario: 单次 run 超时
- **WHEN** 沙箱 run 执行超过 `sandbox_timeout_seconds`（默认 120s）
- **THEN** run 状态转为 failed，error_message 记录 "timeout"

#### Scenario: 租户并发上限
- **WHEN** 同租户已有 `sandbox_max_concurrent`（默认 4）个 run 正在执行
- **THEN** 新的 run 请求返回 429 "sandbox concurrency limit reached"

#### Scenario: 每小时速率限制
- **WHEN** 同一用户在一小时内已触发 `sandbox_rate_limit_per_hour`（默认 10）次 run
- **THEN** 新请求返回 429 "sandbox rate limit exceeded"

### Requirement: 机构去标识群体画像
系统 SHALL 提供机构级去标识群体画像 API，聚合本租户所有用户的画像/叙事/风险数据，任何聚合桶计数 < 5 时合并为"其他"防重标识。

#### Scenario: 机构管理员查看群体画像
- **WHEN** admin/professional/auditor 调用 `GET /v1/tenant/portrait`
- **THEN** 返回 mood_hint 分布、observation_days 统计、活跃用户数、escalation 计数、Skill 下发数，不含单个用户 ID

#### Scenario: 小桶合并防重标识
- **WHEN** 某 mood_hint 桶的用户数 < 5
- **THEN** 该桶合并到"其他"桶，不单独输出

### Requirement: 灰度回滚
系统 SHALL 支持租户级 feature flag 控制被动感知范式的启停，移动端拉取 flag 后联动端侧行为，支持 Skill 批量回滚。

#### Scenario: 租户关闭被动感知
- **WHEN** admin 设置某租户 `passive_sensing_enabled=false`
- **THEN** 该租户用户调用 `POST /v1/features/ingest` 返回 410，移动端拉取 flag 后停止采集

#### Scenario: Skill 批量回滚
- **WHEN** admin 调用 `POST /v1/skills/batch-retire` 传入 skill_id 列表
- **THEN** 这些 Skill 状态转为 retired，不再下发给用户

#### Scenario: 移动端拉取 feature flag
- **WHEN** 移动端启动时调用 `GET /v1/config/flags`
- **THEN** 返回本租户的 feature flags，移动端缓存并据此控制被动采集/Skill 卡片显示

## MODIFIED Requirements

### Requirement: 冷启动兜底文案
系统 SHALL 在 Skill 列表为空时根据用户 observation_days 展示分阶段冷启动文案，并区分"加载失败"与"真无 Skill"。

#### Scenario: 分阶段文案
- **WHEN** 用户打开主界面且无已下发 Skill
- **THEN** 根据 observation_days 显示：0天="系统正在了解你，能力卡片将逐渐出现"/1-3天="已采集N天数据，能力即将出现"/4-7天="画像成型中，敬请期待"/7+天="暂无新能力，系统持续观察中"

#### Scenario: 加载失败可重试
- **WHEN** fetchSkills 网络失败
- **THEN** 显示"加载失败"+ 重试按钮，而非冷启动文案
