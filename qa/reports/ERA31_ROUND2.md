# ERA 31 Round 2 报告（跨语言黄金门 + 运动序列 + Core Personal Reasoning Set）

> 日期：2026-08-15。R1 的「下一轮优先」项全部落地，并正式进入 BATCH 2 第一项。

## 1. 跨语言黄金门（QA_MIRROR_AUDIT 遗留项收口）

QaPortraitMirror 与 backend compute_dimensions 的漂移风险从「审计发现」变为「双端 CI 门禁」：

| 工件 | 位置 | 作用 |
|---|---|---|
| 语言中立 fixture（5 cases） | `qa/reports/mirror_goldens/fixture.json` | 同日输入喂两种语言：typical / big_late_drift / missing_start / near_zero_baseline（scale floor）/ empty_baseline |
| golden（backend 真值） | `qa/reports/mirror_goldens/golden.json` | `backend/scripts/export_mirror_golden.py` 确定性导出 |
| backend 漂移门 | `backend/tests/test_mirror_golden.py` | 提交的 golden ≠ 当前 compute_dimensions → backend CI 红 |
| Android 对拍门 | `QaPortraitMirrorGoldenTest`（feature:qa） | 同 fixture 构建 Kotlin 结构 → mirror 输出逐字段对拍（value/metric/z；STABILITY.diff_count 为 backend 附加字段，审计已记录） |

**实测：首轮对拍即全绿** —— mirror 与 backend 当前语义一致；今后两侧任何漂移都在各自 CI 立即变红，
修复必须跨语言同 commit（同源演化，禁止分叉）。backend 测试 1077 passed + 1 skipped（+1）。

## 2. 运动序列（Part 6「short animation captures where technically feasible」）

- 静态帧无法体现 motion character（R1 诚实边界）→ 本轮生成 **36s 运动序列拼图**
  （12 帧 × 3s，覆盖呼吸周期 4–6s + 轨道运动）：`qa/visual-review/rendered/sheets/motion_APP_<PROFILE>.png` ×7。
- 机器代理（`VisualReviewRenderTest.c_`）：全部 profile 6 秒内粒子真实位移 > 0.0005，
  且 7 用户位移幅度 ≥3 档 —— **motion personality 可测可见**。
- 画廊首页已收录运动序列。

## 3. Core Personal Reasoning Set（BATCH 2 §20 第 1 项）

- 从题库 CORE 43 条精选 **26 条**：`QaQuestionBank.CORE_PERSONAL_IDS / CORE_PERSONAL`（代码事实源）。
- 选择记录与逐条理由：`qa/reports/CORE_PERSONAL_REASONING_SET.md`（8 主题 × Day 30/90/180 验收映射）。
- 集合是「选择」，不是新 QA framework：无新基建、无新评分系统；变体不进集合。
- 后续 BATCH 2 项（真实回答四层人审 / Correction 闭环 / Grounding overreach）以此集合为优先对象。

## 4. 实测

- Android：feature:qa 全绿（含 QaPortraitMirrorGoldenTest 1/1 + VisualReviewRenderTest 3/3 + 既有全部）、
  feature:presence 全绿、detekt 全模块 PASS、lint PASS（全量套件跑完见最终计数）。
- backend：pytest 1077 passed + 1 skipped；ruff 0；README 测试计数一致性门禁同步更新（1077→1078 项）。

## 5. 下一轮

BATCH 2 第 2/3 项：对 Core Set 26 条跑真实回答链路并做 Answer Quality 四层人审 +
Correction → Future Reasoning 真实闭环验收（q038/q040/q042）。
