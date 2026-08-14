# ECHO Mind Implementation Status

> 本文件是长期自主演进的**唯一状态锚点**。每轮结束更新；禁止虚假完成状态。
> 文档权威顺序见 `docs/DOCUMENT_AUTHORITY.md`。
> 本文件描述**当前可交付 main**（git 受控状态），不描述 agent working tree。

## Current Era

**ERA 13.1 — ME APPLICATION LAYER 完成 ✅ → 下一轮 ERA 13.2（Domain Ports：Data package 成为 Adapter）**

## Distribution Closure Status（ERA 12.8 实测）

- ✅ **EchoRuntimeCoordinator 最终事实**：`android/.../runtime/EchoRuntimeCoordinator.kt`（145 行正式实现）此前被 `.gitignore` 裸 `runtime/` 规则排除在 git 之外——工作树存在、SOURCE_MANIFEST 记录、`git archive` 必漏包。已修复：`.gitignore` 改为根锚定 `/runtime/`，文件入库（`git ls-files` 可证）。
- ✅ SOURCE_MANIFEST 以 **git 受控文件集** 为唯一事实源（`update_release_metadata.collect_source_files`），不再扫描文件系统 → clean checkout 与 manifest 恒等。
- ✅ 确定性 Source Archive：`scripts/build_source_archive.py`（479 源文件；NFC 路径；ZIP 非 ASCII 条目置 UTF-8 标志 0x800；HEAD commit 时间戳；同 commit 字节级可复现）。
- ✅ Final Archive Verification Gate：`scripts/verify_source_archive.py`（解包 → 内嵌清单校验 → NFC/UTF-8 标志 → required sources → 双向 unexpected 检查）。zip 与 tar.gz 均 PASS。
- ✅ Distribution Integrity Test Suite：`scripts/test_source_archive.py`（10 用例：中文/emoji/空格/长路径 fixture、确定性、hash 篡改、意外文件、缺失 runtime、NFD、缺 UTF-8 标志负例）。
- ✅ root APK 绑定 Provenance：`release_apk_path/release_apk_sha256/unsigned_apk_sha256/signing_stage/signature_scheme`（`generate_provenance.py`，schema v2）。
- ✅ Artifact Manifest 只描述最终交付物（不再 hash build 目录 debug/androidTest 临时 APK）。
- ✅ Final Release Package：`scripts/build_final_package.py` + `scripts/verify_final_package.py`（§18 终态门禁：manifest 双向复核 + provenance 交叉绑定 + 包内 source archive 递归验证）。
- ✅ CI：`source-integrity.yml` 扩展为 archive build+verify+test suite；新增 `release-closure.yml`（§17 原子 release 流程，tag v* 触发，secrets 存在时 apksigner 签名）。
- ✅ 根目录 APK/idsig 从 git 移除（本地产物，`.gitignore` 泛化 `ECHO_Mind_v*.apk*`）——source archive 不再夹带 APK。

## Source Integrity

- ✅ `docs/architecture/SOURCE_INTEGRITY_REPORT.md`：核查结论 + 实测表（本 ERA 更新）
- ✅ EchoRuntimeCoordinator / EchoRuntimeHealth 五态（READY/STARTING/DEGRADED/PAUSED/UNAVAILABLE）已在 git 中，SourceIntegrityTest 断言防回归
- ✅ SourceIntegrityTest（Manifest 组件/Worker/包路径/引用解析/DAO/领域包/五态，7 断言）+ CI `source-integrity.yml`
- ✅ BuildConfig 内嵌 GIT_COMMIT / BUILD_TIMESTAMP / BUILD_VERSION（Me → About 可见）

## Build Status（本轮实测，clean checkout 复核 PASS）

- Android：**485 unit tests 全绿**（Journey 13 + Me §103 矩阵 7）；lintDebug / detekt PASS；assembleRelease PASS（Corretto-17 + SDK 36）；signed APK（v2,v3）生成并绑定 provenance
- backend：pytest **1070 passed + 1 skipped**；ruff 0 / mypy 0；alembic roundtrip / openapi 导出 / content-packs / claim scan / dynamic code / safety / contract drift / fault injection 全 PASS（release_preflight 全绿）
- Distribution：SOURCE_MANIFEST verify PASS；source archive（zip+tar.gz）构建+解包验证 PASS；test_source_archive 10/10；final release package §18 终态门禁 PASS；**clean checkout 全 Gate PASS**

