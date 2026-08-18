# ECHO Mind 架构走读（压缩版）

> 按 HEAD（本轮深化迭代后）代码走读；真值纪律：所有路径/类名经实测核对（settings.gradle.kts / build.gradle.kts / 源文件逐一定位）。改进明细不在此复制，见 §8 索引。

## 1. 总览

### 1.1 技术栈

- **Android**：Kotlin + Jetpack Compose（BOM 管理）+ Room(KSP) + WorkManager + AGSL；Gradle 9.5、compileSdk 37、minSdk 26、JVM 17；detekt 静态门禁（rootProject `detekt.yml`）。
- **backend**：Python FastAPI + SQLAlchemy + Alembic + Pydantic；依赖锁 uv.lock；Dockerfile + docker-compose。
- **腕上**：Xiaomi Vela 快应用（.ux 声明式页面 + JS common 层），构建产物 `.rpk`。
- **门禁/发布**：Python 脚本族 + GitHub Actions（5 workflows）+ Makefile 入口。

### 1.2 顶层目录布局（实测）

| 目录 | 内容 |
|---|---|
| `android/` | 14 模块 Gradle 工程（app + 9 feature + 4 core） |
| `backend/` | FastAPI 服务：`app/api`（路由）、`app/services`（服务链）、`alembic`（迁移）、`tests/` |
| `wearable/xiaomi-vela/` | Vela 快应用：`src/`（三页面 + common）、`tests/`（node 测试 + 模拟器） |
| `scripts/` | ~30 个门禁/发布/校验脚本 + `version_source.json`（版本单源） |
| `docs/` | PRD / STATUS / contracts / design / architecture / ADR 等 |
| `content-packs` `qa` `safety-eval` `integrations` `pilot-pack` `releases` `dist` | 内容包 / QA / 安全评测 / answatch 集成 / 试点 / 发布产物 |
| 根目录 | Makefile、docker-compose.yml、SOURCE_MANIFEST.sha256、sbom.spdx.json、BUILD_PROVENANCE.json、RELEASE_ARTIFACT_MANIFEST.sha256 |

### 1.3 三世界

| 世界 | 职责 | 边界 |
|---|---|---|
| 手机 App | 感知→本地画像→视觉呈现→加密同步 | 端侧 Me-vs-Me，原始音频不落盘 |
| backend | 租户/用户/画像核心：ingest→aggregate→baseline→portrait→narrative | 行为观察非诊断；人工支持（escalation）与画像链独立（main.py 描述明文） |
| 腕上 | Vela 端只读呈现 + 白名单观察 | 只收投影信封；触觉节流；隐私白名单 |

### 1.4 版本与构建（version_source 单源）

- `scripts/version_source.json`：`release_version/backend_package_version/android_version_name` = **0.11.0**、`android_version_code` = **8**、`contract_manifest_version` = **0.7.0**、`release_notes_file`、`sbom_created_utc`。
- 派生链：`update_release_metadata.py` → SOURCE_MANIFEST / SBOM / provenance / artifact manifest / 版本一致性测试（backend/tests/test_version_consistency.py）。
- Makefile 门禁入口（实测 11 目标）：`preflight`、`backend-test`、`backend-run`、`openapi`、`safety`（4 脚本串联）、`sbom`、`android`（testDebugUnitTest+lintDebug+detekt，与 CI 一致）、`android-wearable`（:feature:wearable 独立门禁）、`wearable-node`（node tests/run.js）、`answatch-golden`、`package`。

## 2. Android 分层（14 模块）

### 2.1 依赖方向

```text
app ──────────────────────────────► 全部 feature:* + core:*
feature:qa ──► observation/presence/presencevisual*/journey/memory/intelligence + core:model/ports/visual（*test-only）
feature:journey ──► presence + intelligence + memory + core:model/visual
feature:presence ──► observation + core:model/visual
feature:intelligence ──► memory + core:model/security/ports
feature:wearable ──► 仅 core:model + core:ports（架构冻结例外 ERA 33，禁 app/Room/仓 SDK）
feature:observation / memory ──► core:model
feature:actions ──► 零 project 依赖（coroutines 叶子）
core:ports ──► core:model；core:visual ──► core:model；core:security / core:model ──► 零 project 依赖
```

