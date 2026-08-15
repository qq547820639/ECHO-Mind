# CONTRACT COMPLIANCE —— 冻结契约 → 实现 → 测试 三级对照总表

> ERA 84（ADR-070）产出的机器可校验对照表：每个条款必须有实现与测试锚点；
> 「冻结/外部门」= 明确不做或依赖外部评审的条款（Affective §8-§10 等）。
> 锚点路径由 `scripts/contract_compliance_check.py` 校验存在性（CI source-integrity 执行）。

## A. ECHO Product Constitution（docs/product/ECHO_PRODUCT_CONSTITUTION.md）

| 条款 | 实现锚点 | 测试锚点 |
|---|---|---|
| §0 产品最终定义（Personal Ambient Intelligence） | `android/feature/presence/src/main/java/com/yunjue/echo/mind/presence/EchoPresenceState.kt` | `android/app/src/test/java/com/yunjue/echo/mind/presence/FirstRunVerticalTest.kt`（Day-0 苏醒/确定性） |
| §1 产品顺序 Ambient→…→Intervene | `android/app/src/main/java/com/yunjue/echo/mind/ui/echo/EchoSceneViewModel.kt`（InterventionPolicy L2 opt-in） | `android/app/src/test/java/com/yunjue/echo/mind/ui/EchoSceneContentSmokeTest.kt` |
| §2 三个世界（ECHO/Journey/Me） | `android/app/src/main/java/com/yunjue/echo/mind/ui/EchoSceneScreen.kt` / `android/app/src/main/java/com/yunjue/echo/mind/ui/journey/JourneyScreen.kt` / `android/app/src/main/java/com/yunjue/echo/mind/ui/me/MeScreen.kt` | `EchoSceneContentSmokeTest` / `JourneyScreenSmokeTest` / `MeStateAssemblyTest` |
| §3 第一原则（模型不是 ECHO 等） | fallback 链 `android/feature/intelligence/src/main/java/com/yunjue/echo/mind/intelligence/AiNarrativeService.kt`；Journey Canonical `JourneyYearView.kt` | `android/app/src/test/java/com/yunjue/echo/mind/intelligence/IntelligenceChainTruthTest.kt` / `JourneyYearViewTest` / `CorrectionLoopChainTest` |
| §4 契约体系（版本化禁止偷改） | `PORTRAIT_CONTRACT.md` + `scripts/contract_drift_check.py` + `docs/contract-manifest.json` | `backend/tests/test_immutability_v03.py` + `test_portrait_golden.py`（001-008 镜像 `LocalPortraitGoldenTest`） |
| §5 反模式（做成任一项即失败） | 编译器边界 `android/app/src/test/java/com/yunjue/echo/mind/ArchitectureBoundaryTest.kt`（9 断言）+ detekt 27 规则 | `ArchitectureBoundaryTest` / `SourceIntegrityTest`（7 断言） |
| §6 Engagement 禁令（dark pattern 零容忍） | 无通知轰炸/无签到积分机制（AppPreferences 无 streak-gamification 字段） | 复核结论（无违规模块；人工评审项，无自动锚点——记录为待办） |
| §7 新需求十二问（默认否决闸） | 决策流程（ADR 体系：`docs/architecture/ADRS.md` ADR-058~070 逐选型记录） | 流程性条款（无实现锚点；ADR 记录为审计证据） |
| §8 不确定时的决策顺序 | ADR 选型理由逐条引用优先级（ADRS.md） | 流程性条款 |
| §9 核心指标（替代 DAU/时长） | baselineDays/validDays 真值链（`LocalBaselineCalculator.kt`） | `BaselineConductionTest` / `LocalPortraitGoldenTest` |
| §10 场景验收（最终完整体验） | First-Run 切面 + 各审计轮（ADR-058~069） | `FirstRunVerticalTest` / `CorrectionLoopChainTest` / `JourneyUiStateAssemblyTest` |

## B. Portrait Contract（PORTRAIT_CONTRACT.md）

| 条款 | 实现锚点 | 测试锚点 |
|---|---|---|
| 观察事实（Ground Truth 层） | `android/feature/observation/src/main/java/com/yunjue/echo/mind/localportrait/`（本地引擎）+ `backend/app/services/portrait/` | `LocalPortraitGoldenTest`（001-008 镜像 backend golden）/ `PortraitContractParseTest` |
| 端口语义（GET /portraits、无副作用） | `scripts/contract_drift_check.py` + `docs/contract-manifest.json` | `backend/tests/test_version_consistency.py`（openapi 端点一致性） |
| 维度禁止值（GOOD/BAD/HEALTHY/NORMAL/ABNORMAL） | `android/core/model/src/main/java/com/yunjue/echo/mind/model/Models.kt`（维度枚举）+ 中性词表 | `LocalPortraitGoldenTest`（containsBlockedVocabulary 断言）/ `Phase6PsychologyReviewTest` |
| 基线字段（status/baseline_days/coverage/bucket） | `LocalBaselineCalculator.kt` ↔ `backend/app/services/baseline/calculator.py` 逐语义镜像 | `BaselineConductionTest`（confidenceFor 边界镜像） |

