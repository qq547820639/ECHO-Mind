# ERA 31 Round 7 报告（BATCH 5 收尾 / BATCH 6 准备 / BATCH 7 起步）

> 日期：2026-08-15。

## 1. BATCH 5 §48 — 权限文案审计

- CORE_SENSING 三行能力文案逐行对照「为什么需要 / ECHO 得到什么 / 可以随时关闭」三问：
  - 运动传感器行：「用于了解移动与作息节奏」（得到什么）+「无需系统权限」（无需关闭路径）✓
  - 屏幕状态行：「用于了解屏幕使用分布…无需额外权限」✓
  - 持续运行通知行：「让你随时看到 ECHO 正在工作」（为什么）+「拒绝后了解仍会继续」（随时关闭）✓
- 结论：已达标，不改动（契约锚点文案稳定 > 措辞打磨）。

## 2. BATCH 6 准备（本环境无真机，交付为部署侧可执行件）

- `qa/DOGFOOD_PROTOCOL.md` 增补：**六类缺陷分类**（PRODUCT/REASONING/VISUAL/TRUST/PERFORMANCE/
  ANDROID_RUNTIME_DEFECT，§36 不建几十个 taxonomy）+ 缺失记录项（Why accuracy / correction reused /
  wallpaper desire-to-keep / Journey usefulness）；
- `qa/reports/dogfood/DAY_TEMPLATE.md` 同步新字段；
- `scripts/collect_wallpaper_metrics.sh`：真机 Wallpaper 指标采样（CPU/mem 周期采样 + gfxinfo +
  batterystats + 电量基线），对应 DEVICE_CHECKLIST §3/§4 归档流程。

## 3. BATCH 7 §39/§40 — Delete Audit + Subscription gating

`qa/reports/DELETE_AUDIT.md` 逐项结论：

- **无删除**：核心 UI 已干净——Skills = Action 系统（保留）；Subscription 是 Me 内 section（保留）；
  Support/Safety = 冻结安全契约（保留）；问卷/日记/打卡 UI 残留 = 无（仅历史注释）；
  settings/diagnostics 无重复。
- **临床量表（GAD-7/PHQ-9/practices）**：属冻结安全契约（危机 L0 路径），不擅自删——移交外部门复核
  （已在 RELEASE_READINESS 外部门清单）。
- **§40 gating 审计通过**：订阅只 gate 云端同步+专业支持；Observation/Baseline/Presence/Basic Journey/
  Basic Memory/Basic Actions/确定性个人问答全部免费本地。

## 4. BATCH 7 §41 — QA mirror cleanup 收尾

- `learningPhaseHeadline` 从 :app 下沉 **feature:presence 单点**，:app 与 QA 双端共用，
  QaHeadlineEngine 文案副本删除——QA mirror 三项（PortraitMirror 黄金门 / Headline 单点 / AskEcho
  薄适配器）至此全部闭环。

## 5. 实测

见最终计数（无新测试；文案重构行为等价，快照套件零漂移验证）。