### 2.2 模块职责表（14 项，每项≤6 行）

| 模块 | 职责 | 核心入口 | 关键类 | 消费方 |
|---|---|---|---|---|
| `:app` | 壳：DI 组装、Compose UI、Services、Room、Outbox 同步 | `EchoMindApplication.kt` | AppContainer / MainActivity / EchoMindApp / SyncWorker / PassiveSensingService | 唯一可安装入口 |
| `:feature:actions` | 干预策略 + 技能行动运行时 | `InterventionPolicy.kt` | InterventionPolicy、EchoActionRuntime | app、intelligence |
| `:feature:memory` | 记忆域：自我模型/稳定模式/人话化/上下文例外 | `EchoMemory.kt` | EchoMemory、EchoSelfModel、StablePattern、MemoryHumanizer、ContextExceptions | app、intelligence、journey、qa |
| `:feature:observation` | 感知采集（传感/屏幕/App/麦克风/通知）+ 本地画像引擎 | `SensingEventHub.kt` | SensorCollector、FeatureExtractor、LocalPortraitEngine、SensingWindowScheduler、SensingConsentGate | app、presence、journey、qa |
| `:feature:presence` | 存在感域：身份种子/日构图/生命周期季相/壁纸调度 | `EchoIdentity.kt` | EchoIdentity、VisualProfile（含 EchoVisualMapper）、AmbientEngine、DailyCompositionGate、WallpaperRenderController | app、journey、qa |
| `:feature:presencevisual` | Compose 渲染实现：AGSL/Canvas 双后端 | `EchoRendererFacade.kt` | EchoRendererFacade、AgslEchoBackend、OrganismCanvasRenderer、EchoOrganismRenderer、VisualLabMetrics | app、qa(test) |
| `:feature:intelligence` | AI Provider 编排/上下文编译/叙事蒸馏/接地校验 | `AiProviderManager.kt` | AiProviderManager、EchoContextCompiler、NarrativeDistiller、PersonalAnswerEngine、GroundingValidator | app、journey、qa |
| `:feature:journey` | 时间长河：周/月/季/年视图与记忆回溯 | `JourneyCanonical.kt` | JourneyCanonical、JourneyRiver、JourneyNarrative、JourneyOrganismVisuals、JourneyMemoryPort | app、qa |
| `:feature:qa` | 产品快照/日模拟/金标准/差异日评测 harness | `QaProductSnapshot.kt` | QaDaySimulator、QaPortraitMirror、VisualReviewRenderer、OrganismQualityHarness、QaHeadlineEngine | 仅测试/QA |
| `:feature:wearable` | 腕上域：协议/投影/隐私/策略（纯 Kotlin，org.json） | `WearProtocol.kt` | WearProtocol、WearPresenceProjector、WearablePrivacyProjector、WearablePolicy、WearMessageCodec | app（vendor 适配） |
| `:core:model` | 纯域模型，零 Android 依赖 | `EchoPresenceState.kt` | EchoPresenceState、PortraitCore、Models、EchoMemoryContract | 全部 |
| `:core:ports` | 跨域端口接口 | `MemoryPorts.kt` | MemoryPorts、PresencePorts、ObservationPorts | wearable、intelligence、qa |
| `:core:visual` | 视觉纯逻辑：参数→基因组→场景→帧 | `VisualGenomeCompiler.kt` | VisualGenomeCompiler、EchoSceneCompiler、OrganismFrameComputer、OrganismTopology、MotionEvaluator | presence/presencevisual/journey/qa |
| `:core:security` | Keystore 密钥/HKDF 派生/字段加密/DB 口令编排 | `AndroidKeystoreFieldCipher.kt` | AndroidKeystoreFieldCipher、FieldCipher、HkdfSha256、DatabasePassphraseDerivation、DatabaseOpenOrchestrator | app、intelligence |

### 2.3 模块规模（main 源集 .kt 文件数，实测）

| 模块 | 文件数 | 模块 | 文件数 |
|---|---|---|---|
| :app | 110 | :feature:wearable | 19 |
| :feature:intelligence | 18 | :feature:observation | 17 |
| :feature:journey | 14 | :feature:qa | 14 |
| :feature:presence | 9 | :feature:presencevisual | 7 |
| :core:visual | 19 | :core:security | 8 |
| :core:model | 7 | :feature:memory | 6 |
| :core:ports | 3 | :feature:actions | 2 |

