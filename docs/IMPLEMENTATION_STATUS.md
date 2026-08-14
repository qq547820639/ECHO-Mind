# ECHO Mind Implementation Status

> 本文件是长期自主演进的**唯一状态锚点**。每轮结束更新；禁止虚假完成状态。
> 文档权威顺序见 `docs/DOCUMENT_AUTHORITY.md`。
> 本文件描述**当前可交付 main**（git 受控状态），不描述 agent working tree。

## Current Era

**ERA 15.5 — MEMORY MATURITY 第一轮完成 ✅ → 下一轮 ERA 16 Journey Long-term Memory（§81-§87）**

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

- Android：**518 unit tests 全绿**（ERA 15.5 第一轮 +8 memory maturity）；lintDebug / detekt / assembleRelease PASS（app + 九模块聚合；Corretto-17 + SDK 36）；signed APK（v2,v3）生成并绑定 provenance
- backend：pytest **1070 passed + 1 skipped**；ruff 0 / mypy 0；alembic roundtrip / openapi 导出 / content-packs / claim scan / dynamic code / safety / contract drift / fault injection 全 PASS（release_preflight 全绿）
- Distribution：SOURCE_MANIFEST verify PASS；source archive（zip+tar.gz）构建+解包验证 PASS；test_source_archive 10/10；final release package §18 终态门禁 PASS；**clean checkout 全 Gate PASS**

## Completed

- v1 ERA 1-10 / v2 两轮 / ERA 12（批次 1+2+v3.1 收尾）——见 RELEASE_NOTES 与 ADR-001~024
- **ERA 12.6/12.7 Source Closure**：交付元数据重建、provenance、BuildConfig、SourceIntegrityTest、CI gate、文档归档
- **ERA 12.8 Distribution Closure**：runtime 入库、manifest=git、确定性归档、终态验证门禁、root APK provenance 绑定、release-closure CI
- **ERA 12.9 Document/Status Truth**：IMPLEMENTATION_STATUS 只描述可交付 main；架构清单 LOC 实测修正（888→314）；Source Reality Report CI drift gate（§21）
- **ERA 13 Journey Application Layer**：JourneyScreen（555→293 行）去编排；JourneyViewModel（168 行）+ JourneyUiState/JourneyEvent（§24/§25 全字段）+ JourneyRepository/JourneyPort（§26）；趋势七态纯逻辑迁 journey 包；ArchitectureBoundaryTest 新增 journey 不依赖 ui 断言；JourneyUiStateAssemblyTest（§102 矩阵）+ JourneyViewModelTest（4 用例）
- **ERA 13.1 Me Application Layer**：MeScreen（326→124 行）去编排；MeViewModel + 四个子 ViewModel（§33-§36）；纯函数装配器 + combine7/8；MeStateAssemblyTest（§103 矩阵）
- **ERA 13.2 Domain Ports**：ports 包（Observation/Memory/Presence 端口族）；五个 data 类 Adapter 化；EchoContextRetriever/EchoCorrectionService 只依赖端口；Ground Truth 断环（SyncEnqueue）；真实依赖图生成器（§48，11 域 53 边无循环，CI drift gate）
- **ERA 13.3 Real DI Ownership**：AppContainer 缩减为 composition root（六容器自持构造 + 跨域编排 + Transient 工厂）；DI_OWNERSHIP.md 生命周期所有权（§45）；DI 裁决继续 structured manual DI（§47）；ArchitectureBoundaryTest +1（Root 禁止直接构造 17 类领域对象）
- **ERA 13.5 第一批模块**：:feature:actions + :core:security（零依赖叶子）；扫描器四件套多根化；detekt 共享配置
- **ERA 13.5 第二批模块**：:core:model（4 文件）；SensingCapability/CapabilityState 纯枚举自 sensing 迁入 model（Ground Truth 内依赖消除）；模块 internal API 不可见触发 22 处声明 public 化（编译器强制边界首次生效）
- **ERA 13.5 第三批模块**：:feature:memory（EchoMemory 领域模型，零项目依赖）；EchoCorrectionService 留 :app（应用层 ports 消费者，split-package 语义）
- **ERA 13.5 第四批模块**：:feature:observation（16 文件）；平台组件留 :app；MicDerivedFeatureSource 契约下沉；Ground Truth API 公开化；Manifest 组件检查多根化
- **ERA 13.5 第五批模块**（依赖序一次拆解 ×3）：:feature:presence（5 文件 + compose；EchoStateStore 抽出留 :app）+ :core:ports（契约层）+ :feature:intelligence（13 文件，无 ui/data 依赖）
- **ERA 13.5 收官**：:feature:journey——**9/9 模块全部完成**；§51 五条边界全部编译器物理强制
- **ERA 14 第一轮**：IdentityGenome 七维 + identitySeed（§54 合规）；LifeSeason 真实计算（§57 中性词表）；DailyComposition/MomentState 填充；smoothPresenceState 平滑；rhythmDelta placeholder 移除；EchoVisualMapper 冻结（§62）；EchoIdentityTest 7 用例
- **ERA 14 第二轮**：WallpaperRenderController 纯状态机（§65 不可见零渲染硬指标）+ PRESENCE_BENCHMARKS.md 测量契约 + ECHO_MOTION_LANGUAGE.md 冻结（§66）
- **ERA 15 第一轮**：QuestionClassifier 六分类；ContextRanker 排序；Context Budget 三重上限；EchoEvidence schema；GroundingValidator + EchoAnswer
- **ERA 15.5 第一轮**：rankMemories 检索排序（§75）；生命周期确认 + derivePatterns 入 Worker（§76）；Derived Pattern Memory（§77，幂等 upsert）；特殊时期用户入口（§78/§79）；What ECHO Knows 七分类展示（§80）；MemoryMaturityTest 8 用例

