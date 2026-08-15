# Development Status（DEVELOPMENT_HEAD）

> ERA 39 §43。本文件描述 **Development HEAD**（当前 git main）的能力与状态，
> 与 Release Baseline（`docs/release/RELEASE_BASELINE.md`）严格区分：
> 这里的内容**尚未**经过新一轮 Release Closure，不得视为已发布。
> 每轮自主演进结束更新；禁止虚假完成状态。

## 1. 当前 HEAD

| 字段 | 值 |
|---|---|
| HEAD（本轮起点） | `90fac1d`（ERA 31 R43 收口） |
| 相对 Release Baseline | +35 commits（v0.10.0 Release Baseline 之后 R11–R44，含本提交） |
| 当前时代 | ERA 31 — Felt Product Reality（政策见 `docs/product/ERA31_FELT_PRODUCT_REALITY.md`） |
| 版本线 | v0.10.0（versionCode 7；下一 release 重新收口） |

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

- Android：**1032 unit tests**（app 872 / feature:intelligence 23 / feature:presence 25 / feature:qa 112；ERA 31 R37 实测全绿）+ lint（4 安全规则）+
  detekt 27 规则（本轮全模块 PASS）+ instrumentation 4 组（迁移链/设备锚点）+ Compose smoke 测试三世界全覆盖。
- feature:qa：7 profile 长期 fixture（Day 0–180）+ 视觉回归黄金集（42 帧哈希）+ 快照套件 + 产品审计报告。
- backend：pytest **1077 passed + 1 skipped**（含 mirror golden 漂移门）、ruff 0、mypy strict 0、uv.lock 冻结。
- 性能：PERFORMANCE_BASELINES 13 行预算（JVM 段）+ connected-test 设备锚点。
- CI 五套：android-ci / backend-ci / security-ci / source-integrity / release-closure（66 uses 全 SHA 锁定）。

## 4. Known Issues（如实记录）

- **无真机实拍**：Wallpaper 真机电池/帧率/进程死亡场景未在真机采集（BATCH 6 执行；本环境只有 JVM/模拟器锚点；
  清单 `qa/visual-review/DEVICE_CHECKLIST.md`）。R16 已修复进程死亡的软件侧锚点（快照 commit 落盘 +
  `EchoPresenceSnapshotRecoveryTest` 写/读进程分离回归），真机进程死亡实测仍归外部门。
- **Release 缺口**：v0.10.0 Release Closure 已完成（R9/R10）；Development HEAD（R11–R16 打磨轮）未做新一轮
  Release Closure，待下一 release 收口。
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

**BATCH 4 ✅ 收口（§30-34）**：

1. ✅ Journey 第一视觉修复（免责文案移页底）。
2. ✅ Significant Change 复核：领域层已达标（阈值/置信度/上下文标注/情感词守卫），无需改动。
3. ✅ Landmark quality：发现 buildLandmarks 生产零消费 → YEAR 尺度装配 + 「时间地标」UI（§33 四类）。
4. ✅ Year View 复审：Visual > Narrative 已达标；journeyYearStory 保持 QA 用途（不增加 narrative 展示面）。
5. ✅ 无意义自动总结：未发现（叙事降级链已克制）。

**BATCH 5 ✅ 收口（§47-50）**：

1. ✅ Onboarding 审计（North Star 达标 / AI·Mic 后置 / 授权完成不停留）。
2. ✅ Time-to-ECHO 指标（六段分解 + 苏醒时长预算）。
3. ✅ §51 首帧不等待审计。
4. ✅ §48 权限文案三问复核（已达标，不改动）。

**BATCH 6 准备中（§35-38，本环境无真机）**：

1. ✅ Dogfood 协议增补：六类缺陷分类 + Why accuracy / 纠正复用 / 壁纸留存意愿 / Journey 有用性记录项。
2. ✅ `scripts/collect_wallpaper_metrics.sh`（真机 CPU/mem/帧/battery 采样，部署侧执行）。
3. ⏳ 30 天 dogfood + 真机 Wallpaper battery：外部门执行（协议/清单/脚本已就绪）。

**BATCH 7 ✅ 收口（§39-41）**：

1. ✅ Delete Audit：核心 UI 已干净；临床量表移交外部门复核。
2. ✅ Subscription gating 审计（符合 §40）。
3. ✅ QA mirror cleanup 收尾（三项 mirror 全部闭环）。
4. ✅ core:ports dependency cleanup（ERA 40 §46 收官）：EchoPresenceState/SensingRuntimeStatus 下沉 core:model，
   core 不再依赖任何 feature；模块化工作停止（§46 约定）。
5. ✅ App/UI complexity cleanup：无 God Screen 发现（三大世界根页面均已 state-in/event-out 可测）。