合计约 253 个 main 源文件；app 占 110（UI 壳 + data + Services），域逻辑全部下沉 feature/core，qa 以 14 文件维持产品快照 harness。

## 3. app 模块走读

### 3.1 启动链（实测顺序）

1. `EchoMindApplication`（Application + WorkManager `Configuration.Provider`）——onCreate 注册周期 Worker：EveningReminder / MessageCheck / PresenceRefresh / SensingWatchdog。
2. `AppContainer(context)`——组合 `di/EchoContainers.kt` 七容器：CoreContainer → ObservationContainer → PresenceContainer / MemoryContainer / IntelligenceContainer / ActionContainer / JourneyContainer，外加 `di/WearableContainer`；每个容器领域内自建对象图，Root 只组合 + 跨域编排；`openDatabase()` 经 DatabaseOpenOrchestrator 加密开门。
3. `EchoRuntimeCoordinator`——sensing / presence / provider / memory 四组件健康度聚合。
4. `MainActivity`——enableEdgeToEdge + setContent。
5. `EchoMindApp`——Onboarding 门 → Scaffold 三 tab：`Tab.ECHO`→`EchoSceneScreen`、`Tab.JOURNEY`→`JourneyScreen`、`Tab.ME`→`MeScreen`；`shouldShowEmergencyFab`：危机 FAB 除 Me tab 常驻，直达全屏 `SafetyScreen`。

### 3.2 Services 与 Workers

| 组件 | 角色 |
|---|---|
| `EchoWallpaperService` | 壁纸 Engine，经 WallpaperRenderController/AmbientEngine 供帧 |
| `EchoDreamService` | 锁屏/梦境呈现 |
| `PassiveSensingService` | 前台服务：拉起 4 Collector + SensingWindowScheduler（5 分钟窗口）；NotificationCollector 系统独立绑定，事件经 SensingEventHub 汇入 |
| `SyncWorker` | Outbox 上行 + 画像回读（网络约束 + AuthTokenRefresher） |
| `EveningReminderWorker` / `MessageCheckWorker` / `PresenceRefreshWorker` / `SensingWatchdogWorker` | 周期任务与感知看护 |
| `ServiceRevocation` | 服务撤销处理；`MicCollector` 麦克风派生特征采集 |

### 3.3 data 层（`data/` 24 文件）

| 分层 | 文件/类 |
|---|---|
| 偏好/令牌 | `AppPreferences`、`PassiveSensingPrefs`、`AuthTokenRefresher` |
| 数据库 | `EchoDatabase` + `database/EchoDatabaseMigrations`（Room）、`PreferencesDatabaseSecretStorage`（DB 秘密） |
| 上行队列 | `outbox/Outbox`（唯一入口：`cipher.encrypt` 落 payloadCiphertext）、`SyncEnqueue`、`SyncState`/`SyncStateRepository` |
| Repository | `PortraitRepository`、`SensingRepository`、`MemoryRepository`、`MessageRepository`、`SkillRepository`、`OnboardingRepository`、`ConsentRepository`、`EscalationRepository`、`FeatureFlagRepository` |
| 其他 | `ApiClient`（HTTP）、`LocalDataRights`（本地数据权）、`LocalPortraitDataSource`、`PortraitMappers`/`PortraitParsers`、`DeterministicPersonalAnswerProvider` |

### 3.4 ui 层速览

`ui/EchoMindApp`（Scaffold/tab）+ `EchoSceneScreen`（ECHO 首页）+ `echo/`（ViewModel、ActionLayer、ConversationLayer、WhyLayer、VisualSurface、PortraitStates）+ `journey/`（14 文件时间河视图）+ `me/`（12 文件设置/数据权/订阅/腕上）+ `OnboardingScreen`/`SafetyScreen`/`EchoAskScreen`/`debug/VisualLabScreen`。

## 4. backend 走读

### 4.1 入口与中间件链

