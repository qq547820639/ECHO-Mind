# ECHO Mind Implementation Status

> 本文件是长期自主演进的**唯一状态锚点**。每轮结束更新；禁止虚假完成状态。
> 文档权威顺序见 `docs/DOCUMENT_AUTHORITY.md`。
> 本文件描述**当前可交付 main**（git 受控状态），不描述 agent working tree。

## Current Era

**Me 数据权利检查台第 1 轮完成 ✅（ADR-062 选定用户信任层；五域存储足迹落地——DataFootprint + footprintSummary + 记忆软删行计数 + LocalModeTest 五域契约）→ 下一轮：检查台第 2 轮（DataAndSensing 状态接入 + UI 检查台卡片 + 导出/删除动作归位）**

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

- Android：**806 unit tests 全绿**（ERA 40-48 契约增量：Provider 私网边界 / 维护序列锚点 +3 / outbox 速率槽位 +3 等）；lintDebug / detekt 27 规则 / assembleRelease PASS（lint 4 条 error 级安全规则固化：UnspecifiedImmutableFlag / UnspecifiedRegisterReceiverFlag / SetJavaScriptEnabled / RtlHardcoded；app + 九模块聚合，Gradle dependency locking 生效；Corretto-17 + SDK 36）；signed APK（v2,v3）生成并绑定 provenance（内嵌 commit == provenance.git_commit，绑定测试强制）；affective_eval 9 用例 + release set 6/6 + distribution 10/10
- backend：pytest **1076 passed + 1 skipped**（ERA 45 激活码 TTL 毫秒边界契约 +1）；ruff 0 / **mypy strict 0**（ERA 34：`strict = true` + `ignore_missing_imports = false`——探测显示全部依赖自带类型零豁免，89 处裸 dict/list 精确化：dimensions stats `dict[str, float]`、baseline_metrics `dict[str, dict[str, float]]`、安全特征 `list[Any]`，其余 `dict[str, Any]`；新增代码裸泛型/未标注/未使用 ignore = CI 红）；alembic roundtrip / openapi 导出 / content-packs / claim scan / dynamic code / safety / contract drift / fault injection 18/18 全 PASS（release_preflight 真全绿）
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
- **ERA 16 第一轮**：Canonical Daily State（§83，Room v10 `journey_canonical_days`，只存参数不存 bitmap，Worker+Journey 双写点幂等）；历史重建（§84，Canonical 优先 → 画像 fallback → 不编造，SelectDay 交互）；Visual Memory River（§85，平稳/密集/漂移/特殊/转变分类+合并）；Year View（§86，四季聚合+转变+上下文时期+身份演化）；Life Season × Journey 解释（§87，禁词测试强制）；五尺度六层装配（§81/§82）；上下文例外时间定位（§78 带日期）；新增 57 用例
- **ERA 17 第一轮**：§88 审计（fixed IV+AES-GCM+SHA-256 非标准 KDF 确认 + 字段/DB 共用 alias 确认）；§89 HKDF-SHA256 标准 KDF（RFC 5869 官方向量测试）+ 受保护随机秘密（Keystore 随机 IV 包装）；§90 密钥分离（field alias / db_secret alias + 独立 HKDF context）；§91 旧库迁移链（DatabaseOpenOrchestrator：derive old → open → rotate → rekey → verify → retire；失败自愈重试；已迁移 fail-closed）；§92 crypto 测试矩阵（23 用例 + 真机 instrumentation 3 用例）
- **ERA 18 第一轮**：§93 Actions pinning（64 uses → immutable SHA + verify_workflow_pins.py CI 门禁）；§94 Release Set 完整性测试（test_release_set.py 5 用例入 release-closure）；§95 应用内构建信息（BUILD_VERSION 派生自 versionName、BUILD_TIMESTAMP 默认提交时间、BuildInfoTest）；§96 clean-room 复现（wrapper distributionSha256Sum + Gradle dependency locking 10 lockfiles + CLEAN_ROOM_REPRODUCTION.md 复现步骤与可复现性边界）
- **ERA 18 收尾轮**：APK↔provenance 绑定闭环（test_release_set 增 APK dex 内嵌 commit == provenance.git_commit 断言，6/6）；§109 Memory Long History（Room v11 echo_memories 复合索引 + 迁移测试）；JVM 性能防退化门禁（PerformanceBaselineTest 4 预算 + PERFORMANCE_BASELINES.md）
- **Affective 预备轮**：§8 离线评估框架（scripts/affective_eval.py：grounding/overreach/calibration 三指标 + 阈值 gate + 本地回放/真 Provider 双模式；8 场景合成验证集禁标签词；test_affective_eval.py 9 用例）；AffectiveContractFreezeTest（全 main 源码扫描：非空 AffectiveState 构造/非 null 赋值 = 发布阻断——affectiveState 恒 null 由测试强制）；docs/intelligence/AI_EVAL.md 复跑协议 + 激活前置清单
- **§96 收尾轮**：backend 依赖锁定（backend/uv.lock：51 包精确版本 + 完整闭包；`uv lock --check` 接入 backend-ci + release_preflight 漂移门禁；venv 已 uv sync --extra dev 对齐——backend 实测 1070 passed + 1 skipped / ruff 0 / mypy 0）；SBOM 升级（backend 段读 uv.lock 精确版本；时间戳锚定 version_source.json sbom_created_utc——版本冻结、commit 无关；clean-room 门禁验证字节级确定性，含 chore 提交后重生成）
- **依赖审计本地化轮**：scripts/audit_dependencies.py（uv.lock → uv export → pip-audit OSV + osv-scanner 本地可选/CI 强制；入 release_preflight + security-ci 同构）；首跑命中并修复 cryptography 漏洞链（46.0.7 → 48.0.1 → 49.0.0 三级串联 → pin `>=50,<51`，uv.lock 重解析 + venv 重同步 + 全量测试复核）；backend 6 用例日期边界 flaky 修复（weekend 桶播种不足 + test_messages 固定日期过期——e2e `_seed_history` 双桶各 ≥7 日 + messages 实时今天；与 cryptography 升级无关已交叉验证）
- **收尾轮**：backend 测试时长优化（`_seed_history` 每日窗口 120→80，覆盖 0.28 安全边际；全量 2:52 → **1:52**，e2e+messages 17 用例 2:18 → 1:36，断言语义不变）；osv-scanner 本地化尝试（GitHub release-assets 网络超时——本地如实 NOT RUN 豁免，security-ci 强制执行不变）
- **性能基线补齐轮**：PerformanceBaselineTest 增 Presence 装配全链（200 次 <2s）与 Context 编译（500 证据 ×20 次 <2s，含禁止数据剔除路径）——PART PERFORMANCE 的 JVM 可代表项全部有防退化预算；PERFORMANCE_BASELINES.md 六行预算表
- **CI 锁定执行轮**：backend-ci/release-closure 全部 backend 步骤改 uv --frozen 锁定安装 + uv run 执行（安装即漂移门禁，替代 uv lock --check；本地同路径预演通过）；verify_workflow_pins.py 增 YAML 结构校验；§22 文档真值（ADR 计数 047、Android 单测 617 与 main 对齐）
- **Runtime 六态收尾轮**：§101 computeEchoRuntimeHealth 纯函数化（sensing 六态 → 组件聚合 / presence / intelligence / memory）+ EchoRuntimeHealthTest 5 用例；PerformanceBaselineTest 增首帧计算 1000 次 <2s（七行预算表）；dependabot backend 生态 pip → uv（升级 PR 自动更新 pyproject + uv.lock）；osv-scanner 第五次下载仍被网络阻断（exit 16，CI 强制）
- **安全覆盖补缺轮**：security-ci CodeQL 扩 java-kotlin（manual build + Android SDK + compileDebugKotlin 提取，P2 落地；超时 60min）；release-closure 包内门禁补 pip install pytest（修潜伏失败）并追加 affective 评估 9 用例；README 计数 623 对齐
- **backend 类型收紧轮**：mypy `disallow_untyped_defs=true`（84 → 0，22 文件全标注：路由真实契约类型 + 410 路由 -> None + 辅助参数）；过程中契约测试抓获 Pydantic 响应校验 bool→int 强转（list_escalations chain_broken/delivery_confirmed 0/1 化）——联合并入 bool 修复（教训：路由注解改变序列化语义，须跑契约测试）；backend 1070 passed + 1 skipped / ruff 0 / mypy 0
- **Android SAST 扩围轮**：detekt 5 → 14 规则（coroutines 2 + potential-bugs 5 + style 4 候选规则探测轮全模块零告警后固化，maxIssues=0 不变；RedundantVisibilityModifier 因 detekt 1.23 移除不启用）——未来新增代码命中即 CI 红
- **Android lint 硬门禁轮**：`warningsAsErrors = true` + lint.xml（97 告警全部处置：13 项真实修复含 ApplySharedPref/ObsoleteSdkInt/mipmap-anydpi 归一/备份双规则全域排除（隐私契约）/14 条真未用 string 删除/Autoboxing 3 处/版本目录 2 处；4 类豁免内联理由 UseKtx/dependabot 升级类/Aligned16KB SQLCipher 上游）——未来任何新 lint 告警 = CI 红
- **Compose UI smoke tests 轮**：UI 层首个真渲染测试基建——compose-ui-test-junit4 + ui-test-manifest（BOM 管理版本）入版本目录 + :app；JourneyScreen 状态提升为薄包装 + `JourneyScreenContent`（state-in / event-out，纯 JourneyUiState 渲染，架构 §27 不变）；JourneyScreenSmokeTest 9 用例（免责声明/六态分支/尺度选择事件/单日历史重建事件/Evidence 折叠事件——含 merged semantics 与 performScrollTo 视口外点击两项真实发现）+ SupportSectionSmokeTest 3 用例（回调/空清单/状态行/未知回退）；app gradle.lockfile 重写同步（espresso 传递闭包）；backend/scripts/verify_audit.py E401 顺手修复（`ruff check .` 全树 0）——UI 层从此有渲染防回归基线
- **MeScreen 状态提升 + Me smoke tests 轮**：Me 六分区全部 state-in / event-out——`MeScreenContent`（九槽位组合矩阵：crisis/subscription/support/data/presence/intelligence/memory/about）+ `DataAndSensingContent`（平台权限编排留在调用侧：onToggleSensing 通知预检回调/onLaunchMicPermission/恢复深链回调）+ `PresenceSettingsContent` + `IntelligenceSettingsContent` + `WhatEchoKnowsContent` + `SubscriptionContent`；**§31 收口**：新增 `SubscriptionViewModel`（旧 SubscriptionSection 直接在 Composable 内调 onboardingRepository/featureFlagRepository/SyncWorker——依赖全部构造注入，7 用例单测无 AppContainer）；新增 51 渲染/业务用例（+7 根组合矩阵）——Me 世界从此有渲染防回归基线
- **backend mypy strict 轮**：`ignore_missing_imports = true → false` 探测——全部依赖自带类型，零豁免（passlib 豁免为死配置已删）；`mypy --strict` 89 处误差（86 裸 dict + 1 裸 list + 2 no-any-return）脚本化行级修复：dimensions 桶统计精确为 `dict[str, float]`（顺带消除 2 处 Any 回传）、baseline_metrics 精确为 `dict[str, dict[str, float]]`、安全鸭子特征 `list[Any]`、其余路由/服务 JSON 载荷 `dict[str, Any]`（28 文件补 Any import）；配置冻结为 `strict = true`——新增代码裸泛型 / 未标注函数 / 未使用 ignore = CI 红；pytest 1070 全绿证无行为漂移
- **ECHO 世界 smoke tests 轮**：ECHO 世界首个 UI 渲染基线（+30 用例）——EchoWhyLayerSmokeTest 8（§9 Progressive Explanation 三层全链路：一句话/AI 依据行/确定性隐藏依据/展开·收起事实卡/空事实占位/Journey 入口回调）、EchoStatusOverlaySmokeTest 6（感知六态可信呈现：非 ACTIVE 才可见 + 初次 AI 提示卡三态）、EchoConversationLayerSmokeTest 7（问答渲染/依据双清单「参考了·没有使用」/像我即时记录/不太像→原因 chips 强制/非 IDLE 禁用态/发送清空事件）、EchoPortraitStatesSmokeTest 9（summary 空态/基线进度文案/覆盖度百分比与隐藏/解锁仪式一次性 consume 语义）——ECHO 三大世界 UI 渲染基线自此齐备（Journey 9 + Me 51 + ECHO 30）
- **EchoSceneScreen 状态提升轮**：ECHO 世界根页面六流聚合 + 三槽位——`EchoSceneContent`（state/navigation/coreActions/feedbackActions 四分组 + visualSurface/actionLayer/actionOverlay 三槽位；分组 data class 保持 detekt 阈值）；容器依赖全部留在薄包装（preferences→aiPromptDismissed/awakenedAtEpochMs 状态输入，skillRepository/coordinator→行动槽位）；`SeedPortraitBlock` 去除 AppPreferences 依赖（只收 awakenedAtEpochMs）；`PortraitFeedbackContent` 状态化（反馈查询/四个回调注入，不再持 ViewModel）；行动覆盖层槽位化（真实 EchoActionOverlay 无限帧动画不适配 Robolectric——由构造隔离）；EchoSceneContentSmokeTest 20 用例九态矩阵——**三大世界根页面（Echo/Journey/Me）至此全部 state-in/event-out 可渲染测试**
- **detekt 扩围轮**：14 → 27 规则（探测→清除→固化）：style +4（UnusedImports/MayBeConst/UnnecessaryParentheses，另 CollapsibleIf 不存在于 1.23 淘汰）、potential-bugs +4（CastToNullableType/DontDowncastCollectionTypes/LateinitUsage/UnusedUnaryOperator，另 MissingWhenCase/RedundantElseInWhen 已由编译器默认检查淘汰）、coroutines +1（SleepInsteadOfDelay）、performance +2（ForEachOnRange/UnnecessaryTemporaryInstantiation）；探测清除 60+ 处（53 未用 import 行删除 + 18 处多余括号按 detekt 建议替换，跨 9 模块迭代三轮）；LateinitUsage 主源集强制 + 测试源集豁免（JUnit setUp 惯例，33 处全在测试）——新增代码未用 import/多余括号/lateinit = CI 红
- **ECHO 组件基线收官轮**：`EchoVisualSurface` 偏好输入纯函数化（`echoVisualSurfaceConfig` 映射矩阵 5 用例——未知动效等级回退 DEFAULT/减少动画→REDUCED_MOTION；组件只收 config，不再持 AppPreferences；EchoLifeField 无限帧动画由构造隔离）；`EchoActionLayer` 槽位化（`EchoActionLayerContent` 纯内容 + 订阅能力槽位，5 用例含 L2 建议门禁与「什么也不做」折叠语义）；`OnboardingStepContent` 三步渲染矩阵（state/actions 分组 11 回调，7 用例：契约句锚点/18+与边界门禁/五同意门禁/能力行系统真实状态/授权·跳过回调/苏醒 CTA·abstain/紧急入口常驻——编排层保留权限 launcher 与服务启动）——UI 组件可测面收官（含动画宿主类组件的纯映射测试模式）
- **lint 复核 + 性能基准扩项轮**：lint.xml 豁免逐条复核——UseKtx 仍命中 50 处（含刻意 commit() 同步写路径，KTX edit{} 默认 apply 语义不同，转换有行为风险）豁免保留并更新理由；探测固化 4 条 error 级安全/RTL 规则（UnspecifiedImmutableFlag PendingIntent 可变标志 / UnspecifiedRegisterReceiverFlag / SetJavaScriptEnabled / RtlHardcoded）——全仓库零命中直接冻结；性能预算 7 → 9 行（Journey 365 天完整 UI 状态装配 assembleJourneyUiState 全链 <2000ms、Derived Pattern 1000 条派生 <1000ms，均取最优 3 次防退化语义不变）
- **§22 文档真值 + Provider 传输安全复核轮**：ADR 补录 055（UI 状态提升 + 槽位组合 smoke test 模式——三大世界根页面收敛与动画宿主构造隔离决策）/ 056（backend mypy strict 冻结：ignore_missing_imports=false + 89 处裸泛型精确化）/ 057（detekt 27 规则 + lint 安全规则 + 性能预算 9 行固化）；docs/current 事实表对齐（ADR 001~057 / 九行预算表 / ERA 30-39 质量门禁深化）；**Provider 传输安全复核**：TLS 路径确认平台默认证书校验（无自定义 TrustManager/hostname 绕过）+ **发现并修复公网 172.x 明文洞**——旧 `http://172.` 前缀误放行公网段（172.217.x）致 API Key 明文出网，改按 RFC 1918 精确判定（172.16/12），+2 用例锁定边界（内网放行/公网拒绝）
- **CI 工作流参数复核轮**：五 workflow 逐条对照本地门禁——backend-ci / security-ci Python **3.13 → 3.12**（对齐 CLEAN_ROOM_REPRODUCTION.md 与 uv.lock 解析环境，消除 frozen 解析漂移风险）；security-ci 补 sdkmanager 显式安装 platform 36 + build-tools 36.0.0（与 android-ci 同构，不依赖 gradle 自动下载的隐性授权路径）；release-closure 终态门禁补 `test_source_archive.py`（§107 分布完整性负例套件进入同一 atomic run——此前仅 source-integrity 覆盖且 tag push 不触发）；verify_workflow_pins 66 uses 全 SHA 门禁复核 PASS
- **backend 安全复核轮**：审计链（verify_audit_chain 哈希链 canonical payload + previous_event_hash 双向校验）、token 权限边界（JWT HS256 + iss/aud/iat/exp 60 分钟 TTL + 角色白名单 + step-up + tenant 隔离查询 + require_write_role/订阅/心理内容角色门禁）、激活码防爆破（SHA-256 pepper 哈希存储 / TTL / 一次性原子消费 / code·IP·device 三维 rate limit）逐条复核 PASS；**落地缺口修复**：支持请求创建此前无限流——新增每用户每小时 20 条上限（幂等重放不计入窗口；**红色信号触发（危机/主动求助）豁免——429 永不阻断危机信号**；限流拒绝写审计链），4 契约用例（20 条放行·21 条 429 / 红色信号豁免 / 幂等重放不计数 / 限流审计可检索）
- **性能设备锚点轮**：`EchoSceneFrameDeviceBenchmarkInstrumentedTest`（androidTest）入 CI connected-test API 34/36 矩阵——三锚点：§65 硬指标设备烟测（不可见 renderActive=false / destroy 永久停止断言）、首帧计算设备锚点（computeEchoSceneFrame ×1000 于真实 ART 运行时，模拟器预算 <10000ms）、Journey 365 装配设备锚点（assembleJourneyUiState 全链 ×3 最优，<10000ms）；实测数字 info 日志逐次记录（PRESENCE_BENCHMARKS §2「逐次记录」落地）——真机严格基线（CPU/GPU/wakeups/battery adb 采样）仍按契约不伪造，由部署侧执行
- **文档真值 + security 预演轮**：文档权威双文件 ADR 计数对齐（README_AUTHORITY/DOCUMENT_AUTHORITY 的「ADR-001~047+」→ 057）；docs/current 时代描述更新（ERA 30-43 质量门禁深化：detekt 27 规则 / lint 安全规则 / mypy strict / UI smoke tests / CI 复核 / 设备锚点）；**订阅到期毫秒边界契约补测**（expires_at == now → 无效，锁定 `expires > reference` 语义）；**security-ci 本地预演**：`audit_dependencies.py`（uv.lock → pip-audit）CI 同构执行「No known vulnerabilities found」；trivy/trufflehog/osv-scanner 本地未安装如实记录（security-ci 强制执行不变）
- **迁移链收官轮**：Room 迁移链 instrumentation 覆盖补齐——此前 2→8 全链 + 7→8 单步，v9（echo_memories）/v10（journey_canonical_days）/v11（复合索引）迁移无测试；新增 8→11 全链（v10 表+索引可写读 / v11 复合索引存在 / 旧特征行保留锚）+ 10→11 索引单步（PRAGMA index_list 校验）；激活码 TTL 毫秒边界契约（expires_at == now → 拒绝，锁定 `_is_expired` 的 <= 语义）——迁移链 2→11 自此无断档，android-ci 注释同步
- **数据库维护 Worker 复核轮**：§76 全语义逐条对照——decay（memoryDecayScore 检索相关性衰减，MemoryMaturityTest.decayScoreDecreasesOverTime）、expiry（shouldForget + purgeExpired 软删，expiryBasedOnRetention）、reinforce（confirm 提升 importance + 刷新确认时间）、derivePatterns（幂等哈希 id upsert）均有实现与测试；Worker 注册（echo-presence-refresh 15min KEEP unique）+ onboarding 门禁 + 四步顺序 + fail-closed 复核 PASS；**落地测试锚点**：抽取 `PresenceMaintenanceScript`（纯 JVM 可测维护序列），3 用例锁定固定顺序 / 中途异常不阻断后续步骤 / 全步异常仍完成（worker 恒 success 语义）
- **数据权利导出覆盖复核轮**：导出/删除域对照发现**真实缺口**——deleteLocalData 覆盖五域（特征/画像/同意/记忆/Canonical 快照）而 exportLocalData 仅三域，用户无法导出自己的记忆与 Journey 视觉快照；修复：MemoryDao 增 `allByUser`（导出专用全量查询，含软删记录与 deleted 标记——完整记录不留盲区；UI 热路径仍走 LIMIT 截断不变）+ JourneyCanonicalDao.range 纳入，导出 JSON 增 `memories` / `journey_canonical_days` 两节；LocalModeTest 扩域断言五域导出内容与删除后五域清零；云端路径复核（backend DSR 矩阵依法保留分类/幂等重放）与 device-first 分工一致（后端不持有设备记忆）
- **SyncWorker outbox 生命周期复核轮**：五维矩阵逐条对照——重试退避（批内 429 Retry-After 聚合取最小 + clamp[1,MAX] + 无 429 时清除持久化值回落 30s 指数退避，backoffDelayConsumesRetryAfterAndFallsBackToDefault）、死信（410 非 deprecated 永久 DEAD_LETTER / 毒丸 max attempts 保护 / 412-422 超限）、幂等重放（2xx/409 DELETE、410+deprecated DELETE_AND_MIGRATE）、本地模式静默（Outbox.enqueue 静默不积压）、auth 暂停不阻塞队列（SyncWorkerAuthPauseTest）全部 PASS；**补测缺口**：derived_feature 上传速率槽位（acquireDerivedFeatureSlot 滑动 60s 窗口 / 上限 20）此前无测试——internal 化 + 3 用例（新窗口精确 20 槽 / 计数持久化 / 窗口过期重置计数，无 sleep 全确定性）
- **云端撤回证据链复核轮**：DSR delete 全链复核——11 类派生/主动内容删除、5 类依法保留（consents 同意证据链 / risk_signals / escalations 危机处置 + 用户去标识（external_ref→dsr_哈希 / city 清空 / timezone UTC）/ audit_events 哈希链 / dsr 记录）逐类复核 PASS；**补契约**：回执-证据链绑定测试（返回回执 per_category 与 dsr.complete 审计事件 metadata 逐类一致；幂等重放结果相等已在册）——用户拿到的回执就是审计链里的事实；**Room 12 预研结论**：当前全部领域已有表覆盖（v11 为最新），无新表需求——不预建 Room 12，维持「需要才迁移」纪律
- **文档终检 + 下阶段选型轮**：全仓文档巡检（README/docs/current/ADRS/性能与安全文档）——测试计数（1077/763）、instrumentation 4 组、ADR 计数、版本 0.9.0/versionCode 6、Room v11、detekt 27 全部零陈旧（ADR 内历史条目保留其时代真值）；**覆盖率实测更新**：93.8% → **94%**（3642 行 / 205 未覆盖，全量 pytest --cov 实测）；**ADR-058 下阶段选型**：质量/安全/数据权利复核阶段收官，下一长阶段 = ERA 14 §52/§61 Identity/LifeSeason 真值审计深化（不选 Provider 扩展/Journey Year 重做）
- **ERA 14 §52 真值审计第 1 轮**：全链路逐段验证——装配（deriveIdentityGenome：SecureRandom 一次性持久化种子（identitySeed，§54 禁设备指纹）+ 基线稳定性 + 运动偏好；computeLifeSeason：60 天画像时间线中性词表；buildDailyComposition / buildMomentState / smoothPresenceState α=0.35 §60）→ 映射（computeVisualParameters 真实消费四层：motionPersonality/symmetryTendency/textureFamily → 流动/相干/密度；lifeSeason.drift → 湍流下限；daily → 五视觉参数；moment.breathingPeriod → 帧呼吸周期）→ 渲染（帧色相由 identity seed Knuth 散列决定——颜色属于 Identity 非状态）→ Journey 快照（Canonical 存 seed/params/identity 引用/maturity/evidenceIds，reconstructJourneyFrame 确定性重建）；§61 巡检：presence/journey 无 TODO/FIXME，rhythmDelta=0f 仅为结构默认（装配恒赋真实 season.drift）；**新锚点**：IdentityPipelineTruthTest 3 用例（四层各自真实改变视觉参数 / Canonical 编解码往返重建帧与当日同帧 / 同 seed 跨成熟度颜色一致·开放度成长——「Day1/Day180 同一个 ECHO」可测化）
- **ERA 14 §52 真值审计第 2 轮**：Journey 侧逐段验证——§84 回退重建（Canonical 缺失 → journeyDayParams 画像回退：确定性同帧且与直接渲染链路一致、双缺失 → null 不伪造）；§87 Life Season × Journey 解释链（assembleJourneyUiState.seasonExplanation 与 computeLifeSeason→explainLifeSeasonVisual **逐字绑定**（SEASON/YEAR 尺度）；全 SIMILAR 时间线仅出数据派生的「more_regular」一行、无漂移编造——审计中发现并固定该语义）；§61 巡检扩展：journey/intelligence/observation/memory/actions 五模块零 TODO/FIXME；**新锚点**：LifeSeasonJourneyBindingTest 4 用例
- **ERA 14 §52 真值审计第 3 轮**：快照落盘恢复链审计发现**真实断链**——EchoPresenceCodec v1 仅存 13 字段（rhythm/behavior/confidence/seed/accentHue），进程死亡后 Wallpaper/Dream 恢复的 ECHO 丢失 LifeSeason drift、DailyComposition、MomentState 与 Identity 的 6 项形态字段（颜色连续、人格/纹理/季节调制全失）；**修复**：codec v2（40 字段：Identity 全 8 项 + LifeSeason 7 项 + Daily 12 项 + Moment 2 项；叙事字段仍永不入快照），v1 快照继续可解（新字段结构默认，与 v1 时代语义一致），版本/长度校验 fail-closed 收紧；Wallpaper/Dream 服务确认以 identityGenome.seed 渲染（同 seed 同帧）；**新锚点**：EchoPresenceCodecTest 扩 4 用例（全四层往返相等 / 跨进程同帧 / v1 兼容 / 截断与未知版本 fail-closed）
- **ERA 14 §52 真值审计收官**：Wallpaper 刷新链复核——onVisibilityChanged(true) 补 refreshSnapshot()（解锁/回前台即追上 15 分钟 Presence 刷新，不再等 surface 变化；SharedPreferences 读开销可忽略）；EchoRuntimeCoordinator §3 职责边界逐条复核 PASS（只做感知编排/Presence 刷新/健康聚合/Provider 轻量状态——无 AI reasoning、无 Context 编译、无基线计算、无记忆合成、无 UI 渲染）；§4 健康四组件齐备（sensing 六态映射/presence 组装态/intelligence Provider 态/memory 进程级 READY 文档化不变量）；**ADR-058 四轮结项**：审计发现 1 处真实断链（codec v1）已修复，3 组测试锚点（IdentityPipelineTruth / LifeSeasonJourneyBinding / EchoPresenceCodec v2）固定「Day1/Day180 同一个 ECHO」端到端真值
- **解释链真值审计第 1 轮**：ADR-059 选定下一长阶段（§67-73 解释链真值审计与深化，理由：宪法 Intelligence depth > 视觉打磨；不选 Year 视图深化/真机基准二期）；**发现并修复 §68 死参数**——ContextRanker.rank(task, items) 的 task 参数从未参与打分；实现 task relevance 粗粒度亲和（EXPLAIN_CURRENT_STATE→TODAY_AGGREGATE / 纵向与摘要→PORTRAIT_HISTORY / 个人问题→PREFERENCES / 行动建议→CONTEXT_EXCEPTIONS，+0.5 封顶远低于 tier 差 10 分——**亲和只影响同 tier 内部，永不跨越纠正/上下文例外优先层级**）；**端到端链锚点**：IntelligenceChainTruthTest 2 用例（分类→编译→Grounding→降级整链契约 + task relevance 同 tier/跨 tier 边界）
- **解释链真值审计第 2 轮**：检索策略矩阵逐任务复核——11 任务策略表全检：原始通知/音频/麦克风 NEVER_ALLOWED 全任务硬禁止 ✓；§78/§79 用户解释（MemoryType.CONTEXT）进入全部 7 个解释/总结/建议任务 ✓；§69 预算（证据 ≤40 / token ≥200 / 时间窗 1-365）全任务有限 ✓；Grounding 引用判定边界 5 契约（空证据 AI 叙事必须失败 / 观察层级不要求证据 / 空 id 不崩且零引用 / 短 label 可引用 / 引用命中携带 evidenceIds）；**新锚点**：IntelligencePolicyMatrixTest 7 用例——策略矩阵演化时的隐私硬边界与用户解释优先防回归
- **解释链真值审计第 3 轮**：§73 失败链逐段复核发现**「retry」步骤只有注释没有实现**（validator 注释宣称 validation→repair→retry→fallback，代码无任何重试）；**修复**：AiNarrativeService 增 `reasonWithSingleRetry`——仅瞬态失败（NETWORK_ERROR/PROVIDER_ERROR）重试一次（共 2 次尝试），配额/限流/认证/模型不存在等语义失败立即降级（重试只会放大伤害）；三处叙事/问答调用点全部接入；StructuredOutputValidator 矩阵复核 PASS（干净 JSON/prose 包裹修复/垃圾拒绝/禁词表/监视语言/超长/安全观察 7 用例已在册）；**新锚点**：AiNarrativeServiceTest +3（瞬态失败重试成功=2 次调用 / 两次失败降级确定性=2 次调用 / 限流不重试=1 次调用）
- **解释链真值审计第 4 轮**：EchoConversationController 此前**零测试**（多轮链核心控制器无锚点）——补 6 契约：全链编排（分类→检索→回答→记录，COMPLETE 相位 + 来源携带）/ provider 失败 → FAILED 相位诚实降级且仍记录（永不空白）/ 4 轮历史窗口滚动（第 6 问的 history 恰为最近 4 轮 8 条，窗口外轮次被滚出）/ 检索异常不破链（空证据继续诚实降级）/ DETERMINISTIC → FALLBACK 相位映射 / clear 重置轮次与相位；Provider 配置修复闭环复核（validate 7 用例：normalize/缺失字段/不安全 URL/本地 http/172 公网段/超时边界 + testConnection 诊断 + providerComponentHealth 状态映射）PASS
- **解释链真值审计收官**：发现两处 **spec 字段流亡**——§70 baseline/comparison 装配不落、编译不读（画像事实把「平常/变化」合并进长句，模型拿不到结构化对比）；§71 EchoAnswer.timeRange 恒 null（spec 字段无任何写入点）。**修复**：fromPortrait 把 baseline/comparison 落入 schema 指定字段，EchoContextCompiler 以「（平常：X）（变化：Y）」显式进入模型上下文（无对比字段不产空括号）；GroundingValidator.buildAnswer 由被引用证据时间范围导出 timeRange（最早~最晚 / 单一 / 无引用 null）；**ADR-059 四轮结项**：换模型不失忆（记忆端口检索与模型无关）与模型崩溃 ECHO 不消失（fallback 链）具备端到端锚点；**新锚点**：EvidenceSchemaFlowTest 3 用例
- **What ECHO Knows 深化第 1 轮**：ADR-060 选定下一长阶段（§80 What ECHO Knows 深化——User trust/User control 直接受益；不选 Year 视图打磨与千级压测本地化）；**§80 七层分层摘要落地**：MemoryLayerCounts（纯映射：七 MemoryType → 七计数 + total；过滤不影响计数——用户永远看到全貌），MemoryManagementUiState.layerCounts 接入 VM，WhatEchoKnowsContent 摘要行（「共 N 条：你确认过 X · 你告诉我的 Y · 我观察到 Z · 你的偏好 · 你纠正过 · 发现的模式 · 还在推测」）；**新锚点**：WhatEchoKnowsContentSmokeTest +1（七层摘要行精确渲染）
- **What ECHO Knows 深化第 2 轮**：「ECHO 不知道什么」能力边界落地（FINAL PRODUCT ACCEPTANCE「明确知道 ECHO 知道什么/不知道什么」）——EchoKnowsFacts（sensingEnabled/micEnabled/providerConfigured/baselineDays）+ EchoDoesNotKnow 纯映射（感知未开 → 不观察屏幕/移动；感知开但麦克风关 → 不记录声音；未接 AI → 只用本地确定性解释；基线 <7 天 → 不能可靠区分平常与变化；能力齐备零行不编造）；VM 由运行时事实采集（sensingActive / micEnabled flow / provider READY / computeToday.baselineDays）；UI 边界块（无行即隐藏）；单层详情确认 = 既有过滤 chips（点选任一层 → 该层列表）；**新锚点**：EchoDoesNotKnowTest 4 用例 + WhatEchoKnowsContentSmokeTest +2（边界块呈现/隐藏）
- **What ECHO Knows 深化收官**：每条记忆行补齐来源+保留解释——来源 = 七层标签（既有），保留 = retentionLabelText 纯映射（与 retentionDaysFor 同源：7/30/365 天到期自动软删 / 用户固定永不清理），UI 行内「保留 X」行；**ADR-060 三轮结项**：Me 世界「ECHO 知道什么（七层摘要）/ 不知道什么（能力边界）/ Memory 有什么（逐条来源+保留）」完整呈现且逐项可测；**新锚点**：EchoDoesNotKnowTest +1（四保留类映射）+ WhatEchoKnowsContentSmokeTest +1（行内保留标签渲染）
- **Memory 长历史性能与正确性第 1 轮**：ADR-061 选定下一长阶段（§109 审计——数据真值 > 视觉打磨；不选 Year 视图深化与数据权利检查台）；**发现并修复真实缺陷**：purgeExpired 用 topByUser(limit=500) 按重要度截断——低重要度短时记忆（恰是最可能过期的一类）被遗漏在过期扫描之外；修复：MemoryDao 增 allNonDeletedByUser（无 LIMIT 维护专用全量查询），purgeExpired 切换全量扫描（shouldForget 单一事实源不变）；**JVM 代表性基准**：MemoryScaleBenchmarkTest 2 用例（rankMemories 1000/5000 条排序 <5000ms 且不丢条目 / shouldForget 5000 条扫掠 <5000ms 且样本含可过期命中）
- **Memory 长历史性能与正确性第 2 轮**：检索 limit 语义复核 PASS（topMemories 取 limit×3 候选 → JVM rankMemories → take(limit)——§109「query ranking」的既定实现，与编译器策略 maxMemories 双层收紧一致）；**derivePatterns 五千级锚点**（5000 条 <5000ms；唯一内容样本零派生顺手锚定幂等语义）；**维护 Worker 单轮成本上限**：purgeExpired 增 MAX_PURGE_PER_ROUND=2000（万级记忆时单轮 UPDATE 数有界，剩余过期项下轮继续——软删幂等无顺序依赖）
- **Memory 长历史性能与正确性收官**：万级护栏（rankMemories / shouldForget 扫掠 / derivePatterns 各 10000 条 <8000ms——预算宽于五千级以区分真数量级退化）；PERFORMANCE_BASELINES 9 → **12 行**（Memory 长历史三行入表：五千级排序 / 五千级扫掠 / 五千级派生），README「12 行性能防退化预算」与 docs/current 事实表同步；**ADR-061 三轮结项**：§109 具备查询级（userId+LIMIT+复合索引 v11）与算法级（万级护栏）双重证据
- **Me 数据权利检查台第 1 轮**：ADR-062 选定下一长阶段（User trust/User control 直接受益；复用 47/49 五域导出删除与 61 轮能力边界事实）；**五域存储足迹落地**：DataFootprint（派生特征窗口 / 画像缓存 / 同意记录 / 记忆存储行 / Journey Canonical 快照 + total），LocalDataRights.footprintSummary（计数查询聚合；MemoryDao 增 countAllByUser——记忆按存储真值计数含软删审计行，语义在 UI 层明确标注）；**新锚点**：LocalModeTest +1（五域各 1 → total 5 → 删除后归零）
- **发布门禁自愈轮（ERA 32 同轮）**：package_release.sh 预检故障注入矩阵 16/18 暴露两个死锚点——ERA 13.5 模块化后 fault_injection_check.py 仍指向旧路径 `android/app/.../sensing/SensingEventHub.kt`/`SensingWindowScheduler.kt`（实际已迁 :feature:observation，`_read` 返回空串 → 条件恒 False）→ 路径修复后 18/18 PASS；README 措辞改动被 test_README_pytest_count_matches_collection 契约测试当场拦截（`**N 项全绿**` 模式冻结）→ 已恢复——发布链预检从此真全绿

