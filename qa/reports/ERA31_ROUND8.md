# ERA 31 Round 8 报告（BATCH 7 收尾：core:ports 依赖倒置收官）

> 日期：2026-08-15。ERA 40 §46 是架构冻结下唯一允许的模块化残余——本轮完成它，
> 按约定「完成后停止模块化工作」。

## 1. 移动内容

| 文件 | 去向 | 符号 |
|---|---|---|
| `feature/presence/.../EchoPresenceState.kt` | `core/model/.../EchoPresenceState.kt`（package → `com.yunjue.echo.mind.model`） | EchoPresenceState / EchoMaturity / echoMaturity / learningPhaseHeadline / EchoLifeSeason / EchoDailyComposition / EchoMomentState / EchoIdentityGenome / RhythmState / BehaviorState / SEED 文案常量 |
| `feature/observation/.../sensing/SensingRuntimeStatus.kt` | `core/model/.../SensingRuntimeStatus.kt`（package → model） | SensingRuntimeStatus 六态 / Inputs / resolve / 六态文案 / sensingRuntimeStatusText |

## 2. 结果

- **core:ports 不再依赖任何 feature**：build.gradle 仅剩 core:model；源码 import 仅 `com.yunjue.echo.mind.model.*`。
  core → feature 依赖全部清零（依赖图实测 `ports ──► observation/memory/presence` 全部消失）。
- 全仓 70+ 文件 import 重写（presence/journey/qa/app/observation 主源 + 测试 + androidTest）；
  迭代编译收敛至 0 error；10 个模块 lockfile 重写（classpath 变化）。
- **模块化工作停止**（§46 约定）：不再继续拆 module、不再加 module。

## 3. 验证

- Android **1004 全绿**（app 852 / intelligence 20 / presence 21 / qa 111）+ compileDebugAndroidTestKotlin +
  detekt 27 规则 + lintDebug PASS；
- 快照/视觉黄金套件零漂移（纯搬移，行为不变——快照套件作为行为等价证明）；
- SOURCE_REALITY_REPORT / ANDROID_DEPENDENCY_GRAPH 重生成（CI 零漂移门同步），
  顺带修正 latent 文档缺陷（feature:qa 模块此前未登记；kt 153→164）；
- ADR-073 第 8 轮记录入册。

## 4. 后续（BATCH 8）

Product Quality release candidate 准备：clean checkout 全 Gate、release closure 重跑
（tests / product QA / Android build / backend build / archive / manifest / SBOM / provenance /
artifact manifest / release package——不带旧 v0.9 proof 发布新代码）。