- `backend/app/main.py`：FastAPI("ECHO Mind Portrait Core API")；lifespan——local 环境 `create_all`（生产必须 Alembic）。
- 中间件顺序：CORS → `request_context_and_security_headers`：
  - X-Request-ID（header 或 `req_{uuid}`）注入 `request_context.current_request_id`；
  - 安全头：nosniff / X-Frame-Options DENY / no-referrer / no-store / Permissions-Policy(camera/mic/geo 全禁)；
  - `telemetry.log_request`：隐私安全遥测，只记 method/path/status/duration_ms/request_id。
- `import app.services.immutability` 即注册 append-only ORM guard（import 副作用）。
- `api/routes.py` 聚合 **15 个子 router**，全部 `prefix="/v1"`。

### 4.2 路由全景表（文件→路径组→门禁）

| 文件 | 路径组（实测） | 门禁依赖（deps.py） |
|---|---|---|
| onboarding.py | /tenants、/users、/onboarding/l0、/onboarding/emergency-contact、/onboarding/verify-code | bootstrap key / step-up |
| auth_refresh.py | POST /auth/refresh | refresh token |
| consent.py | /onboarding/consents（POST、GET latest） | principal + 租户 |
| features.py | POST /features/ingest | require_passive_sensing_consent |
| portraits.py | /portraits*、/baseline/status、/me/portraits/today|list|rebuild|feedback | consent + step-up（rebuild） |
| narratives.py | GET /narratives | 认证 + 范围 clamp |
| messages.py | GET /me/messages | 认证 |
| profiles.py | /profile/{user_id}（GET、rebuild） | psych_content 角色 + consent |
| subscription.py | GET /me/subscription | 认证 |
| escalations.py | /escalations 全套：create/list/metrics/sla-scan/{id}/ack/takeover/close/review/case-review/user-status | 角色分级 + SLA |
| data_rights.py | /data-subject-requests（POST/GET/{id}/complete） | principal（DSR） |
| skills.py | /skills（list/详情/completions/{id}/transition） | require_write_role + require_active_subscription |
| sandbox.py | /sandbox/runs（POST、GET） | 隔离审计 |
| admin.py | /audit/events|verify、/config/flags、/tenant/flags、/tenant/portrait、/admin/activation-codes（POST/GET/revoke）、/skills/batch-retire | 管理员角色 |
| legacy.py | /checkins、/journals、/safety/check、/questionnaires、/practices + 旧删除路由 | 410 语义退役中 |

### 4.3 服务链（ingest→aggregate→baseline→portrait→narrative）

1. `api/features.py` ingest → `services/aggregates/calculator.upsert_daily_aggregate`（时区处理 timezone.py）。
2. → `services/portrait/materializer`（mark_dirty / materialize_dirty 脏标记物化）。
3. → `services/baseline/calculator`（circular 循环统计 / confidence / day_type / metrics，Me-vs-Me 个人基线）。
4. → `services/portrait/engine + dimensions + explain + narrative`（每日画像维度计算/解释/叙事）。
5. 辅助：crypto、audit、escalation、safety、subscription、feature_flags、tenant_portrait、trends、activation、schema_registry、scoring（退役中）、sandbox/{runner,worker,tool_forge,tool_validator,slots,scheduler,audit_day,gap_finder,sanitizer,skill_induct}。

### 4.4 Alembic 迁移链尾（实测）

…→ 20260810_0007_daily_portraits → 20260812_0001_portrait_materialization → 20260812_0002_portrait_phase5 → 20260813_0001_portrait_feedback → 20260814_0001_subscription → 20260816_0001_refresh_tokens → **20260818_0001_query_indexes（本轮新增复合索引）**。

### 4.5 auth / RBAC / 租户

- 身份：`app/auth.py` + `deps.get_principal`（DB + PRINCIPAL 注入）。
- RBAC：`require_write_role` / `require_psych_content_role` / `require_step_up`（敏感对象二次确认）。
- 商业门：`require_active_subscription`。
- 同意四门：`require_psychological_consent` / `require_passive_sensing_consent` / `require_voice_features_consent` / `require_feature_flag`。
- 租户隔离：`ensure_user`（tenant 校验）；升级流：`get_escalation` / `open_escalation`。

### 4.6 测试面速览

