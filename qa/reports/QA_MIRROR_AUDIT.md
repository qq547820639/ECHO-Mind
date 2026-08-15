# QA Mirror Audit（:feature:qa 与 production 重复实现）

> ERA 31 Execution Batch 1 第 3 项。原则（ERA 38 §41）：**QA 测产品，不要 QA 重写产品。**
> 审计日期：2026-08-15。审计对象：`android/feature/qa/src/main` 三个 mirror +
> 其余 fixture 资产；对照 production 实现逐文件比对。

## 结论总表

| Mirror | 重复对象（production） | 性质 | 判定 | 处置 |
|---|---|---|---|---|
| `QaPortraitMirror` | backend `app/services/portrait/dimensions.py`（compute_dimensions） | Kotlin 移植（1:1 语义） | **必要镜像**——Android 运行时消费后端 API；JVM QA 时间线无后端可用 | 保留 + 加跨语言黄金漂移门（见下） |
| `QaHeadlineEngine` | `:app` `EchoSceneUiState.kt`（learningPhaseHeadline / deterministicHeadline 装配） | 文案重复（同逻辑双实现） | **真重复**——production 在 :app，:feature:qa 因模块边界无法复用 | BATCH 7 下沉生产纯函数到 :feature:intelligence，双端复用；期间以黄金文案测试绑定 |
| `QaAskEcho` | 无（production 回答走 QuestionClassifier → ContextRanker → AiNarrativeService） | 独立 Expected Evidence 黄金（eval oracle） | **不是 mirror**——它是 Batch 2 Personal Question Eval 的预期证据种子 | 保留为 oracle；Batch 2 用真实链路与其对拍 |

## 1. QaPortraitMirror vs backend compute_dimensions

逐段比对结果（`QaPortraitMirror.computeDimensions` ↔ `dimensions.py:82`）：

- ✅ scale = max(mad×1.4826, (p75−p25)/2, floor)；floor = MIN_ABS_DELTA[metric]（ERA 21 修复一致）。
- ✅ MIN_ABS_DELTA 六项数值一致（active_start/end=10，movement=0.02，screen/late_screen=5，spread=0.05）。
- ✅ MIN_REL_DELTA=0.05、Z_SIMILAR=0.7；below-min-delta → z=0 语义一致。
- ✅ missing != irregular：active_start / active_hour_spread 缺失 → 维度省略（RHYTHM/DAY_STRUCTURE 的 `z is None → pass` 分支一致）。
- ✅ 五维度方向词一致（EARLIER/LATER、LESS/MORE、MORE_CONCENTRATED/MORE_FRAGMENTED）；STABILITY = 实际输出维度非 SIMILAR 计数（VERY_SIMILAR/SLIGHTLY_DIFFERENT/CLEARLY_DIFFERENT）。
- ⚠️ 已知偏差（如实记录）：backend STABILITY 输出携带 `diff_count` 附加字段，Kotlin `PortraitDimensionDto` 无此字段（UI 不消费，无产品影响）。

**风险**：两实现无自动化对拍——Python 改阈值/Kotlin 漏跟会静默漂移，QA fixture 从此测的是「镜像」而非「产品」。
**处置（立即）**：加跨语言黄金门——backend 侧生成固定 fixture 的 `compute_dimensions` JSON golden
（`qa/reports/mirror_goldens/`），`QaPortraitMirrorTest` 用同输入断言逐维度一致。若 backend 语义有意变更，
更新 golden 时必须同 commit 改镜像（同源演化，禁止分叉）。

## 2. QaHeadlineEngine vs :app EchoSceneUiState

- production 学习期文案（`learningPhaseHeadline`）：SEED「初见。」/ DISCOVERING·EMERGING「我开始看到一些属于你的节奏。」/ KNOWN「我开始认识通常的你了。」/ MATURE「ECHO 还在了解今天。」（MATURE 为不可达兜底）。
- `QaHeadlineEngine.learningPhase` 复刻前三态 + 自带的 MATURE 分支——**同逻辑双实现，且各自演化**（例如 production 已在 MATURE 分支与 mirror 不同）。
- production 的 deterministicHeadline = portrait.headline 拼接 → summary → learningPhaseHeadline；mirror 则用 dimension 驱动 public voice——**装配策略也不同**，QA 快照里的 headline 不代表真实 Scene 文案。

**处置（BATCH 7，允许的架构整理）**：把 `learningPhaseHeadline` 与 headline 装配纯函数从 :app 下沉
`feature:intelligence`（该模块无 ui 依赖，:feature:qa 已依赖它），:app 与 QA 同源调用；删除 QaHeadlineEngine 的文案分支，
只保留 QA 侧证据行组装（Evidence/AI layer 的 fixture 视图）。期间先在本轮把 mirror 文案与 production 对齐（含 MATURE 兜底）。

## 3. QaAskEcho（非 mirror）

`QaAskEcho` 是 8 条 canonical personal questions 的确定性 Expected Evidence 黄金（时间窗/基线/数字 + 中性结论），
production 真实回答链路（分类→检索→Grounding→叙事）不在 QA module 内运行。
判定：eval oracle 保留；**Batch 2 用真实链路回答与 oracle 对拍**（evidence 命中率 / grounding 合规 / 用户口吻），
对拍失败时优先怀疑 production 链路而非 oracle。

## 4. 其余 fixture 资产（快检）

- `QaDaySimulator` → 产出 `LocalDayAggregate`，直接进 production `LocalBaselineCalculator`/`AmbientEngine`——**测产品** ✅。
- `QaTimeline.snapshot` 全链走 production（buildLocalBaseline → AmbientEngine → computeLifeSeason →
  LifeSeasonTracker → deriveIdentityGenome → buildDailyComposition/MomentState → EchoVisualMapper）——**测产品** ✅。
- `VisualReviewRenderer`（本轮新增）只调用 production 帧管线 + production android.graphics 渲染器——**测产品** ✅。
- `QaSnapshotReport / QaProductSnapshot`：报告层，无生产逻辑重复 ✅。

## 5. 立即执行（本轮）

1. ✅ 本报告入库 `qa/reports/QA_MIRROR_AUDIT.md`。
2. ✅ `QaHeadlineEngine.learningPhase` 与 production `learningPhaseHeadline` 逐字对齐（含 KNOWN/MATURE 独立分支，MATURE 兜底「ECHO 还在了解今天。」一致）。
3. ⏳ 跨语言黄金门（backend golden JSON → QaPortraitMirror 对拍）——下一轮优先。

## 6. 后续（BATCH 7 收口）

- :app headline 纯函数下沉 :feature:intelligence；QaHeadlineEngine 文案分支删除。
- 复检 `QaPortraitMirror` 是否可改由「backend 导出的 golden 快照」驱动（不跑 Python），以 golden 而非镜像为 fixture 基线。
