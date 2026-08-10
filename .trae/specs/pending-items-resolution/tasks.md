# Tasks — 待明确项收尾

> 5 个待明确项逐项落地。标注 [P0] 为关键路径；[par] 为可并行。

## P1 — 麦克风授权证据闭环

- [x] P1: 麦克风授权证据闭环 [P0]
  - [x] P1.1: 后端 `schemas.py` 新增 `VoiceFeaturesConsentCreate`（复用 ConsentCreate 但 consent_type 固定 "voice_features"）；`app/api/routes.py` 的 `POST /v1/features/ingest` 对 `source=mic_opt` 额外校验 `voice_features` consent 有效（新增 `require_voice_features_consent` 依赖），撤销则 412
  - [x] P1.2: Android `sensing/MicCollector.kt` 加 `OnPermissionsChangeListener`（或 `ContextCompat.getSystemService(ContextCompat.PERMISSION_SERVICE)` + `PackageManager` 轮询），权限撤回时调 `stop()` + 回调通知 LocalRepository
  - [x] P1.3: Android `data/LocalRepository.kt` 新增 `saveVoiceFeaturesConsent(granted: Boolean)`：构造 evidence_hash（SHA-256 of "voice-features-consent-2026.07:$userId:$granted"）+ saveConsent(consentType="voice_features", version="voice-features-consent-2026.07", priority=600) + 入 outbox
  - [x] P1.4: Android `ui/OtherScreens.kt` 麦克风开关 granted=true 后调 `saveVoiceFeaturesConsent(true)`；权限拒绝/撤回时调 `saveVoiceFeaturesConsent(false)`
  - [x] P1.5: 后端 `tests/test_voice_features_consent.py`：mic_opt 特征无 voice_features consent → 412；有 consent → 201；consent 撤销后 → 412
  - [x] P1.6: Android `MicCollectorTest.kt` 补权限撤回监听测试

## P2 — 沙箱算力预算

- [x] P2: 沙箱算力预算 [P0]
  - [x] P2.1: 后端 `app/config.py` 新增 `sandbox_timeout_seconds: int = 120`、`sandbox_max_concurrent: int = 4`、`sandbox_rate_limit_per_hour: int = 10`
  - [x] P2.2: `app/services/sandbox/runner.py` `execute()` 用 `concurrent.futures.ThreadPoolExecutor(max_workers=1)` 提交 + `future.result(timeout=settings.sandbox_timeout_seconds)`，超时抛 TimeoutError → 转 failed（error_message="timeout after Ns"）
  - [x] P2.3: `app/services/sandbox/scheduler.py` 新增模块级 `_tenant_semaphores: dict[str, threading.BoundedSemaphore]`，`schedule_sandbox_run` 前检查同租户并发数，超限返回 sentinel，路由层据此返回 429
  - [x] P2.4: `app/api/routes.py` `POST /v1/sandbox/runs` 加每小时速率限制（内存计数器 `_sandbox_rate: dict[str, list[float]]` 按 tenant+user 记录时间戳窗口），超限返回 429
  - [x] P2.5: 后端 `tests/test_sandbox_budget.py`：超时转 failed、并发上限 429、速率限制 429

## P3 — 冷启动兜底文案分阶段

- [x] P3: 冷启动兜底文案分阶段 [par]
  - [x] P3.1: 后端 `app/api/routes.py` `GET /v1/skills` 空列表时返回 `{"skills": [], "cold_start_hint": "stage_0|stage_1_3|stage_4_7|stage_7_plus"}`（基于该用户 UserProfile.observation_days 推荐阶段）
  - [x] P3.2: 后端 `tests/test_skill_delivery.py` 补：空列表返回 cold_start_hint 字段，且阶段与 observation_days 对应
  - [x] P3.3: Android `res/values/strings.xml` 新增 4 档冷启动文案字符串资源（cold_start_stage_0/1_3/4_7/7_plus）+ 加载失败文案（cold_start_load_failed）
  - [x] P3.4: Android `ui/SkillCardHost.kt` 移除 `COLD_START_HINT` 常量，改用 strings.xml 资源；`coldStartHint` 函数改为按 stage 返回对应资源 ID
  - [x] P3.5: Android `ui/TodayScreen.kt` + `SkillListScreen` 区分：skills==null（加载中）、loadFailed==true（失败+重试按钮）、skills.isEmpty()（按 cold_start_hint 显示分阶段文案）
  - [x] P3.6: Android `data/LocalRepository.kt` `fetchSkills()` 返回 `SkillFetchResult(skills, coldStartHint, loadFailed)` 三态
  - [x] P3.7: Android `SkillCardHostTest.kt` 补：4 档文案映射、加载失败重试

