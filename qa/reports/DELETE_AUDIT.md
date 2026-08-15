# Delete Audit（BATCH 7 §39/§40）

> ERA 31 R7。逐项检查旧产品遗产：是否仍属于 ECHO / Journey / Me？
> 不属于 → 移出核心 UI。审计日期：2026-08-15。

## 结论总表

| 检查项 | 现状 | 判定 | 处置 |
|---|---|---|---|
| 旧 Skills | SkillRepository/SkillCardHost/SkillActionRenderers/SkillSessionCoordinator + SkillListSection（ECHO Scene 内） | **属于 ECHO**——Skills = Action 系统（产品顺序 Act/Intervene 的载体），内容来自 content-packs（云端下发，非本地硬编码） | 保留 |
| Subscription 页面 | SubscriptionSection（Me 内 section，非独立页面）+ SubscriptionViewModel | 属于 Me（可选订阅入口） | 保留（§40 gating 审计见下） |
| 历史 Support UI | SupportSection + MeSupportHelpers | 属于 Me；安全资源入口（紧急支持）为冻结安全契约 | 保留 |
| 旧 Mental-health content | content-packs/safety + questionnaires（GAD-7/PHQ-9）+ practices | **临床量表属于冻结安全契约**（危机处置 L0 路径），移除需临床负责人签署 | 保留 + 移交外部门复核（Part 0 边界：不擅自删） |
| Questionnaire remnants（App 内） | 无——App 内无问卷 UI；量表只在后端 content-packs（临床路径） | 干净 | 无处置 |
| Journal remnants | 仅 SkillActionRenderers 注释引用「旧日记系统」；无 UI/无上传路径 | 干净（注释历史描述） | 注释保留（事实记录） |
| Check-in remnants | 无 | 干净 | 无处置 |
| 重复 Settings | Me 七 section 各自单一归属（Data/Intelligence/Presence/Memory/Subscription/Support/WhatEchoKnows） | 干净 | 无处置 |
| 重复 diagnostics | 无用户可见 diagnostics（内部质量反馈仅 DEBUG 构建） | 干净 | 无处置 |
| 重复 QA mirror | QaPortraitMirror（必要镜像 + 黄金门）/ QaHeadlineEngine（待 BATCH 7 下沉）/ QaAskEcho（已薄适配器化） | 见 QA_MIRROR_AUDIT | QaHeadlineEngine 下沉列本轮后续 |

## §40 Subscription gating 审计

- 订阅 = 激活码制，gating 面 = **云端同步 + 专业支持**（SubscriptionViewModel 文案明示）；
- **免费核心全部本地且无墙**：Observation（感知采集）/ Baseline（本地基线）/ Presence（本地装配渲染）/
  Basic Journey（本地时间线+河流+年视图）/ Basic Memory（本地七类记忆+Worker 生命周期）/
  Basic Actions（本地 skills）；
- 无 Provider 的确定性个人问答（R3 PersonalAnswerEngine）也不在订阅墙后 ✓；
- 结论：**符合 §40**（收费不决定 ECHO 是否懂用户）。无需改动。

## 本轮执行的实体变更

1. 无删除——审计确认核心 UI 已经干净（ERA 38 简化轮已清过）；
2. `qa/DOGFOOD_PROTOCOL.md` + `DAY_TEMPLATE.md` 增补六类缺陷分类与缺失记录项（Why accuracy /
   纠正复用 / 壁纸留存意愿 / Journey 有用性）——BATCH 6 准备；
3. `scripts/collect_wallpaper_metrics.sh`（真机电池/CPU/mem/帧/wakeups 采样脚本）——BATCH 6 真机采集工具。

## 遗留（跨轮）

- ✅ QaHeadlineEngine 下沉完成（ERA 31 R7）：learningPhaseHeadline 迁 feature:presence 单点，QA 副本删除；
- 临床量表外部门复核：移交 RELEASE_READINESS 外部门清单（已列「临床负责人签署」）。
