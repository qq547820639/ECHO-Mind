# ERA 32 R03 — Context / Correction Loop 深度走查（Batch C 余项）

> 2026-08-15。Batch C 第 3–5 项执行：Context retrieval 与 Correction reuse 全链深度走查
> （EchoCorrectionService → CONTEXT 记忆 → DeterministicPersonalAnswerProvider →
> PersonalAnswerEngine.travelContext；AI Provider 路径 EvidenceAssembler →
> EchoContextRetriever → ContextRanker → EchoContextCompiler）。
> 发现并修复两个真实缺陷；无新 feature、无新 taxonomy。

## 1. 缺陷与修复

| # | 类别 | 问题 | 修复 |
|---|---|---|---|
| F1 | REASONING_DEFECT（P1 错误理解：旧上下文被当现在） | 生产 CONTEXT 记忆只带开始日期，provider 把窗口 `toDay` 恒设为今天 → 上下文永远「进行中」。三个月前的出差仍被回答「这几天在出差的窗口里」；「已经结束了」分支在生产路径不可达 | `PersonalAnswerEngine.travelContext` 增加活跃窗口上限（14 天）：`dayIndex - fromDay > 14` → 已结束分支。证据只陈述已知事实——有明确结束日显示区间，没有则显示「从 X 开始（已过去 N 天）」，**不编造结束日** |
| F2 | TRUST_DEFECT（内部格式进 AI 上下文） | R37 只把确定性路径的纠正/确认内容人话化；AI Provider 路径（EvidenceAssembler.fromMemories）仍把「画像反馈：不太像（原因：旅行）（原判断：…）」「问答反馈：像我（问：…）」「特殊时期：旅行@…」整串内部格式发给 Provider | 新增 `feature:memory/MemoryHumanizer`（单一事实源）：纠正/确认/上下文三格式人话化；EvidenceAssembler 与 DeterministicPersonalAnswerProvider 同源调用（删除 provider 内的第二份实现） |

## 2. 走查通过面（无需改动）

- **EchoCorrectionService（R18 桥梁）**：上下文类原因（工作/旅行/假期/身体不舒服/特殊事件）→
  CORRECTION + CONTEXT 双写；3 天同 kind 去重；confidence=1 用户自述最高置信。✅
- **ContextRanker 优先级（§30）**：USER_CORRECTIONS(5) > CONTEXT_EXCEPTIONS(4) >
  PREFERENCES/BASELINE(3，USER_CONFIRMED 映射同源) > PORTRAIT_HISTORY/TODAY(2)；
  task affinity 0.5 封顶不跨 tier。✅
- **EchoContextCompiler**：禁止数据剔除 → ranking → 记忆先行占预算（R22 修复，观察证据挤不掉用户自述）→
  三重上限 → 结构化事实编译；原始通知/音频/麦克风硬禁止。✅
- **EvidenceAssembler**：TODAY_AGGREGATE/BASELINE/PORTRAIT_HISTORY 映射、敏感度标记正确。✅
- **provider 桥**：181 天窗口化加载、CONTEXT 标签 kind 化（R18）、corrections/confirmed 人话化。✅

## 3. 回归

- `PersonalAnswerEngineTest`：travel 活跃窗口测试改为窗口内场景（36..39）；新增
  `travelContextOldContextIsTreatedAsEnded`（60 天前上下文 → 已结束 + 证据含「已过去」且不编造结束日）。
- 新增 `EvidenceAssemblerHumanizeTest`（feature:intelligence，+3）：纠正/确认/上下文内部格式
  负向锁 + 人话正向锚点。
- `DeterministicPersonalAnswerProvider` 删除私有 humanize 副本（单一事实源）。
- QA 快照确定性重生成：仅 `PROFILE_E_PROJECT_CRUNCH` 一处变化——30 天前的项目冲刺上下文
  从「这几天在窗口里」正确翻转为「已经结束了（已过去 30 天）」；其余 6 profile 零漂移。
- 实测：Android 全模块单测 + detekt + lint 全绿（见 STATUS.md §3 自动数字）。

## 4. 下一轮

Batch C 全部项完成（Core Set 冻结 ✅ / 答案复核 ✅ / Context ✅ / Correction reuse ✅ /
false interpretation 门禁 ✅ / 结构拆分判断 ✅ / Provider fallback ✅ / Narrative stability ✅）。
下一轮按优先级：Batch G Delete Review 预检（低价值 settings/reports 清理）或直接评估
Batch H Release Candidate 条件（真实体验升级证据）。