## P4 — 机构去标识群体画像

- [x] P4: 机构去标识群体画像 [P0]
  - [x] P4.1: 后端 `app/services/tenant_portrait.py` 新建 `build_tenant_portrait(db, tenant_id) -> dict`：聚合 mood_hint 分布（从 UserProfile.traits.recent_mood_hint）、observation_days 统计、近 7 天活跃用户数（DistinctFeature.window_start 去重 user_id）、escalation 计数（复用现有 metrics 逻辑）、Skill 下发数
  - [x] P4.2: 去标识保护：任何聚合桶计数 < 5 时合并到 "other" 桶（`_suppress_small_buckets(counts: dict, threshold=5) -> dict`）
  - [x] P4.3: 后端 `app/schemas.py` 新增 `TenantPortraitOut`（mood_distribution/observation_stats/active_users_7d/escalation_metrics/skill_count）
  - [x] P4.4: 后端 `app/api/routes.py` 新增 `GET /v1/tenant/portrait`（require_roles admin/professional/auditor + append_audit action="tenant.portrait.view"）
  - [x] P4.5: 后端 `tests/test_tenant_portrait.py`：聚合正确性、小桶合并、跨租户隔离、角色权限（user 403）

## P5 — 灰度回滚方案

- [x] P5: 灰度回滚方案 [P0]
  - [x] P5.1: 后端 `app/models.py` `Tenant` 表新增 `feature_flags: JSON` 字段（默认 `{"passive_sensing_enabled": true, "sandbox_enabled": true, "skills_delivery_enabled": true}`）
  - [x] P5.2: 后端 `alembic/versions/` 新增迁移 `20260731_0002_tenant_feature_flags.py`（down_revision="20260731_0001"，ALTER TABLE add column）
  - [x] P5.3: 后端 `app/services/feature_flags.py` 新建 `get_tenant_flags(db, tenant_id) -> dict` + `set_tenant_flag(db, tenant_id, key, value)`
  - [x] P5.4: 后端 `app/api/routes.py` 被动感知路由（ingest/sandbox/skills）前置 `require_feature_flag("passive_sensing_enabled"/"sandbox_enabled"/"skills_delivery_enabled")` 依赖，关闭则 410
  - [x] P5.5: 后端新增 `GET /v1/config/flags`（用户拉取本租户 flags）+ `PUT /v1/tenant/flags`（admin 修改 flags）
  - [x] P5.6: 后端新增 `POST /v1/skills/batch-retire`（admin 批量 transition 到 retired，body=skill_id 列表）
  - [x] P5.7: 后端 `tests/test_feature_flags.py`：flag 关闭后路由 410、admin 修改 flag、用户拉取 flag、批量 retired
  - [x] P5.8: Android `data/LocalRepository.kt` 新增 `fetchFeatureFlags(): Map<String, Boolean>`（调 GET /v1/config/flags，缓存到 AppPreferences）
  - [x] P5.9: Android `AppPreferences.kt` 新增 feature flags 缓存读写；`PassiveSensingService` 启动前检查 `passive_sensing_enabled`，关闭则不启动
  - [x] P5.10: Android `ui/TodayScreen.kt` + `SkillCardHost.kt` 检查 `skills_delivery_enabled`，关闭时隐藏 Skill 卡片区显示"能力下发已暂停"

# Task Dependencies
- P1/P2/P3/P4/P5 互相独立，全部可并行
- P5.4 依赖 P5.1-P5.3（flag 字段 + 服务先建）
- P5.8-P5.10 依赖 P5.5（GET /v1/config/flags 路由先建）
- P3.1 依赖 UserProfile（Phase 1 已有）
