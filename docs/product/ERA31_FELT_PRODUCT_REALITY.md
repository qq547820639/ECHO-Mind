# ERA 31 — Felt Product Reality（产品实施宪章）

> 自 ERA 31 起生效，取代「补全架构 / 扩 QA / 加 domain model」为第一目标。
> 唯一核心问题：用户实际使用时，是否真的能感觉 **「我的手机懂我」**，最终 **「这是我的 ECHO」**。
> 架构、QA、测试、指标都只是实现该目标的手段。

## 1. 北星

ECHO 不是 AI Dashboard / Mood Tracker / ChatGPT Wrapper / Live Wallpaper App /
Mental-health Detector / Habit Gamification App / Analytics Platform / QA Framework。

ECHO 是 **Personal Ambient Intelligence**：

```
REAL LIFE → Observation → Personal Baseline → Context → EchoSelfModel
→ Memory → Personal Intelligence → Presence → ECHO
```

产品顺序恒为：Ambient → Understand → Explain → Converse → Act → Intervene。

默认行为：**安静存在**。不是主动找用户。

## 2. 新开发门（NEW DEVELOPMENT GATE）

任何新增任务必须至少明确改善以下一项，否则默认不实施：

1. 用户能否感觉 ECHO 更懂自己；
2. ECHO 是否更自然地存在于手机中；
3. 长期使用后 ECHO 是否更像「同一个 ECHO 在成长」；
4. 用户纠正后，未来理解是否明显改变；
5. Ask ECHO 是否能回答只有该用户才有的个人问题；
6. Journey 是否让用户看到「自己的时间」；
7. 用户是否更清楚 ECHO 知道什么、不知道什么；
8. Wallpaper 是否值得长期保留；
9. 产品是否更安静而不是更吵；
10. 信任、隐私或用户控制是否增强。

## 3. 架构冻结（ARCHITECTURE FREEZE）

不是完全禁止架构改动，而是：**只有架构问题直接阻碍真实产品体验、可靠性、安全或维护时，才继续重构。**

暂时禁止：

- 为了纯洁继续增加 module；
- 为了 pattern 增加抽象；
- 为了 QA 再创建另一套 framework；
- 为了所谓未来扩展建立大量 interface；
- 为了覆盖 synthetic fixture 添加特殊 rule；
- 为了「architecture score」进行无用户价值的迁移。

当前允许的架构整理仅包括：

- core:ports → feature:presence 的剩余依赖倒置（ERA 40 §46，完成后停止模块化工作）；
- QA 与 production algorithm 的重复实现收敛（ERA 38 §41）；
- 明显 God Screen/ViewModel 的收敛；
- Development HEAD 与 Release Baseline 的区分（ERA 39）；
- 真实阻碍产品质量的结构问题。

## 4. QA 原则（QA PRINCIPLE CHANGE）

**不新增 QA，除非 QA 对应一个真实产品问题。**

正确顺序：

```
真实体验问题 → 复现 → Fixture / Regression → 修复 → 回归
```

禁止顺序：

```
想到一个指标 → 创建测试 → 为了通过测试修改产品
```

Synthetic QA 是安全网，不是 Product Truth。PROFILE_A–G 只能作为 baseline；
禁止为某 fixture 增加 user-specific special case；只对某 synthetic user 有意义的规则删除。

## 5. 视觉评审原则（REAL RENDER FIRST）

- 不再只看参数和 Markdown report，必须实际 Render 并用人眼看。
- 渲染面：APP / HOME_WALLPAPER / LOCK_SAFE / DREAM / CANONICAL_JOURNEY。
- 每个 Profile 输出 Day 0 / 7 / 28 / 90 / 180。
- 工件：rendered PNG golden + 参数快照 + 状态解释，落在 `qa/visual-review/`。
- 该目录目的是**让人直接看到最终视觉**，不允许发展成巨大 QA 系统。

### 人眼必答题（HUMAN VISUAL QUESTIONS）

1. 两个不同用户的 ECHO，不看名字，是否明显不同？
2. 同一用户 Day 7 / 90 / 180，是否明显是同一个 ECHO 在成长？
3. Wallpaper：看 5 秒是否有生命感？看 1 小时是否不烦？看 7 天是否仍值得保留？

如果 mathematical identityDistance PASS 但人眼答案是「不太看得出区别」→ **Product FAIL**。

### 动效性格（MOTION CHARACTER）

