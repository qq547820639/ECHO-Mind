# Time-to-ECHO（BATCH 5 §50 正式体验指标）

> ERA 31 R6。指标定义：**fresh install → 第一眼看到 ECHO**。
> 本文件是测量契约：哪些段能机器测量、哪些段是人因——不伪造数字。

## 1. 指标分解（端到端）

| 段 | 内容 | 预算 | 测量方式 |
|---|---|---|---|
| 1. Welcome | 2 勾选（18+/边界）+ 1 击「开始」 | 人因（不可机器测） | 用户研究/dogfood 秒表 |
| 2. Privacy pledge | 3 句承诺 + 5 勾选 + 1 击「我理解并继续」 | 人因 | 同上 |
| 3. Core sensing | 能力行只读 + 可选通知授权（可跳过）+ 1 击「让 ECHO 开始了解我」 | 人因 + 系统权限弹窗时间 | 同上 |
| 4. Awakening | `AWAKENING_DURATION_MS`（呼吸过渡） | **2200ms（实测常数）** | 代码常量；超 3s 视为回归 |
| 5. Presence 装配 | 当日聚合 → baseline → Ambient → identity → presence | **<10ms** | PerformanceBaselineTest（装配全链 200 次 <2s） |
| 6. 首帧 | computeEchoSceneFrame + 渲染 | **<1ms（计算段）** | PerformanceBaselineTest（首帧计算 1000 次 <2s） |

**机器可测段合计：≈ 2.21s**；人因段由 dogfood 采集（BATCH 6）。

## 2. §49 授权完成不停留（审计确认）

- 核心授权完成（onAwaken）→ `awakenedAtEpochMs` 锚点 → AwakeningScreen（自动过渡，
  `LaunchedEffect { delay; onFinished() }`）→ finishOnboarding → 主界面。**没有「继续」按钮、
  没有 DONE 页、没有停在原页面**。OnboardingStepContentSmokeTest 7 用例锁定三步矩阵与门禁。
- 「暂不开启」（abstain）不阻断离开：不启动感知直接进入应用——用户控制 > 留存。

## 3. §51 首帧不等待（审计确认）

ECHO Scene 首帧链路：`EchoSceneViewModel.uiState` = combine(本地画像流, Presence 流,
感知六态, 叙事流)——**全部本地 StateFlow**，不等待 Provider / Backend / Memory 全量扫描 / Journey。
- 首帧视觉：EchoStateStore 内存态（null → 中性占位，不编造）；
  第一次 Presence refresh（本地聚合，<10ms）后切换为 **SEED ECHO**（identitySeed 持久化于
  AppPreferences，与安装期 identity 同源）。
- Provider 不可用不影响任何视觉/画像（narrative 层独立降级）。
- 改进候选（记录不实施）：把「中性占位」换成「Seed ECHO 预渲染」需要持久化 identitySeed
  首帧直读——收益 = 2.21s 后的一瞬间差异，不值得；保持现状。

## 4. 优化记录

- AWAKENING_DURATION_MS = 2200ms（R6 实测值；呼吸过渡 1.4s 周期 ×1.5 倍，感受完整但不拖沓）。
- 本指标不进入 CI 硬门禁（人因段不可测）；机器段预算由 PerformanceBaselineTest 既有行覆盖。

## 5. 下一步（BATCH 6 dogfood）

- 真实安装秒表：三段人因时间 + 首帧到 SEED 的观感；
- 授权流程中的任何「不知道下一步」观察 → PRODUCT_DEFECT 归档。