`backend/tests/` 40+ 文件：契约金标准（test_portrait_golden / test_mirror_golden / test_portrait_contract_fixtures）、端到端链（test_portrait_e2e_full_chain / test_e2e_privacy / test_e2e_sandbox）、门禁闭环（test_feature_flags_fail_closed / test_immutability_v03 / test_dsr_matrix）、专项（test_auth_refresh / test_baseline / test_materializer / test_activation_codes / test_deprecated_routes 等）。Android 侧各模块自带 src/test（含 Robolectric 与黄金图），`:feature:qa` 兼作跨模块评测 harness。

## 5. wearable 走读

### 5.1 Kotlin 域（:feature:wearable）与 app 适配层

- 域内（禁 Android UI / Room / 仓 SDK）：`WearProtocol`（WEAR_SCHEMA_V1=1）、信封族 WearMessage / WearPresenceEnvelope / WearObservationEnvelope / WearActionEnvelope、`WearMessageCodec`、`WearPresenceProjector`、`WearablePrivacyProjector`、`WearablePolicy`、`WearSourceArbitration`、`WearableRuntime(State)`、`research/AnsObservation*`（观察研究管线 + AnsPromotionPolicy）。
- app 层 vendor 适配（`app/…/wearable/`）：`XiaomiWearVendorBoundary`、`XiaomiWearCapabilityMapper`、`di/WearableContainer`、`WearablePrefs`、`WristObservationLog`、`FakeWearablePlatformAdapter`/`NoopWearablePlatformAdapter`（调试/无设备降级）。

### 5.2 Vela 端页面结构（wearable/xiaomi-vela/src/）

| 文件 | 职责 |
|---|---|
| `manifest.json` | package `com.yunjue.echo.mind`；features：router/interconnect/sensor/vibrator/storage |
| `echo/index.ux` | 存在感主呈现（生物体投影） |
| `why/index.ux` | WHY 解释页（门控后一行克制 headline） |
| `action/index.ux` | ACTION_MENU 轻行动菜单 |
| `common/transport/interconnect_bridge.js` | 与手机互传 |
| `common/protocol/wear_protocol.js` | WearProtocol JS 镜像 |
| `common/presence/presence_cache.js` | 投影缓存 |
| `common/visual/wear_visual.js` | 视觉降级渲染 |
| `common/sensor/accel_summary.js` | 加速度摘要（上行白名单观察） |
| `common/haptic/haptic_throttle.js` | 触觉 ≥1.5s 节流（T7-P2-6 本轮已修） |
| `common/cache/storage_wrap.js` | 存储封装 |
| `i18n/zh-CN.json` `i18n/en.json` | 双语 |
| `tests/` | run.js / preflight.js / declared_features_test.js / simulator HTML 快照 |

### 5.3 同步边界

- 手机→腕上：仅投影（`WearPresenceProjector.project*` 五约束：几何简化/动画预算/隐私/屏形/功耗）。
- 腕上→手机：仅白名单观察（accel 摘要 → AnsObservation 管线 + `WearSourceArbitration` 仲裁）。
- 隐私白名单：`WearablePrivacyProjector` VISUAL_FIRST——publicHeadline 默认 null；仅用户主动 WHY 生成；学习期（<KNOWN）只给 SEED/DISCOVERING 中性表达；成熟期才给 LATE/QUIET/ACTIVE 氛围词。
- 触觉双保险：Kotlin `WearablePolicy.HapticEvent/HapticRateLimiter` + Vela `haptic_throttle.js`。

## 6. 基础设施

> 门禁哲学：所有「声称」必须有脚本可验证（claim_scan），所有产物必须有清单可追溯（SOURCE_MANIFEST/SBOM/provenance），所有发布必须有闭环（release-closure）。

### 6.1 scripts 门禁体系（每脚本一句）

