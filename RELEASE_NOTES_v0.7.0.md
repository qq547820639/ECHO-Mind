# ECHO Mind v0.7.0 — Portrait Core（每日个人画像）发布说明

> 状态：`pilot-candidate`（CODE-FROZEN 结论见交付报告）· 2026-08-12
> 唯一契约：`PORTRAIT_CONTRACT.md`（仓库根）

## 一句话产品定义

> ECHO-Mind 安静地了解你的日常节奏，每天告诉你：今天的你，和通常的你有什么不同。

## 主链（本版本核心闭环）

```
Passive Sensing → Derived Features → DailyBehaviorAggregate → PersonalBaseline
→ DailyPortrait → Today → 7 / 28 Day Portrait Timeline
```

## 本版本新增（v0.7 Portrait Core 封板）

### 后端
- **authenticated current-user Portrait API**：`GET /v1/me/portraits/today`、`GET /v1/me/portraits?days=N`、`GET /v1/me/baseline/status`、`POST /v1/me/portraits/rebuild`（user 由 principal 确定；旧 `/v1/portraits/*?user_id=` 保留兼容，tenant isolation + ensure_user 不变）
- **Automatic PortraitMaterializer**：feature ingest → dirty 标记 → coalesced materialization（15 分钟 debounce）→ PersonalBaseline → DailyPortrait；幂等 / 并发安全（原子 claim）/ late feature 补齐 / 失败重试；GET 无写副作用；rebuild 仅 repair/admin/debug
- **Feature Schema Registry**：`passive-core-v1`（22 维核心）/ `mic-feature-v1`（256 维外围）；schema/source 组合校验（422 + telemetry）；mic 不进入 Portrait Core aggregate
- **DailyAggregate Correctness**：coverage 按 unique (window, schema, source) 窗口；expected windows 按 DST 动态（23h→276 / 24h→288 / 25h→300）；mic/health 过滤
- **PersonalBaseline 增强**：circular median/MAD（active_start/end 跨午夜正确）；`baseline_snapshot_digest` 可复现摘要；weekday/weekend 小样本 fallback（MIN_BUCKET_DAYS=2）
- **Dimension Semantics**：missing != irregular（RHYTHM 缺失省略，不计 STABILITY diff）；minimum meaningful absolute delta（near-zero baseline 不产生巨大 z）；SCREEN_AMOUNT / SCREEN_TIMING 解耦；`rhythm_regularity` → `active_hour_spread`（语义命名修正）
- **Explainability**：禁止渲染原始指标与 `+900%`；absolute floor + bounded percentage + coarse wording
- **Narrative 词汇**（Psychology Review）："移动较少/移动较多/接近" 替换 "安静/活跃/稳定"