## C. Personal Intelligence Contract（docs/intelligence/PERSONAL_INTELLIGENCE_CONTRACT.md）

| 条款 | 实现锚点 | 测试锚点 |
|---|---|---|
| §1 三层真相（OBSERVED/INTERPRETED/FELT） | `GroundingValidator`（Observed/Interpreted 边界）+ `AffectiveSignal`（Felt 独立） | `IntelligenceChainTruthTest` / `AffectiveContractFreezeTest`（Felt 冻结） |
| §2 Confidence 一等公民 | `EvidenceItem.confidence` + `confidenceFor` | `CorrectionLoopChainTest` / `BaselineConductionTest` |
| §3 Provenance 可追溯 | `EchoMemory.provenance` + `EvidenceItem.provenance` + audit 链 | `CorrectionLoopChainTest`（user-correction:v1 映射） |
| §4 禁区（医学/心理推断） | 词表防线 `containsBlockedVocabulary` + `explainLifeSeasonVisual` §57 词表 | `Phase6PsychologyReviewTest` / `LocalPortraitGoldenTest` / `JourneySeasonNarrative` 测试 |
| §5 Memory 契约（分类/字段/生命周期/门槛/用户控制） | `android/feature/memory/src/main/java/com/yunjue/echo/mind/memory/EchoMemory.kt` + `MemoryRepository.kt`（record/confirm/forget/pin/edit/purgeExpired） | `MemoryMaturityTest` / `EchoCorrectionServiceTest` / `DatabaseMigrationTest`（生命周期 DAO 语义） |
| §6 Context 契约（模型无 DB 访问权） | `EchoContextCompiler`（policy → 剔除 → budget → 编译） | `EchoContextCompilerTest`（prohibited/budget/question/schema/usedSources） |
| §7 纠错契约（用户自述最高优先） | `EchoCorrectionService` + `recordCorrection` | `EchoCorrectionServiceTest` / `CorrectionLoopChainTest`（头位+注入+归因） |
| §8 Provider 边界 | `AiProviderManager` + 私网判定（RFC 1918） | `ProviderTestConnectionTest`（内网放行/公网拒绝） |
| §9 Privacy Budget（每任务必须定义） | `ReasoningTasks.kt`（contextPolicyFor 全任务 allowed/prohibited/budget） | `EchoContextCompilerTest`（预算截断）/ `IntelligenceDepthTest` |
| §10 Explainability | `EvidenceAssembler`（baseline/comparison）+ why 层 | `EchoWhyLayerSmokeTest` / `EvidenceSchemaFlowTest` |
| §11 Fallback Chain（AI 失败不破坏 ECHO） | `AiNarrativeService.reasonWithSingleRetry` → deterministic narrative → 观察事实 | `IntelligenceChainTruthTest` / `EchoSceneContentSmokeTest`（六态） |
| §12 版本与变更 | `scripts/version_source.json` + 单头迁移链 | `test_immutability_v03.py` / `test_version_consistency.py` |

## D. Affective Contract（docs/intelligence/AFFECTIVE_CONTRACT.md）—— 冻结

| 条款 | 状态 | 锚点 |
|---|---|---|
| §1 授权（explicit opt-in） | 冻结：affectiveState 恒 null | `android/app/src/test/java/com/yunjue/echo/mind/AffectiveContractFreezeTest.kt` |
| §2 允许/禁止信号 | 冻结：字段存在、无写入路径 | 同上 |
| §3 连续 latent 禁标签 | 冻结：AffectiveSignal 结构定义 | `EchoPresenceStateTest` |
| §4 置信度门槛 / §5 保留政策 / §6 用户纠错 | 冻结（实施前不可用） | AffectiveContractFreezeTest |
| §7 视觉/语言/干预政策 | 冻结（默认不开启） | 同上 |
| §8 模型验证 / §9 隐私审查 / §10 错误恢复 / §11 版本评审 | **外部门（人工评审前置）** | BLOCKED 记录：ADR 各轮「Affective §8/§9/§10 仍处人工评审等待（冻结不绕过）」 |

## 记录与待办

- §6 Engagement 禁令：无自动锚点（复核无违规）——记录为人工评审待办。
- Affective §8/§9/§10：人工评审门（不绕过）。
- feature_vectors 留存裁剪：阻塞条件 1（不可逆删除真实用户数据），非人工确认不自主执行。

## E. Institutional Support 纵向切面（ERA 86 / ADR-071）

| 条款 | 实现锚点 | 测试锚点 |
|---|---|---|
| 升级上报 receipt 诚实（送达 ≠ 人工已收到） | `android/app/src/main/java/com/yunjue/echo/mind/data/EscalationRepository.kt` + `backend/app/api/escalations.py`（delivery_confirmed_at 接收即写；human_acknowledged 仅显式 ack/takeover） | backend 全量套件（escalation/receipt 用例）+ Android SupportSection 语义 |
| 试点就绪总控表与 final distributed state 对齐 | `pilot-pack/00_试点就绪总控表.md`（ERA 86 刷新：v0.9.0/provenance/测试数/5 条 CI；外部阻断项保持外部门） | `scripts/contract_compliance_check.py`（本文件锚点校验） |