**BATCH 8 ✅ 完成（v0.10.0 Release Closure）**：

1. ✅ 版本收口 v0.10.0（versionCode 7；一致性门禁 6/6）。
2. ✅ LOCAL PREFLIGHT PASSED + assembleRelease（-PECHO_GIT_COMMIT 钉定）+ 签名 v2,v3。
3. ✅ 发布链全量重生成：SOURCE_MANIFEST 1019 / 确定性归档 / SBOM 80 / provenance（release）/
   artifact manifest / final package §18 终态门禁 PASS / test_release_set 6/6。
4. ✅ LAST_RELEASE_BASELINE 更新为 6e84086；RELEASE_NOTES_v0.10.0。
5. ⏳ Device smoke test + 生产签名 + 30 天 dogfood：外部门执行（协议/清单/脚本就绪）。

**ERA 31 全部 Batch 1-8 完成。v0.10.0 后产品主链打磨轮（R11–R44）✅**：
R11 Headline 自然句优先 + What ECHO Knows 人类语言 · R12 ECHO Scene 全链路走查（日期降噪）·
R13/R14 Wallpaper/Dream 自适应帧率（§16 电池现实）· R15 Why 层安静化 · R16 进程死亡恢复锚点
（快照 commit 落盘 + 写/读进程分离回归——重启后同一个 ECHO）· R17 Ask ECHO 免费用户走查
（AI 催促清零 + 问法归一，界面建议的问题离线可答）· R18 §22 Correction Reuse 桥梁
（上下文类纠正 → CONTEXT 记忆 → 未来回答自然融入，不再只剩机械回放）· R19 Journey 第一眼走查
（免费用户叙事说人话 + DAY 河流锚定真实数据）· R20 Wallpaper 运动现实检查
（4fps 静态期实测=缓慢呼吸非跳帧 + 真实帧率人眼证据入画廊）· R21 What ECHO Knows 10 秒可读
（chip 与分组行同源同一词表，人称方向统一）· R22 Day-0 苏醒
（identitySeed 派生的真实 ECHO 第一次呼吸，占位圆退役）· R23 Day-7 Why 可懂
（开始活跃的「变化」直接说晚/早 N 分钟，镜像两端同改）· R24 Ask ECHO 依据诚实化
（纠正/上下文/确认回答如实标注来源，不再一律「历史画像」）· R25 Ask ECHO 证据说人话
（z 分数与工程键退役，维度中文词表）· R26 Me 检查台走查
（麦克风双控制去重，控制权唯一）· R27 Journey 一条河流
（河段故事并入主河流，第二条河流行退役）· R28 Scene 早期状态去仪表化
（基线进度条与覆盖率条退役，「它在记录」由事实句承担）· R29 Scene Action 安静化
（按钮墙折叠成单一「想做点什么？」入口，订阅槽位不再常驻）· R30 文案一致性收口
（引号规范统一 + 「重新生成」→「重新看看今天」）· R31 苏醒 = 第一次 Presence
（Day-0 SEED 单一构建点，苏醒与运行时 identity 逐字段一致）· R32 关键验收契约锁
（纠正复用 production 全链 + UI 建议必须离线可答）· R33 Release Integrity 复核
（LOCAL PREFLIGHT 全绿 + Baseline 文档对齐）· R34 Wallpaper Adoption
（Scene 一次性壁纸引导，采纳入口从 Me 深处浮到第一屏）· R35 Dogfood 交接刷新
（真机清单对齐当前 UX：fresh install 全链 + R16/R20 实测预期）· R36 验收证据总表
（十道门 × 五时点 → 轮次与证据，见 `qa/reports/ERA31_FELT_ACCEPTANCE.md`）·
R37 纠正/确认回放说人话（记忆内部格式退役）· R38 上下文回答语句流畅化
（「出差的这几天」不再被空格切断）· R39 AI 设置标签中文化
（Current provider/Model/Status/Advanced → 中文，协议专名保留）· R40 QA 镜像快照同口径
（产品预览的 z 分数泄漏清零，全仓缺陷类别终扫完成）· R41 Release Build Readiness
（assembleRelease + R8 全量通过，dev head 可发布）· R42 CI 完整性审计
（契约锚点修复 + SOURCE_MANIFEST 1064 重生成，五套 workflow 全绿恢复）·
R43 source-integrity 全量重放（reality/依赖图漂移门 + 归档双格式验证 + 10/10，
CI 生成物目录入 .gitignore）· R44 源码事实扫描假阳性根因
（KDoc/fun 粘连修复，unresolved 归零）。

**下一阶段（真实 dogfood 数据回流后）**：
- BATCH 6 缺陷回流 → fixture 化 → 修复 → 回归；
- Affective 评估（ERA 30 前置满足后）；Production 签名与设备矩阵。