| 脚本 | 一句话职责 |
|---|---|
| version_source.json | 版本单一事实源（0.11.0 / code 8 / contract 0.7.0） |
| update_release_metadata.py | 派生全套发布元数据（manifest/SBOM/provenance/artifact） |
| release_preflight.sh / package_release.sh | 发布前检 / 打包 |
| verify_final_package.py | 终包多步校验（含 SOURCE_MANIFEST 闭环） |
| build_source_archive.py / verify_source_archive.py / verify_source_manifest.py | 源归档构建与两向校验 |
| generate_sbom.py | SPDX SBOM（uv.lock 闭包 80 包） |
| generate_provenance.py | 构建溯源 provenance |
| verify_workflow_pins.py | CI action pin 校验 |
| verify_wrist_signing.py | 腕端 .rpk 签名校验 |
| check_dynamic_code.py | 动态代码静态禁令（多模块扫描） |
| claim_scan.py | 文档声明-实现漂移扫描 |
| generate_source_reality.py / generate_dependency_graph.py | 源真值报告 / 依赖图 |
| contract_compliance_check.py / contract_drift_check.py | 契约符合性 / 漂移门禁 |
| validate_content_packs.py | 内容包校验 |
| audit_dependencies.py | 依赖审计 |
| safety_eval.py / affective_eval.py（+fixtures/test） | 安全评测 / 情感评测 |
| fault_injection_check.py | 故障注入检查 |
| backup_restore_drill.py | 备份恢复演练 |
| android_release_static_check.py | Android 发布静态检 |
| refresh_status_numbers.py | docs/STATUS.md 数字再生成 |
| collect_wallpaper_metrics.sh | 壁纸指标采集 |
| smoke.sh / build_final_package.py / distribution.py | 冒烟 / 终包 / 分发 |

### 6.2 CI workflows（5 条）

`android-ci.yml`（test+lint+detekt）、`backend-ci.yml`（pytest）、`security-ci.yml`、`source-integrity.yml`（源完整性）、`release-closure.yml`（发布闭环）。

### 6.3 发布元数据链

`version_source.json` → `update_release_metadata.py` → `SOURCE_MANIFEST.sha256`（1313 文件）→ `sbom.spdx.json`（80 包）→ `BUILD_PROVENANCE.json` → `RELEASE_ARTIFACT_MANIFEST.sha256`（APK/idsig/rpk）→ `verify_final_package.py` 闭环。

## 7. 四条端到端链路

> ① 本地当日视觉链（不联网即可活）；② 用户反馈闭环（端内即时 + 上行画像反馈）；③ 加密同步链（端↔backend 全往返）；④ 腕上投影链（受隐私白名单与触觉节流约束）。

### 7.1 ① 感知→视觉（本地当日链）

1. `PassiveSensingService` 拉起 SensorCollector / ScreenCollector / AppActivityCollector / MicCollector；NotificationCollector 系统绑定。
2. 事件汇入 `SensingEventHub`（`SensingConsentGate` 门控）。
3. `SensingWindowScheduler` 5 分钟窗口聚合 → `FeatureExtractor`（`MicFeatureExtractor` 派生特征；原始音频不落盘）。
4. `LocalPortraitEngine`（+ LocalAggregateCalculator / LocalBaselineCalculator / LocalPortraitMath / LocalPortraitDigest）纯端侧 Me-vs-Me。
5. `PresenceRepository` 融合云端画像与本地态。
6. `VisualProfile` 内 `EchoVisualMapper`：画像 → 视觉参数。
7. `core/visual`：`VisualGenomeCompiler` 编译基因组 → `EchoSceneCompiler` 场景化 → `OrganismFrameComputer` 逐帧（腔体半径/深度/运动预算）。
8. `feature/presencevisual`：`EchoRendererFacade` 按设备分流 `AgslEchoBackend`（AGSL shader）或 `OrganismCanvasRenderer`（Canvas）。
9. app `ui/echo/components/EchoVisualSurface` → `EchoSceneScreen`（ECHO tab / Home）。
10. 壁纸支线：`EchoWallpaperService` + `WallpaperRenderController`；身份/构图资格：`EchoIdentity` / `LifeSeasonTracker` / `DailyCompositionGate`。

### 7.2 ② 反馈→纠正脉冲

