# Development Status（DEVELOPMENT_HEAD）

> ERA 39 §43。本文件描述 **Development HEAD**（当前 git main）的能力与状态，
> 与 Release Baseline（`docs/release/RELEASE_BASELINE.md`）严格区分：
> 这里的内容**尚未**经过新一轮 Release Closure，不得视为已发布。
> 每轮自主演进结束更新；禁止虚假完成状态。

## 1. 当前 HEAD

| 字段 | 值 |
|---|---|
| HEAD（本轮起点） | `b16fc1ce119beeaa75104a312ddc7194a2c48c98`（Product Quality Era R8 收官） |
| 相对 Release Baseline | +10 commits（v0.9.0 发布终检之后的 Product Quality Era R1–R8）+ ERA 31 R1 |
| 当前时代 | ERA 31 — Felt Product Reality（政策见 `docs/product/ERA31_FELT_PRODUCT_REALITY.md`） |
| 版本线 | v0.9.0（versionCode 6；下一 release 重新收口） |

## 2. 最新能力（DEVELOPMENT_HEAD）

- **Observation → Baseline → Presence → Journey 全链**：EchoRuntimeCoordinator（感知编排 +
  15min Presence 刷新 + 健康五态）、LocalBaselineCalculator、AmbientEngine、Identity Genome（七维 +
  identitySeed）、LifeSeasonTracker（hysteresis + transition 平滑）、DailyCompositionGate、MomentState、
  EchoVisualMapper（冻结映射链）、Wallpaper/Dream 渲染（不可见零渲染状态机）、EchoPresenceCodec v2（40 字段）。
- **Personal Reasoning**：QuestionClassifier 六分类、ContextRanker（tier 优先级 + task relevance）、
  Context Budget 三重上限、GroundingValidator（Evidence before interpretation + timeRange 导出）、
  EchoAnswer（依据双清单）、AiNarrativeService（瞬态重试 + 确定性降级链）、Narrative Distiller（Persona 稳定）。
- **Memory / Self Model**：rankMemories 检索、七 MemoryType 分层、decay/expiry/reinforce/derivePatterns
  Worker 序列、User truth > passive evidence、Correction 优先层级、What ECHO Knows 七层摘要。
- **Journey**：Canonical Daily State（v10 表 + 确定性重建）、Visual Memory River 五分类、
  Year View 四季聚合、Life Season × Journey 解释绑定、上下文例外时间定位。
- **UI 三世界**：ECHO / Journey / Me 根页面全部 state-in/event-out 可渲染测试；
  ECHO Scene 三层叙事（确定性 headline / AI 增量层 / facts 证据）。
- **隐私/安全/数据权利**：HKDF 密钥分离 + 迁移链 2→11、导出/删除五域对齐、DSR 证据链、审计链、
  TLS 私网判定修复、激活码防爆破、限流（红色信号豁免）。
- **模块**：9 个 Gradle module（core:model/ports/security + feature:observation/presence/journey/memory/
  intelligence/qa）+ :app；结构化 manual DI；编译期架构边界。

## 3. 当前 QA

- Android：**1002 unit tests**（app 850 / feature:intelligence 20 / feature:presence 21 / feature:qa 111；ERA 31 R5 实测全绿）+ lint（4 安全规则）+
  detekt 27 规则（本轮全模块 PASS）+ instrumentation 4 组（迁移链/设备锚点）+ Compose smoke 测试三世界全覆盖。
- feature:qa：7 profile 长期 fixture（Day 0–180）+ 视觉回归黄金集（42 帧哈希）+ 快照套件 + 产品审计报告。
- backend：pytest **1077 passed + 1 skipped**（含 mirror golden 漂移门）、ruff 0、mypy strict 0、uv.lock 冻结。
- 性能：PERFORMANCE_BASELINES 13 行预算（JVM 段）+ connected-test 设备锚点。
- CI 五套：android-ci / backend-ci / security-ci / source-integrity / release-closure（66 uses 全 SHA 锁定）。

## 4. Known Issues（如实记录）

- **无真机实拍**：Wallpaper 真机电池/帧率/进程死亡场景未在真机采集（BATCH 6 执行；本环境只有 JVM/模拟器锚点；
  清单 `qa/visual-review/DEVICE_CHECKLIST.md`）。
- **Release 缺口**：Development HEAD 未做新一轮 Release Closure（BATCH 8 收口）。
- **QA mirror 残余**：QaPortraitMirror / QaHeadlineEngine 与生产实现存在重复（见
  `qa/reports/QA_MIRROR_AUDIT.md`；跨语言黄金门 + BATCH 7 下沉为待办）。
- **人眼评审待确认**：机器代理全 PASS，但画廊的人眼结论（不同用户/连续性/壁纸生命感）需人打开
  `qa/visual-review/index.html` 作答（`qa/reports/ERA31_VISUAL_REVIEW_R1.md` §6）。
- **动画捕获未做**：静态帧无法体现 motion character；短动画捕获列后续轮次（技术可行时）。
- 外部门（真机矩阵/责任矩阵/临床/法务/渗透/值班演练）见 `docs/release/RELEASE_READINESS.md`，不由代码生成替代。

## 5. Next Product Slice（当前执行）

**BATCH 1 ✅ 完成（R1+R2）**：架构冻结落地 / Dev-Release 区分 / QA mirror 审计 / 真实 Render Review 画廊 /
两个真实视觉缺陷修复 / Scene 信息密度修复 / 运动序列捕获 / 跨语言黄金门。

**BATCH 2 ✅ 完成（§20-25）**：

1. ✅ Core Personal Reasoning Set 26 条。
2. ✅ 真实回答四层人审（26/26 覆盖 + 8+ A-D 缺陷修复）。
3. ✅ Correction → Future Reasoning 闭环（检索级 + 回答级双闭环）。
4. ✅ Context retrieval 用户自述优先（上下文窗口 label 化）。
5. ✅ Grounding overreach 门禁：26×3 回答强制词表 + 监视语言双门禁（确定性层）。
6. ⏸ Narrative Distiller / Provider persona stability——Provider 路径需真实 key，列 dogfood 轮（BATCH 6）。

**BATCH 3 ✅ 收口（§26-29）**：

1. ✅ Self Model value audit——interactionPreferences 死数据已删。
2. ✅ Pattern contradiction 用户可见验收（OUTDATED →「有些出入」，不再当当前事实）。
3. ✅ Memory consolidation 决策：暂不接线（decay/expiry/purge 已限增长；触发条件 = dogfood 实测记忆成为真实问题）。
4. ⏭ What ECHO Knows 人类语言复核 + edit / forget / confirm UX（列 BATCH 6 dogfood 轮并行推进）。

**BATCH 4 进行中（§30-34）**：

1. ✅ Journey 第一视觉修复：免责文案移到页底，打开 Journey = 视觉记忆河流第一眼。
2. ⏭ Significant Change 精简复核。
3. ⏭ Landmark quality 复核。
4. ⏭ Month/Season/Year visual story 复审。
5. ⏭ 删除无意义自动总结（如有）。