## Completed

- v1 ERA 1-10 / v2 两轮 / ERA 12（批次 1+2+v3.1 收尾）——见 RELEASE_NOTES 与 ADR-001~024
- **ERA 12.6/12.7 Source Closure**：交付元数据重建、provenance、BuildConfig、SourceIntegrityTest、CI gate、文档归档
- **ERA 12.8 Distribution Closure**：runtime 入库、manifest=git、确定性归档、终态验证门禁、root APK provenance 绑定、release-closure CI
- **ERA 12.9 Document/Status Truth**：IMPLEMENTATION_STATUS 只描述可交付 main；架构清单 LOC 实测修正（888→314）；Source Reality Report CI drift gate（§21）
- **ERA 13 Journey Application Layer**：JourneyScreen（555→293 行）去编排；JourneyViewModel（168 行）+ JourneyUiState/JourneyEvent（§24/§25 全字段）+ JourneyRepository/JourneyPort（§26）；趋势七态纯逻辑迁 journey 包；ArchitectureBoundaryTest 新增 journey 不依赖 ui 断言；JourneyUiStateAssemblyTest（§102 矩阵）+ JourneyViewModelTest（4 用例）
- **ERA 13.1 Me Application Layer**：MeScreen（326→124 行）去编排；MeViewModel + DataAndSensingViewModel（§33）+ IntelligenceSettingsViewModel（§34）+ PresenceSettingsViewModel（§35）+ MemoryManagementViewModel（§36，含类别过滤）；纯函数装配器 + combine7/8 助手；MeStateAssemblyTest（§103 矩阵 7 用例）；SourceIntegrityTest/生成器支持泛型顶层函数扫描

## In Progress

- ERA 13.2 Domain Ports：ObservationEvidenceSource/PortraitHistorySource/EchoMemoryReader 等端口 + Intelligence 依赖清理 + Data package Adapter 化（本轮后立即执行）

## Blocked

- 无（Android 本机构建依赖 /tmp/echo-build JDK+SDK；缺失时由 CI android-ci/release-closure 兜底，如实标注不伪造）

## Legacy Remaining

| 模块 | 处置 | 状态 |
|---|---|---|
| `ui/SkillCardHost.kt` + `SkillActionRenderers.kt` | KEEP_AS_CONTENT（订阅内容，SKILLS_TO_ACTIONS.md 已裁决） | 最终决策 |
| backend 410 存根 | keep（机构历史只读） | 最终决策 |
| `releases/` v0.2 时代 bundle/tar/实施手册 | 历史交付物（git 受控但不进 SOURCE_MANIFEST/source archive） | 保留 |
| 根目录历史 APK（v0.8.0/v0.9.0） | 本地产物已移出 git（.gitignore），新交付走 release set | ✅ |

## Architecture Debt

- AppContainer 子容器为组合式分组（构造仍在 root）——ERA 13.3 抽 ports + 真拥有

## Security Debt

- SQLCipher passphrase KDF（Keystore 派生 + 固定 IV 域）→ ERA 17 用 HKDF/HMAC 标准方案迁移（需迁移测试，禁止直接换算法）
- GitHub Actions actions 仍 pin major tag（TODO(ERA17)：pin commit SHA）

## Performance Debt

- Journey 365d 全量 Compose 渲染风险（当前按 30 天聚合；未做 lazy 优化）→ ERA 16 处理
- EchoLifeField 帧渲染依赖 draw-phase 状态读取（当前可用；未做 profile）

## Release Integrity

- ✅ 版本单一事实源 v0.9.0（version_source/README/pyproject/versionName/manifest 一致，CI 断言）
- ✅ Git source = SOURCE_MANIFEST = distributed source ZIP = extracted verified source（同一 Gate，实测 PASS）
- ✅ signed APK ↔ BUILD_PROVENANCE ↔ RELEASE_ARTIFACT_MANIFEST ↔ final release package（DAG 无循环）
- ✅ release 必须 clean tree（`generate_provenance.py --require-clean`；CI 同 run 生成全部元数据）

## Next Highest-value Task

ERA 12.9（文档/状态真值）→ ERA 13 应用层收口批次 1：JourneyViewModel + JourneyUiState + Journey 领域访问层（JourneyScreen 去编排）→ 批次 2：MeViewModel + DataAndSensingViewModel。