## In Progress

- Affective Intelligence（可选时代）：§8 评估框架已就绪；§8 阈值定稿 + 试点脱敏验证集、§9 PIPIA、§10 错误恢复为激活前置（人工评审门槛）——**affectiveState 恒 null（测试强制）**

## Blocked

- Affective Intelligence 激活：被冻结契约 AFFECTIVE_CONTRACT §8（临床/安全评审定稿）/§9（PIPIA 审计）/§10（错误恢复前置）阻断——属主提示明确的人工审批门槛，如实冻结不绕过
- 无其余阻塞（Android 本机构建依赖 /tmp/echo-build JDK+SDK；缺失时由 CI android-ci/release-closure 兜底）

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

- ~~SQLCipher passphrase KDF（Keystore 派生 + 固定 IV 域）~~ → ERA 17 已迁移：HKDF-SHA256（RFC 5869 向量验证）+ 受保护随机秘密 + 字段/DB 密钥分离 + 旧库自动 rekey 迁移链（迁移测试覆盖）
- ~~GitHub Actions actions 仍 pin major tag~~ → ERA 18 已全部 pin immutable SHA + verify_workflow_pins.py 门禁

## Performance Debt

- ~~Journey 365d 全量 Compose 渲染风险（当前按 30 天聚合；未做 lazy 优化）~~ → ERA 16 解决：Canonical Daily State 预聚合快照（§108），年视图只读 ≤365 行参数行 + 河段/月度聚合，无全量实时计算
- ~~Memory Long History（SELECT everything → JVM sort 风险）~~ → ERA 18 收尾：Room v11 复合索引 + LIMIT 截断 + JVM 防退化预算（§109）
- Wallpaper 设备实测数字（CPU/frame/memory/wakeups/battery）由 CI connected-test/真机矩阵执行（基准已冻结）
- EchoLifeField 帧渲染依赖 draw-phase 状态读取（当前可用；未做 profile）

## Release Integrity

- ✅ 版本单一事实源 v0.9.0（version_source/README/pyproject/versionName/manifest 一致，CI 断言）
- ✅ Git source = SOURCE_MANIFEST = distributed source ZIP = extracted verified source（同一 Gate，实测 PASS）
- ✅ signed APK ↔ BUILD_PROVENANCE ↔ RELEASE_ARTIFACT_MANIFEST ↔ final release package（DAG 无循环）
- ✅ release 必须 clean tree（`generate_provenance.py --require-clean`；CI 同 run 生成全部元数据）

## Next Highest-value Task

检查台第 2 轮：DataAndSensingViewModel 接入 footprintSummary（StateFlow 刷新：页面可见与导出/删除后自动重算）+ UI 检查台卡片（五域足迹行 + 记忆含软删行标注 + 导出/删除按钮归位到同卡）+ 权限/基线状态复用 EchoDoesNotKnow 事实聚合呈现。Affective §8/§9/§10 仍处人工评审等待（冻结不绕过）；osv-scanner 本地首跑待 GitHub release CDN 可达（CI 已强制）。
