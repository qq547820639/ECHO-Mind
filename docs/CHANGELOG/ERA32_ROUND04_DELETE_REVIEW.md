# ERA 32 R04 — Product Simplification Delete Review（Batch G 预检）

> 2026-08-15。§53：删除是一等开发能力，每轮执行 Delete Review。本轮对用户表面与代码层
> 逐类复查（ERA 31 R7 已有一次审计，本轮复核 + 补删遗留项）。无新功能、无新 module。

## 1. 删除（本轮执行）

- `core:model` `baselineProgressText` / `todayCoveragePercent` 删除——v0.7 的「基线进度条 /
  今日覆盖率」UI 自 ERA 31 R28 起退役（Scene 去仪表化，产品宪法 §9 禁止重新增加 metrics/coverage），
  两函数零消费方（R28 留作「规格锚点」的清理决策本轮作出：§53 删除）。对应测试
  `baselineProgressTextClamps` / `todayCoveragePercentParsesAndClamps` 同步删除；
  `delayToNextEveningMs` 测试保留（其目标为生产 Worker 代码）。

## 2. 复查保留（判定 + 理由）

| 对象 | 判定 | 理由 |
|---|---|---|
| Skills 体系（SkillRepository / SkillCardHost / SkillListSection / ActionRenderer） | 保留 | Skills=Action 系统（R7 决策）：呼吸/暂停等行动仍在 Scene 行动层与协调器消费，非遗产 |
| Subscription UI | 保留 | Me 内 section，免费核心承诺与到期语义仍有效（§56） |
| QA mirrors（QaPortraitMirror / QaAskEcho / QaHeadlineEngine） | 保留 | 三项均有黄金门或已单点（`qa/reports/QA_MIRROR_AUDIT.md` §7 闭环） |
| ERA31_ROUND01~49 轮报 | 保留 | ERA 31 详细历史，被 CHANGELOG/验收报告引用；不占用户表面 |
| Me 检查台 / Data & Sensing 双入口 | 保留 | 权限设置与控制权（§49），R26 已去重麦克风双控制 |
| 临床量表 content-packs / pilot-pack | 保留 | 冻结安全契约与外部移交包，不由本轮处置 |
| JourneyEvidenceView 采集/同步时间观测 | 保留 | 信任控制中心职责（R26 判定） |
| `subscriptionStatusText` / `PORTRAIT_COPY_BASELINE_UNLOCKED` | 保留 | 前者被订阅面消费；后者是第 7 天仪式事实句（Day-7 验收） |
| 旧版签到/日记/打卡 UI | 无残留 | R7 已确认无；本轮复查未发现 |
| 重复 settings / diagnostics | 无残留 | 复查通过 |

## 3. 验证

- Android 全模块单测 + detekt + lint 全绿（删除后 1040→1038，见 STATUS.md §3 自动数字）；
- SOURCE_MANIFEST 重生成 verify PASS。
- 结论：用户表面无变化（删除的是零消费方历史代码）；代码面净减 2 函数 + 2 测试。

## 4. 下一轮

Batch G 预检完成。剩余高价值方向：
1. **Batch H 条件评估**——v0.10.0 后 R02/R03 修复了 4 个 P1/TRUST 级答案缺陷，属「重要体验与
   Personal Intelligence 升级」，符合 §70 优先 0.11.0 的标准；但 Batch H 需真机 smoke（本环境无），
   版本切换决策与 Release Closure 需完整重跑发布链。
2. 或 **Batch E/F 预检**（Journey/Memory 以真实长期数据评估——同样依赖 dogfood 回流）。
