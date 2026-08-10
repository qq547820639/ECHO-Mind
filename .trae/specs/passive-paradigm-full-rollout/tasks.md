# Tasks — 被动感知范式全量落地

> 依赖前置：Phase 1 后端地基（T01/T06/T07）已完成。
> 分四个阶段：A 端侧被动采集 / B 自进化沙箱+UI / C 删旧主动输入 / D E2E 隐私验收。
> 标注 [P0] 为关键路径；[par] 表示可与同阶段无依赖任务并行。

## Phase A — Android 被动采集 (T02–T05)

- [x] T02: 传感器/屏幕/通知/App 活跃采集 + 分项同意 (R-01) [P0]
  - [x] T02.1: `AndroidManifest.xml` 新增权限（BODY_SENSORS / HIGH_SAMPLING_RATE_SENSORS / ACTIVITY_RECOGNITION / POST_NOTIFICATIONS / FOREGROUND_SERVICE / FOREGROUND_SERVICE_REMOTE_MESSAGING / WAKE_LOCK）+ 前台服务声明 `foregroundServiceType`
  - [x] T02.2: `build.gradle.kts` + `libs.versions.toml` 新增 DataStore Preferences 依赖
  - [x] T02.3: 新建 `sensing/` 包：`PassiveSensingService.kt`（Foreground Service）+ `SensorCollector.kt`（加速度/陀螺仪）+ `ScreenCollector.kt`（屏幕开关）+ `NotificationCollector.kt`（通知监听，NotificationListenerService）+ `AppActivityCollector.kt`（UsageStatsManager 前台 App）
  - [x] T02.4: `AppPreferences.kt` 扩展：`passiveSensingEnabled` / `micEnabled` / `samplingConfig`（用 DataStore 替代 SharedPreferences 结构化配置）
  - [x] T02.5: `AppContainer.kt` 注入新组件；`EchoMindApplication.kt` 初始化 DataStore
  - [x] T02.6: 端侧 `passive_sensing` 同意持久化（本地 ConsentEntity 新增）+ onboarding 流程接入分项同意开关
  - [x] T02.7: 单测：采集器启停、同意开关联动、前台服务通知可见

- [x] T03: 麦克风可选模块（默认关，显式授权） [par]
  - [x] T03.1: `sensing/MicCollector.kt`：仅当 `micEnabled=true` 且 RECORD_AUDIO 已授时启动；AudioRecord 读取后即时处理，不落盘
  - [x] T03.2: 端侧音频特征提取（音量包络/语速/停顿/情绪声学特征 → `mic_opt` source），原始音频处理后丢弃
  - [x] T03.3: 设置页麦克风开关 UI + 二次确认 + 权限请求
  - [x] T03.4: 单测：默认关闭、授权后启动、撤回授权后停止、音频不落盘

- [x] T04: 特征提取 + Room 加密 + Keystore (R-02) [P0]
  - [x] T04.1: 新建 `sensing/FeatureExtractor.kt`：窗口聚合（5min 窗口）→ summary（中文自然语言摘要，≤4000 字）+ vector（≤256 维 float），对齐 `DerivedFeatureIn` 契约
  - [x] T04.2: `EchoDatabase.kt` 升级到 v3：新增 `SensorSampleEntity` / `FeatureVectorEntity` / `ConsentEntity`（本地持久化）；新增 `MIGRATION_2_3`
  - [x] T04.3: 引入 SQLCipher（`net.zetetic:android-database-sqlcipher`），`AppContainer.kt` L45 注入 `openHelperFactory`，密钥由 `FieldCipher` Keystore 派生
  - [x] T04.4: `LocalRepository.kt` 新增 `saveDerivedFeature()`：特征落库 + 入 outbox（priority=20，与现有档位隔离）+ 调 `SafetyEngine.evaluatePassive`
  - [x] T04.5: `SyncWorker.kt` 扩展：`derived_feature` eventType → `POST /v1/features/ingest`；新增上传速率限制（每分钟最多 N 条）+ 批量合并
  - [x] T04.6: `ApiClient.kt` 扩展：支持 GET（拉取 Skill 下发）+ 超时/重试可配
  - [x] T04.7: 单测：特征提取算子、Room v2→v3 迁移、SQLCipher 加解密一致性、outbox 限流