1. ECHO Scene 内 Like/NotLike（对话层与画像陈述两入口）。
2. `EchoSceneViewModel` 持有 `EchoCorrectionService.recordConversationFeedback / recordPortraitCorrection`（correctionWriter = MemoryRepository，落 contextExceptions 稳定模式）。
3. 纠错触发 correction chips 重排；同时 `EchoSceneScreen` `correctionPulseTrigger++`。
4. `EchoVisualSurface(correctionPulseTrigger)` → `OrganismFrameComputer.correctionPulseAgeNanos`。
5. `MotionEvaluator.correctionPulse(sinceMs)` 返回 (haloDelta, pauseSeconds) 脉冲包络——生物体一次 Halo 收放-停顿，回应「被听见」。
6. 画像级 helpful 反馈：`PortraitRepository` 以 `"LIKE"/"NOT_LIKE"` POST `/v1/me/portraits/feedback` 上行。

### 7.3 ③ Outbox 加密同步链

1. 所有上行事件必须经 `Outbox.enqueue`：`FieldCipher/AndroidKeystoreFieldCipher.encrypt` → payloadCiphertext（Keystore + HKDF 派生，`core/security`）。
2. `SyncEnqueue` 触发 `SyncWorker`（网络约束 + `AuthTokenRefresher` 续 token）。
3. 按 event_type 路由：derived_feature → `POST /v1/features/ingest`。
4. backend：`upsert_daily_aggregate` → `materializer.mark_dirty/materialize_dirty` → `baseline/calculator` 更新基线 → `portrait/engine` 重算当日画像。
5. 端上下次同步 `GET /v1/me/portraits/today` 回读 → `PortraitRepository` / `LocalPortraitDataSource` 更新 → 回到链路①视觉管线。
6. 失败入 dead-letter（`SyncStateRepository` 跟踪，Me tab 可见）。

### 7.4 ④ 腕上链

1. 手机端 `WearPresenceProjector`（几何/动画/屏形/功耗投影）+ `WearablePrivacyProjector`（VISUAL_FIRST、WHY 门控 + 成熟度分级）。
2. `WearMessageCodec` 编码为 `WearProtocol`（WEAR_SCHEMA_V1）信封。
3. app 层 `XiaomiWearVendorBoundary` 经 interconnect 下发。
4. Vela `interconnect_bridge.js` 接收 → `echo/index.ux` + `presence_cache.js` + `wear_visual.js` 呈现。
5. `why/index.ux` 用户主动请求解释（门控后一行克制 headline）；`action/index.ux` 轻行动菜单。
6. 触觉：`haptic_throttle.js` ≥1.5s 节流 + `WearablePolicy.HapticRateLimiter` 双保险。
7. 上行仅白名单观察：`accel_summary.js` → AnsObservation 研究管线，不越隐私边界。

## 8. 改进点索引（不复制明细）

- 逐行走读审计发现：`.trae/specs/audit-repo-line-by-line/findings/T2-visual-chain.md`、`T3-presence-identity.md`、`T4-app.md`、`T5-domain-features.md`、`T6-backend.md`、`T7-boundary.md`、`T8-test-quality.md`（P1/P2/P3 分级，P3 留档）。
- 深化迭代分诊与收口台账：`.trae/specs/deepen-iteration-p2-ux/notes/LEDGER.md` — P2 共 61 条。
- FOLLOW_UP 8 项：T2-P2-2（OrganismTopology 盐空间冲突）、T2-P2-5（AGSL 丢弃多层，余 cavity shader 子项）、T3-P2-4（SCREEN_TIMING 量-时混义）、T4-P2-6（ACKNOWLEDGED 不可达）、T6-P2-4（escalation 豁免绕过）、T6-P2-8（scoring/safety 死代码退役）、T7-P2-5（Vela 模拟器结构镜像）、T8-P2-5（PG 迁移 round-trip CI 基建）。
- 本轮已清偿摘要：FIX_THIS_ROUND 51/51 全部完成（T2 10/10 含盐分段与 AGSL 层对齐、T3 6/6、T4 9/9、T5 9/9、T6 7 FIX + 2 ABSORBED、T7 8 FIX + 1 ABSORBED、T8 8/8）；2 条 ABSORBED（T6-P2-1 订阅门禁、T7-P2-1 SBOM 80 包）；P3 顺手修 20+ 项；附带修复 POST /users 重复 external_ref 500→409；42 个黄金哈希因盐分段 / dayComposition 真实化 / depth01 满幅映射统一再生，一致性 smoke 全绿。逐项证据见同目录 `T2–T8-notes.md`。
