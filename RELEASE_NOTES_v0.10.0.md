# RELEASE_NOTES v0.10.0 — ERA 31 Felt Product Reality

> 发布线：v0.10.0（versionCode 7）。上一 Release Baseline：v0.9.0（commit `5783036`）。
> 本版本是 ERA 31「Felt Product Reality」的首次 Release Closure：核心问题从「补全架构」
> 切换为「用户是否真的感觉：我的手机懂我 / 这是我的 ECHO」。

## 产品变化（用户可感知）

### Ask ECHO 不再需要 AI Provider

- 新增 **PersonalAnswerEngine**（确定性个人回答引擎，13 个回答族、27 条问法）：
  「最近是不是越来越晚 / 晚上结束时间趋势 / 周末和平时 / 最像今天 / 稳定了吗 /
  月对比 / 屏幕差多少 / 这周为什么碎 / 今天为什么不一样 / 今天为什么碎 /
  出差影响 / 纠正了什么 / 确认过什么 / 半年变化」。
- 无 Provider / 离线 / Provider 失败三条路径都会先由引擎回答；**Core Set 26/26 覆盖**，
  证据先行、数据不足诚实、中性词表、不认识的问题诚实交回 AI。
- 用户自述优先：出差/冲刺上下文（用户告诉 ECHO 的特殊时期）直接进入回答；
  被动证据不投票击败用户确认/纠正（§28）。

### ECHO 视觉：身份在结构里，不只是颜色

- 修复两个真实视觉缺陷（Real Render Review 发现）：
  1. 粒子场从未存在——sceneRandom 丢失 index 熵，所有粒子堆叠一点；已修复，粒子真实散布。
  2. 身份差异几乎只靠颜色——纹理族（柔光/微粒/流线/环晕）、轨道几何（环状↔弥散）、
     结构环、对比度进入帧模型；两个用户不看名字明显不同，同一个 ECHO 跨天颜色恒同。
- 真实渲染画廊：`qa/visual-review/`（210 帧 PNG + 42 快照 + 41 拼图 + 运动序列 + 画廊首页）。

### Journey：看见自己的时间

- 打开 Journey 第一眼 = 视觉记忆河流（免责文案移页底）。
- **时间地标**上线（YEAR 视图）：基线成熟 / 明显变化 / 特殊时期 / 用户确认阶段——
  值得回看的时间有了锚点。
- ECHO 忘掉过去的我（OUTDATED 模式在 What ECHO Knows 显示「有些出入」）。

### 其他

- ECHO Scene 更安静：Journey/问 ECHO 入口 TextButton 化、Why 证据行与 AI 提示去卡化。
- Time-to-ECHO 指标建立（机器段 ≈2.21s）；苏醒时长预算回归。
- 交互偏好并入偏好展示（删除无消费方数据）。

## 工程与质量

- **core 不再依赖任何 feature**（EchoPresenceState/SensingRuntimeStatus 下沉 core:model）；
  模块化工作停止（§46）。
- QaPortraitMirror 跨语言黄金门（backend golden ↔ Android 对拍双 CI 门禁）；
  QA mirror 三项全部收敛（PortraitMirror 黄金门 / Headline 单点 / AskEcho 薄适配器）。
- 测试：Android **1004 全绿**（app 852 / intelligence 20 / presence 21 / qa 111）+
  lint + detekt；backend 1077 passed + 1 skipped；ruff/mypy 0。
- 冻结不变：Affective OFF（契约冻结）；隐私/加密/迁移链无变更。

## 已知边界（如实）

- 真机 Wallpaper 电池/帧率：采集脚本与清单已就绪，实测数据待部署侧执行（dogfood）。
- Provider persona stability：需真实 key 验证，列 dogfood。
- 本 Release Closure 在本地环境执行：APK 为 unsigned（签名由运营签名环境执行，
  provenance 如实记录 signing_stage）。
