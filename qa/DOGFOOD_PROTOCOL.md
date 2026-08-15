# 30-Day Dogfood Protocol（ERA 29 §63）

> 目的：在仓库中建立真实使用测试协议，把「feature-complete but emotionally flat」
> 变成每天可观测、可回填为 eval fixture 的产品质量问题。**不使用任何真实私人数据入库**。

## 使用方式

1. 真实设备安装当前构建，作为日常手机使用 **≥ 30 天**；
2. 每天睡前（或固定时段）花 2 分钟填写下方模板一行；
3. 每周把「bad answer / correction / unexpected interpretation」匿名化 +
   fixture 化（把真实数字替换为 fixture 语义等价的场景，注明来源类别）后
   提交到 `qa/reports/dogfood/`；
4. 禁止提交：真实姓名/地点/通知正文/原话转录；只允许行为类别与量化摘要。

## 每日记录模板（ERA 31 R7 增补：Why 准确度 / 纠正复用 / 壁纸留存意愿 / Journey 有用性）

```markdown
## Day N（yyyy-MM-dd）

- ECHO visual：今天打开看到的第一眼（一句描述，如「核心比前几天更聚拢」）
- headline：ECHO 说了什么（原文）
  - 真实吗？（是/否/部分）
- Why accuracy：点「为什么」后，证据行和你的体感一致吗？（一致/不一致/看不懂）
- unexpected interpretation：有没有「它怎么会这么理解」的时刻（匿名描述）
- battery：当日电量消耗大致百分比 / 是否异常
- wallpaper behavior：壁纸今天变了吗？看到时第一反应是什么？
  - desire-to-keep：今天还想留着它吗？（想留/无感/想换）
- useful answer：今天最有用的一次回答（问题 + 为什么有用）
- bad answer：今天最差的一次回答（问题 + 错在哪）
- correction：今天有没有纠正 ECHO？（纠正了什么 → 之后行为变了吗（reused？））
- Journey usefulness：今天打开 Journey 了吗？看到「自己的时间」了吗？
```

## 问题分类（§36：每条问题只分六类，不建几十个 taxonomy）

| 分类 | 适用 |
|---|---|
| PRODUCT_DEFECT | 交互/流程/文案层面的产品缺陷 |
| REASONING_DEFECT | 回答/检索/上下文选择错误 |
| VISUAL_DEFECT | 视觉/壁纸/渲染问题 |
| TRUST_DEFECT | 隐私/边界/「它知道太多/太少」类 |
| PERFORMANCE_DEFECT | 卡顿/耗电/内存 |
| ANDROID_RUNTIME_DEFECT | 进程被杀/服务重建/OEM 后台策略 |

## 数据回流（§65）

| dogfood 发现 | 匿名化/fixture 化方式 | 落地位置 |
|---|---|---|
| bad answer 类别 | 转成 QaQuestionBank 用例 + Expected Evidence | `:feature:qa` 题库 |
| correction 是否生效 | 转成 QaCorrectionReuseTest 场景 | `:feature:qa` |
| unexpected interpretation | 转成 Grounding/Distiller 正反例 | QaGroundingCompatEvalTest / QaPersonaStabilityEvalTest |
| battery/壁纸行为 | 对照 QaWallpaperLongRunTest 预算 | `:feature:qa` |

## 示例（已 fixture 化的空模板样例，非真实数据）

见 `qa/reports/dogfood/DAY_TEMPLATE.md`。