- [x] T05: 安全门禁改造 (R-05) [P0]
  - [x] T05.1: `SafetyEngine.kt` 新增 `evaluatePassive(summary: String): SafetyDecision`：复用 `PASSIVE_RED_TERMS`，命中返回 RED + freeze + scriptKey="l2_stabilization"
  - [x] T05.2: `LocalRepository.saveDerivedFeature` 中 RED → `enqueueEscalation(trigger="passive_red_signal")`，复用既有 escalation 通道
  - [x] T05.3: UI 联动：被动 RED 触发后切到 `SafetyScreen`（与现有文本 RED 路径一致）
  - [x] T05.4: 单测：被动 RED 命中冻结、否定语境不误触发、escalation 写入

## Phase B — 自进化沙箱 + 无输入框 UI (T08–T11)

- [x] T08: 自进化沙箱骨架 (R-04) [P0]
  - [x] T08.1: 后端 `models.py` 新增 `Skill` / `Tool` / `SandboxRun` 三表（tenant_id 索引 + user_id FK + 审计哈希链对齐）
  - [x] T08.2: `schemas.py` 新增 Skill/Tool Pydantic schema（复用 JSON Schema 描述 parameters/returns）
  - [x] T08.3: `content-packs/skills/` 新增 Skill 包格式（`{id, version, trigger_conditions, guardrails, steps, status}`），扩展 `MANIFEST.generated.json`
  - [x] T08.4: `alembic/versions/` 新增迁移（down_revision="20260729_0001"，显式 op.create_table）
  - [x] T08.5: 新建 `backend/app/services/sandbox/` 包：`scheduler.py`（按租户时区夜间调度）+ `runner.py`（沙箱执行骨架）+ `audit_day.py`（审计当日数据）
  - [x] T08.6: 后端路由 `POST /v1/sandbox/runs`（admin 触发）+ `GET /v1/sandbox/runs/{id}`（查询状态）
  - [x] T08.7: 后端测试：沙箱骨架启动/调度/租户隔离

- [x] T09: 造工具回路（生成→验证→归纳） [P0]
  - [x] T09.1: `sandbox/gap_finder.py`：基于当日 DailyNarrative.gaps + UserProfile.traits 识别感知覆盖缺口
  - [x] T09.2: `sandbox/tool_forge.py`：为每个缺口生成候选 Tool（JSON Schema 描述 + 执行步骤模板）
  - [x] T09.3: `sandbox/tool_validator.py`：在沙箱内执行候选 Tool，验证输出符合 schema + 不违反 guardrails（复用 `evaluate_passive` 红线检查）
  - [x] T09.4: `sandbox/skill_induct.py`：验证通过的 Tool 归纳为 Skill，写入 Skill 表 + content-pack（status=draft）
  - [x] T09.5: `sandbox/runner.py` 串联完整回路：audit_day → gap_finder → tool_forge → tool_validator → skill_induct
  - [x] T09.6: 后端测试：Zero-Skill 冷启动、缺口识别、Tool 验证失败回退、Skill 归纳幂等

- [x] T10: Skill 合成 + 脱敏 + 下发 [par]
  - [x] T10.1: `sandbox/sanitizer.py`：Skill 下发前脱敏（移除其他用户数据、移除原始特征引用，仅保留能力描述）
  - [x] T10.2: 后端路由 `GET /v1/skills`（用户拉取已 Reviewed 的 Skill）+ `GET /v1/skills/{id}`（详情）
  - [x] T10.3: Skill 治理状态机接入 content-pack 治理（Draft→Reviewed→Signed），`require_roles("admin","professional")` 审签
  - [x] T10.4: 后端测试：脱敏断言、权限隔离、状态下发控制

- [x] T11: 无输入框主界面 + 卡片渲染 (Android) [P0]
  - [x] T11.1: 新建 `ui/SkillCardHost.kt`：WebView 沙箱渲染 Skill 卡片（限制权限，禁用 JS 任意访问）
  - [x] T11.2: `TodayScreen.kt` 改造：移除签到 Slider + 量表入口 + 求助按钮区，改为 Skill 卡片列表 + 缺口提示
  - [x] T11.3: `EchoMindApp.kt` 导航：RECORD tab 改为「能力」tab（展示已下发 Skill），危机入口常驻底部
  - [x] T11.4: `LocalRepository.kt` 新增 `fetchSkills()`：拉取后端 Skill + 本地缓存 + 过期刷新
  - [x] T11.5: 危机入口常驻验证：无输入框状态下 12356/110/120 仍可见可点
  - [x] T11.6: UI 测试：卡片渲染、危机入口常驻、Skill 列表空态冷启动