ECHO 动起来不能像 particle demo / music visualizer / game effect。
目标：缓慢、有机、克制、可长期看。
不同 Identity 的差异重点来自 motion personality / topology / orbit / texture / structure，**不是换颜色**。
Life Season 不出现「系统说我进入某个阶段」——让 ECHO 自己慢慢变化，需要解释时才在 Journey 中揭示。

## 6. 主要指标（PRIMARY METRICS）

- Time to first ECHO
- Day-7 baseline completion
- Wallpaper adoption / day-7 retention
- Correction reuse rate
- Grounded personal answer rate
- False personal interpretation rate
- 「What ECHO Knows」comprehension
- Journey return after 30/90d
- Battery impact
- Crash-free Presence runtime

内部最重要的定性指标：**「Would I keep this ECHO?」**

## 7. 执行批次（EXECUTION BATCHES）

- **BATCH 1**：冻结落地；Dev/Release 区分；QA 镜像审计；真实 Render Review（Day 0–180 画面、
  双用户差异、同用户连续性）；真机 Wallpaper 清单；从真实画面修第一批视觉/交互问题；ECHO Scene 信息密度复审。
- **BATCH 2**：Core Personal Question Set 20–30 条；回答人类质量 review；Correction → Future Reasoning 闭环；
  Context retrieval 用户自述优先；Grounding overreach 检查；Narrative Distiller；Provider persona stability。
- **BATCH 3**：Self Model value audit（无消费方则删除/降级）；Pattern contradiction；Memory consolidation；
  What ECHO Knows 人类语言；用户 edit / forget / confirm UX。
- **BATCH 4**：Journey visual-first；Significant Change 精简；Landmark quality；Month/Season/Year visual story；
  删除无意义自动总结。
- **BATCH 5**：Onboarding 简化（尽快让 ECHO 苏醒；AI/Mic 后置）；Time-to-ECHO；授权完成自动进入 Awakening。
- **BATCH 6**：30-day dogfood；真机 Wallpaper battery；真实 reasoning defects fixture 化；修复；回归。
- **BATCH 7**：删除旧产品遗产；Subscription gating audit；QA mirror cleanup；core:ports dependency cleanup；UI 复杂度清理。
- **BATCH 8**：Product Quality release candidate；全量测试；Device smoke；Source manifest；Provenance；最终包验证。

## 8. 最终验收（FINAL ACCEPTANCE）

- **Day 0**：用户授权后没有停在授权页；ECHO 苏醒，还没有 baseline，但 ECHO 已经活着。
- **Day 7**：ECHO 开始形成「通常的我」；视觉比 Day 0 更稳定；第一次点 Why 能理解 ECHO 为什么变化。
- **Day 30**：Wallpaper 仍留在桌面——是愿意保留，不是忘记关；「最近是不是越来越晚？」的回答确实来自自己的 30 天。
- **Day 90**：纠正过的事情未来真的被使用；Journey 有几段值得看的时间；ECHO 和记忆都在成长。
- **Day 180**：两个半年用户并排，两个 ECHO 明显不同；同一用户 Day 7/90/180 并排，明显是同一个 ECHO；
  「这半年我有什么变化？」的答案不能换一个随机用户也成立。

最终北星测试：如果用户换手机上的普通 AI App 觉得「都差不多」，ECHO 还没有完成。
只有用户觉得「别的 AI 不知道这些，也不是我这个 ECHO」，才算成功。

## 9. 实施状态锚点（2026-08-15 更新）

- **Batches 1–8 全部完成**（v0.10.0 Release Closure 全绿，LAST_RELEASE_BASELINE=6e84086）；
- **v0.10.0 后产品主链打磨轮 R11–R46 完成**——十道门 × 五时点逐条映射到交付轮次
  与证据，见 `qa/reports/ERA31_FELT_ACCEPTANCE.md`（机器测试 / 人眼画廊
  `qa/visual-review/index.html` / 真机清单 `qa/visual-review/DEVICE_CHECKLIST.md`）；
- **Development Head 发布就绪**：见 `docs/STATUS.md` 与
  `qa/reports/ERA31_ROUND46.md`（下一轮 Release Closure 的版本号决策留人工）；
- **外部待办**（本环境不可执行，部署侧）：30 天 dogfood（`qa/DOGFOOD_PROTOCOL.md`）、
  真机壁纸电池采集（DEVICE_CHECKLIST）、生产签名与设备矩阵。