### Android
- **本地优先架构 + 端侧画像引擎（订阅制改造）**：无账号/激活码门槛——Onboarding 删除激活码验证，默认本地模式（画像由端侧引擎生成、数据只保存在本机、outbox/SyncWorker 静默）；订阅改为「支持」页可选入口（免费本地版 + 可选付费订阅：订阅激活码复用 verify-code 机制，开通后开启云端同步与专业支持）。端侧引擎镜像后端画像流水线（日聚合 → 28 天基线 → 5 维度画像 → 确定性中文叙事，`localportrait/` 纯 Kotlin 模块，与服务端同输入同输出、有 golden 场景一致性单测）；数据源为本地 `feature_vectors`（Room v8 加 sourcesPresentJson 列，迁移 7→8 纯加列）。已订阅时服务端失败/无网络自动回退本地画像（Today/Trend/Baseline 三处），Today 页显示「画像由本机数据生成」横幅。
- **强类型 Portrait DTO**：`PortraitDimensionDto(value, metric, z)` 嵌套解析（弃用 Map<String,String>/optString）；BaselineStatusDto 类型对齐（bucket_usage String / todayCoverage Double）
- **Privacy Fail-Closed**：SQLCipher 加载失败 fail closed（不回退明文 Room）；FieldCipher 接口化 + AndroidKeystoreFieldCipher（Keystore 不可用即抛异常）+ JvmTestFieldCipher（显式测试实现）
- **Portrait Cache 用户隔离**：PortraitDao 全部查询 SQL 层带 userId；激活码重新登录清理旧用户缓存
- **Immutable Sensing Window**：SensorSample 带时间戳精确窗口归属；extract(snapshot) 不读 live hub；retry 重处理同一 snapshot；buffer 4096（覆盖 5 分钟 @200ms）；Screen/App carry-over 跨窗口状态
- **Onboarding 重构**：WELCOME → PORTRAIT EXPLANATION → CORE DATA CONSENT → MINIMUM SENSING → BASELINE WARMING UP → DONE；L0 解耦；紧急入口常驻；文案对齐 Portrait 定位
- **Permission Degraded**：coreSensingGatePasses（flag+consent+SENSOR）；拒绝 USAGE/NOTIFICATION/MIC 不停止 sensing
- **Phase 6 UX**：headline 非交互 semantic 组件；feedback 走 Outbox 可靠同步（/v1/me/portraits/feedback）
- **跨端契约测试**：canonical JSON fixtures（后端 + Android 同源）+ PortraitContractParseTest + fixture 校验测试；contract-manifest v0.7.0

## 验证（本环境实际执行）

> 下表为 2026-08-14 在 HEAD `9e23c32`（v0.7 封板后 7 个提交）开发机实测回写：
> macOS + Corretto 17 + Android SDK（/tmp/echo-build）+ backend/.venv。

| 项 | 结果 |
|---|---|
| 后端 pytest（全量） | 1055 passed / 1 skipped |
| ruff check app tests | 0 errors（历史 F401 债务已清零） |
| mypy app | 0 errors（历史类型债务已清零） |
| Alembic upgrade→downgrade→upgrade | roundtrip PASS（head 20260813_0001 portrait_feedback） |
| fault_injection_check.py | 18/18 PASS（checker 已修复 LocalRepository 拆分后的路径引用） |
| contract_drift_check.py | CONTRACT OK（58 路径，manifest v0.7.0） |
| OpenAPI 导出 | PASS（58 路径，title：ECHO Mind Portrait Core API） |
| safety_eval / claim_scan / dynamic_code / content packs | PASS |
| Android testDebugUnitTest / assembleDebug / lintDebug / detekt | PASS — 336 tests 0 失败（含离线画像引擎 golden 一致性 + 迁移 7→8 + 本地优先模式 39 项新增）/ APK 构建成功 / lint 0 error / detekt 0 findings |
| Android instrumentation（connectedDebugAndroidTest） | NOT RUN — 无 androidTest 用例且无模拟器（CI 空通过，见 android-ci.yml） |
| PostgreSQL integration | NOT RUN — ENVIRONMENT BLOCKED（无 Docker/psql） |

## 外部发布门（未完成，依赖真实环境）

- Android SDK 全量构建（testDebugUnitTest/assembleDebug/lintDebug）、APK/AAB 签名、API 34/36 模拟器与真机矩阵
- PostgreSQL production integration、KMS/HSM、备份恢复
- 独立渗透测试、外部红队、危机演练
- 心理学 / 隐私文案最终 Review、法务/临床/伦理审批
- **`PORTRAIT_CONTRACT.md` v1.0 修订（本地优先 + 订阅制产品模式）合规/法务审批**；支付集成（Apple/Google 计费或自建支付）与订阅激活码发放链路
- 真实用户试点与机构 Pilot

## 历史

- v0.6.1 hardening（escalation client loop / consent 状态机 / activation codes / sync semantics）
- v0.6.0（感知平台 → 收口）；v0.2（Path A 初版）—— 详见 `docs/archive/`