## In Progress

- ERA 16 Journey Long-term Memory：Canonical Daily State（§83）+ 历史重建（§84）+ Visual Memory River 语义（§85）+ Year View season 聚合（§86）+ Life Season × Journey 解释（§87）

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

- 无（ERA 13.3/13.5 已解决：子容器自持构造 + 物理模块化完成）

## Security Debt

- SQLCipher passphrase KDF（Keystore 派生 + 固定 IV 域）→ ERA 17 用 HKDF/HMAC 标准方案迁移（需迁移测试，禁止直接换算法）
- GitHub Actions actions 仍 pin major tag（TODO(ERA17)：pin commit SHA）

## Performance Debt

- Journey 365d 全量 Compose 渲染风险（当前按 30 天聚合；未做 lazy 优化）→ ERA 16 处理
- Wallpaper 设备实测数字（CPU/frame/memory/wakeups/battery）由 CI connected-test/真机矩阵执行（基准已冻结）
- EchoLifeField 帧渲染依赖 draw-phase 状态读取（当前可用；未做 profile）

## Release Integrity

- ✅ 版本单一事实源 v0.9.0（version_source/README/pyproject/versionName/manifest 一致，CI 断言）
- ✅ Git source = SOURCE_MANIFEST = distributed source ZIP = extracted verified source（同一 Gate，实测 PASS）
- ✅ signed APK ↔ BUILD_PROVENANCE ↔ RELEASE_ARTIFACT_MANIFEST ↔ final release package（DAG 无循环）
- ✅ release 必须 clean tree（`generate_provenance.py --require-clean`；CI 同 run 生成全部元数据）

## Next Highest-value Task

ERA 12.9（文档/状态真值）→ ERA 13 应用层收口批次 1：JourneyViewModel + JourneyUiState + Journey 领域访问层（JourneyScreen 去编排）→ 批次 2：MeViewModel + DataAndSensingViewModel。
