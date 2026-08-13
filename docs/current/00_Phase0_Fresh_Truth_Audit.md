# Phase 0 — Fresh Truth Audit（事实基线）

> 生成：2026-08-12 · 主理人 Qi 编排执行 · 仓库 `/Volumes/Extra/CodeProj/ECHO Mind`

## 1. 基线标识

| 项 | 值 |
|---|---|
| git commit SHA | `e096c06f81205fea8b5e6a5c7672cab2e4aa141b`（`feat: v0.7 Portrait Core`） |
| 分支 | main |
| Backend Python | 3.12.13（backend/.venv） |
| Android 版本 | versionName 0.6.0 / versionCode 3（尚未切 0.7.0） |
| Backend pyproject | 0.6.0 |
| DELIVERY_MANIFEST | v0.6.0 / Path A / 923 tests 硬编码（需重建） |

## 2. 环境可用性侦察

| 工具 | 状态 | 影响 |
|---|---|---|
| JDK / Java | **缺失**（无法定位 Java Runtime） | Android gradle 全链路 NOT RUN — ENVIRONMENT BLOCKED |
| Docker | 缺失 | PostgreSQL integration NOT RUN — ENVIRONMENT BLOCKED |
| psql | 缺失 | PostgreSQL CLI 不可用 |
| adb / emulator | 缺失 | Android instrumentation NOT RUN — ENVIRONMENT BLOCKED |
| Android SDK | 未检出 | 同上 |
| backend/.venv | 可用（pytest/alembic/ruff/mypy 已补齐） | 后端可完整执行 |

## 3. Backend 实际执行结果

| 命令 | 结果 |
|---|---|
| `python -m pytest -q`（基线，Phase 0 开始时） | **988 passed, 1 skipped**（41.7s） |
| `ruff check app tests` | **18 errors**（17 fixable；历史 F401 unused imports 为主） |
| `mypy app` | **50 errors in 14 files**（历史：portrait engine `**dict` 展开、dimensions Optional round 等） |
| `alembic upgrade head` | PASS（至 20260810_0007） |
| `alembic downgrade 20260810_0005 → upgrade head` | PASS（roundtrip OK，当前 head 20260810_0007） |
| `python scripts/export_openapi.py` | PASS（docs/openapi.json 111KB 重新导出，57 paths） |
| `python scripts/contract_drift_check.py` | PASS（53 路径，**但 manifest 为 v0.6 无 Portrait API → Phase 1.2 必须重建**） |
| `python scripts/fault_injection_check.py` | **16/18 PASS**（2 FAIL：`9 429 retry-after`、`verify-code 404/403`——均为 checker 引用过时代码模式的误报，Phase 8 修复） |
| `python scripts/claim_scan.py` | PASS |
| `python scripts/check_dynamic_code.py` | PASS |
| `python scripts/validate_content_packs.py` | PASS（4 packs） |
| `python scripts/safety_eval.py` | PASS（650 合成语料；confusion 矩阵正常） |
| PostgreSQL integration suite | **NOT RUN — ENVIRONMENT BLOCKED**（无 Docker/psql） |

## 4. Android 实际执行结果

| 命令 | 结果 |
|---|---|
| `./gradlew clean` | **NOT RUN — ENVIRONMENT BLOCKED**（无 JDK） |
| `./gradlew testDebugUnitTest` | **NOT RUN — ENVIRONMENT BLOCKED** |
| `./gradlew assembleDebug` | **NOT RUN — ENVIRONMENT BLOCKED** |
| `./gradlew lintDebug` | **NOT RUN — ENVIRONMENT BLOCKED** |
| Instrumentation tests | **0 个 androidTest 源码（无 androidTest 目录）** → Phase 7 必须补齐源码；执行仍需模拟器 |

## 5. 关键事实发现（供后续 Phase 使用）

