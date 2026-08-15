# ERA 32 R02 — Personal Reasoning 答案复核轮（Batch C 起步）

> 2026-08-15。Batch C 第 1–6 项执行：对 PersonalAnswerEngine 26/26 捕获回答做
> FACT/CONTEXT/VALUE/VOICE 四层复核（`qa/reports/personal_answer_review/answers.md` 为 production
> 引擎实际输出），发现并修复三个真实缺陷。无新 feature、无新 QA taxonomy。

## 1. 发现与修复（均为真实产品缺陷 → 修复 → regression，符合 QA 冻结顺序）

| # | 缺陷类别 | 问题 | 修复 |
|---|---|---|---|
| D1 | REASONING_DEFECT（答非所问，P1 错误理解） | q032「和上个月比，我这个月更规律了吗？」错挂 STABILITY 单窗口族——回答「最近两周算稳定」，答的不是问题问的 | 新增 `STABILITY_MONTH_COMPARE` 族（族数 15→16）：本月 vs 上月「接近通常」占比对比（阈值 0.1），证据给两个月具体占比（如「这个月 21/28 天 · 上个月 26/28 天」） |
| D2 | TRUST_DEFECT（工程值泄漏） | q025/q028 相似日证据泄漏「z 距离 4.79」——用户不可解读（同类：R25 whyToday z 分数、R40 QA 快照） | 证据改「更接近今天 → 稍远：日期序列」；全仓 z 距离 0 残留 |
| D3 | VALUE_DEFECT（结论不可验证） | q006 半年 / q013·q014·q017 月间 / q007·q009·q010 周间的「没有明显差别」分支只给结论句 | 无变化分支也给出两端实际数字（如「活跃起点 08:49 → 08:51 · 屏幕 254 → 251 分钟（半年差都在日常波动内）」） |
| D4 | CONTEXT（文案硬编码） | q001/q004 漂移文案硬编码「最近这一个月…比前一个月」，与 90d/60d 窗口不符 | 改窗口无关措辞「和前半段相比，你最近开始得明显更晚」 |

## 2. 回归与证据

- `PersonalAnswerEngineTest` +5：月间规律对比（含不足两个月诚实分支）/ z 距离负向锁 /
  半年数字 / 月间数字 / 周间数字。
- `QaQuestionBank` 期望证据行修正：q006 90d→180d、q032 90d→56d（QA 元数据与引擎实现对齐）。
- `answers.md` 与 7 profile 快照确定性重生成（diff 仅上述四类，无意外漂移）。
- 实测：Android 全模块 `testDebugUnitTest` 全绿；`PersonalAnswerReviewHarnessTest` 26×3
  grounding 双门禁保持 PASS（新增文案未触碰监视词表）。

## 3. 复核结论（其余 23 条）

q001/q004/q005（数字+阈值）、q015（差多少都给数字）、q019/q021/q023（周末双线数字）、
q029/q031（稳定性+漂移补充）、q033/q035/q037（whyToday 人话维度）、q038（出差上下文标签流畅）、
q040/q043（零记录诚实）、q042（确认对拍、User truth 优先）——本轮复核通过，未发现新缺陷。

## 4. 结构与 Provider 结论（Batch C 第 6/7/8 项）

- **结构**：族数 16，距拆分触发线（≥20）仍远；`PERSONAL_ANSWER_ENGINE_AUDIT.md` §5 记录本轮增量。
- **Provider fallback**：无 Provider / Provider 失败 → 确定性引擎路径（`AiNarrativeServiceTest`
  noProviderWithDeterministicHook / providerFailureFallsBackToDeterministicPersonalAnswer 双锁）本轮复验通过。
- **Narrative stability**：26×3 回答词表门禁保持全绿；快照 7 profile 重生成即本次改动本身，无叙事漂移。

## 5. 下一轮

Batch C 余项（Context retrieval 深度走查 + Correction reuse 全链复验）→ 或按真实 dogfood 数据回流
顺序切换 Batch D。真机 Batch B 仍按 §9 协议待命。
