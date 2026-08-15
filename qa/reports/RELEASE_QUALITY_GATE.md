# Release Quality Gate（ERA 29 §66 前置 · Batch 8 收官）

> 产品发布前的质量门 = 本仓库可测部分的全部量化门 + 外部发布门（如实保持外部门）。
> 每轮大改必须过以下检查；任何一条失败即阻断。

## A. 产品质量门（PRODUCT QUALITY GATES）

| 门 | 量化锚点 | 状态 |
|---|---|---|
| ECHO：第一眼是 ECHO 不是卡片 | ECHO Scene 审计（视觉主体第一项 + 去卡化清单） | ✅ |
| Presence：不同用户够不同 | 100-seed 两两距离 ≥0.03；纯色相 <0.005 → 非仅颜色 | ✅ |
| Presence：同一用户长期够像 | 同用户 Day1→180 距离 <0.05；跨用户 >2× 同用户 | ✅ |
| AI：只有这个人的 ECHO 能回答 | 115 问题题库 + 分类 94.8% + Recall 全 1.0 + persona 6 风格收敛 | ✅ |
| Memory：纠正后未来行为真的变 | Correction Reuse 端到端（纠正前后编译上下文对比）+ 纵向任务 USER_CORRECTIONS 修复 | ✅ |
| Journey：三个月后有值得看的 | 期间故事/年故事/地标 fixture 锚定 + 河流 DRIFT 修复 | ✅ |
| Me：知道 ECHO 知道什么、使用什么 | What ECHO Knows 自然语言 + 依据双清单 + Provider Advanced | ✅ |
| Wallpaper：愿意长期留着 | 7 天模拟（熄屏 0 帧/节流/静止适应/身份色恒定） | ✅ |

## B. 质量指标（QUALITY METRICS）

| 指标 | 可测代理值 | 状态 |
|---|---|---|
| Day-7 Baseline Completion | 日历成熟度 Day7=KNOWN（修复 weekday 桶 MATURE 不可达） | ✅ |
| Personal Question Grounding Rate | Grounding claim-evidence 矩阵 + Evidence exists | ✅ |
| Context Retrieval Recall | Recall@5=1.0 / Correction=1.0 / ContextException=1.0 / UserConfirmed=1.0 | ✅ |
| Correction Reuse Rate | 纠正后 top1 纠正证据 + 编译上下文含用户原话 | ✅ |
| Memory Confirmation Rate | 确认/忘记/编辑五权 UI 在位（smoke） | ✅ |
| False Interpretation Rate | z 爆炸修复 + DRIFT 误报修复 + 情绪断言禁词 0 命中 | ✅ |
| Battery Cost（代理） | Wallpaper 7 天模拟 0 无效帧 + 快照重读 ≤1/s + 10k 排序预算 | ✅ |
| Lock-safe Privacy Violations | lock-safe 无 narrative 字段 + knowsLines 无敏感纠正 | ✅ 0 |

## C. 工程门（每轮维持）

- backend pytest：1076 passed（R1 全量后无后端变更）
- Android 单元：app 844 + qa 92 + presence 16 全绿
- detekt：全模块干净
- 架构边界：core:ports 不再依赖 feature:memory；presence 渲染不依赖 Room
- 迁移安全：Room 11 级迁移链测试全绿（单元 + instrumented 编译）
- 隐私：敏感纠正默认 SENSITIVE；公开叙事过滤；原始通知/音频/麦克风永不进入上下文

## D. 外部发布门（如实保持外部门，本仓库不伪造）

真实设备 ≥8 台回归 · 责任矩阵 · 临床签署 · 法务定稿 · 外部渗透 · 值班演练 ·
生产域 · 伦理审查 —— 见 RELEASE_READINESS 移交包（ADR-072）。