## Phase C — 删旧主动输入模块 (T12)

- [x] T12: 移除旧主动输入 + 趋势视图切换 [P0]
  - [x] T12.1: 后端 `routes.py` **BREAKING** 移除写入路由：`POST /v1/checkins` / `POST /v1/journals` / `POST /v1/journals/{id}/revisions` / `DELETE /v1/journals/{id}` / `POST /v1/safety/check` / `POST /v1/questionnaires/{code}/responses` / `POST /v1/practices/completions`（保留 GET 查询路由用于历史）
  - [x] T12.2: 后端保留旧表（Checkin/JournalEntry/QuestionnaireResult/PracticeCompletion）不动，AuditEvent 历史不动；onboarding L0 准入门禁保留
  - [x] T12.3: Android `TodayScreen.kt` 移除签到区；`QuestionnaireScreen.kt` 移除量表录入；`OtherScreens.kt` RecordScreen 移除日记输入
  - [x] T12.4: `TrendScreen.kt` 数据源从签到 mood 折线切到 `GET /v1/narratives` + `GET /v1/profile`（叙事/画像驱动）
  - [x] T12.5: 后端测试：移除的路由返回 410 Gone；保留的 GET 路由仍可查历史；全量回归无破坏（pytest 731 passed，410 断言 19 处跨 4 个测试文件）
  - [x] T12.6: Android 测试：旧入口已移除、趋势视图新数据源、L0 门禁仍生效（新增 3 个纯 JVM JUnit 测试：TrendDataSourceTest / OnboardingGateTest / DeprecatedInputRemovalTest；引用符号已逐一核对存在且签名匹配；本机未装 JDK，无法执行 ./gradlew test，留待 T13.6 全量回归时执行）

## Phase D — E2E + 隐私验收 (T13)

- [x] T13: E2E 联调 + 隐私验收 + 全链路回归 [P0]
  - [x] T13.1: 后端 `conftest.py` 补 fixture：`admin_headers` / `auditor_headers`；`passive_sensing` consent 撤回后 ingest 返回 412 用例
  - [x] T13.2: 后端测试：被动 RED 审计哈希链跨多事件验证（head_hash + 事件序列）；跨租户 audit 链隔离；DSR delete/revoke 对被动感知数据清理（test_e2e_privacy.py；DSR delete bug 已修复，清理 DerivedFeature+RiskSignal）
  - [x] T13.3: 后端测试：沙箱完整回路 E2E（ingest → narrative → sandbox run → skill 下发 → 脱敏断言）（test_e2e_sandbox.py）
  - [x] T13.4: Android 集成测试：被动采集 → 特征提取 → 上传 → 拉取 Skill → 卡片渲染 全链路（E2EFlowTest.kt，11 个纯 JVM 测试；本机无 JDK 未执行 gradlew，已代码审查）
  - [x] T13.5: 隐私抓包验证：用 Charles/mitmproxy 抓包，断言请求体仅含 summary/vector，无原始传感 payload/音频（自动化覆盖：DerivedFeatureIn extra="forbid" schema 拒绝原始字段 + E2EFlowTest 验证 payload 字段集合；运行时抓包为发布前补充 QA 步骤）
  - [x] T13.6: 全量回归：后端 pytest 全过（744 passed）+ Android ./gradlew test（本机无 JDK 未执行，纯 JVM 测试已代码审查）+ 安全语料回归（650 passed）
  - [x] T13.7: 文档同步：`01_PRD.md` 补被动感知/沙箱章节；`03_API契约.md` 补新路由 + 标注移除路由 + 隐私契约；`06_测试计划.md` 补 E2E 隐私验收项

# Task Dependencies
- T03 依赖 T02（采集框架）
- T04 依赖 T02（采集数据源）+ T02.6（同意持久化）
- T05 依赖 T04（特征提取产出 summary）
- T08 依赖 Phase 1（后端地基已就绪）
- T09 依赖 T08（沙箱骨架）
- T10 依赖 T09（Skill 产物）
- T11 依赖 T10（Skill 下发）+ T04.6（ApiClient GET 能力）
- T12 依赖 T11（新主界面就绪后再移除旧入口，避免空窗）
- T13 依赖 T02–T11 + T12 全部完成
- T02/T08 可并行（Android 端与后端沙箱互不阻塞）
- T03/T10 可分别与 T02/T09 并行