- **Phase 1**：Backend Portrait GET endpoints 要求必填 `user_id` query param；Android `getTodayPortrait()`/`getPortraits()`/`getBaselineStatus()` 均**不发送** `user_id` → 必然 422。`rebuildPortrait()` 发送 `{}` 但后端要求 `{"user_id":...}` → 422。→ 后端已实现 `/v1/me/*` authenticated 变体（工程师第一批），Android 侧待改。
- **Phase 1.1**：Android `DailyPortraitDto.dimensions: Map<String,String>` + `optString` 吞嵌套对象（后端为 `{"RHYTHM":{"value","metric","z"}}`）；`BaselineStatusDto.bucketUsage: Map<String,Any>?` 但后端为 `str`、`todayCoverage: Map<String,Any>?` 但后端为 `float` → 类型不匹配。
- **Phase 3.1**：`AppContainer.database` = `runCatching{loadLibs}.getOrElse{普通Room}` → 生产 SQLCipher 失败静默回退明文。违反 fail-closed。
- **Phase 3.2**：`FieldCipher` JVM fallback（JCEKS 内存密钥）与 Production AndroidKeyStore 在同一类中自动切换，未显式分离。
- **Phase 3.3**：`PortraitDao.queryLatest()`/`queryByDateRange(from,to)` 无 userId 参数；LocalRepository 虽有 `takeIf { userId == }` 应用层过滤（today 路径），但 queryByDateRange 缓存兜底路径无 userId 过滤 → 跨用户泄漏风险。
- **Phase 4**：SensingEventHub 已有 snapshot/clearConsumed ACK 语义，但 `FeatureExtractor.extractFromHub` 在 flush 时**重新读 live hub**（快照取了却没用）；accel/gyro buffer 为 `FloatArray` **无时间戳**（5 分钟窗口归属不可精确）；MAX_BUFFER_SIZE=1024 在 SENSOR_DELAY_NORMAL（≈200ms/样本）下 5 分钟约 1500 样本 → **前半段会被 trim 丢弃**；ScreenCollector 无 carry-over state（窗口开始前已 ON 的屏幕无法计入）；AppActivity `topAppDurationMs = windowEnd - lastEvent.timestamp` 非真实 top-app duration。
- **Phase 5.1**：aggregate coverage = `len(features)/288`，非 unique window keys；duplicate/retry 会重复计数。
- **Phase 5.2**：`EXPECTED_WINDOW_COUNT=288` 固定，无 DST 动态窗口数。
- **Phase 5.3**：movement_index 注释称"幅度均值（vector[7]）"但 vector[7] 实为 magnitude_std → 命名/实现不一致。
- **Phase 5.4**：rhythm_regularity = 活跃小时数/24（active hour spread 语义却命名 regularity）。
- **Phase 5.5**：active_start/end_minute 使用普通 median/MAD（非 circular；23:55 vs 00:05 会被算成相差巨大）；DailyPortrait 无 baseline_snapshot_digest。
- **Phase 5.6**：`active_start_minute==None → RHYTHM=IRREGULAR`（missing 当 irregular，且计入 stability diff_count）。
- **Phase 5.7**：SCREEN_PATTERN 合并 amount+timing 为单一维度。
- **Phase 5.8**：`_z`/`_classify` 只有 MAD+epsilon，无 minimum meaningful absolute delta；explain 百分比 `max(med,1e-9)` 在 near-zero baseline 下产生 +900% 无意义值。
- **Phase 6**：Onboarding 五步 `WELCOME→CONSENTS→L0→EMERGENCY→DONE`，L0 阻断普通 Portrait onboarding；文案"心理健康记录、筛查提示和审核练习工具"；"心理记录与量表信息（核心必选）"。
- **Phase 6.1**：`passiveSensingGatePasses` 要求 POST_NOTIFICATIONS + notificationAccess + usageAccess **全部具备**才启动 sensing → 拒绝 Notification Listener 即整个 sensing 停止。
- **Phase 6.2**：narrative 已确定性，headline 含"安静/活跃/稳定"（Psychology Review 需标注 ALLOW/REWRITE/BLOCK）。
- **Phase 6.3**：facts 使用 `movement_index = 0.47` 类原始值 + 无界百分比。
- **Phase 6.4**：Android 缓存键用 `todayLocalDateString(now, ZoneId.systemDefault())`（端侧日期），未用服务器 local_date/timezone_used。
- **Phase 6.5**：headline 用 `AssistChip(onClick={})` 假交互组件；Trend 仍依赖 `fetchProfile()`（legacy UserProfile）。
- **Phase 6.6**：feedback 仅本地 SharedPreferences 记录，未走 Outbox 同步。
- **Phase 7**：无 androidTest；Room schemas 仅 2.json/6.json（无 7.json）。
- **Phase 8**：`release_preflight.sh` 引用不存在的 `QuestionnaireScorer.kt` 且用 kotlinc 编译历史 domain 文件；`update_release_metadata.py` 硬编码 0.6.0/874/Path A；`package_release.sh` 硬编码 VERSION=0.2.0；DELIVERY_MANIFEST 硬编码 923。

## 6. 结论

- 后端基线：**可运行但存在历史 lint/type 债务（ruff 18 / mypy 50）+ 2 个 checker 误报**；全部测试通过。
- Android 基线：**本环境无法构建/测试**（无 JDK/SDK/emulator），代码质量只能静态审计。
- 后续所有 Phase 将基于上述事实推进；环境阻塞项均如实标记，不伪装 PASS。
