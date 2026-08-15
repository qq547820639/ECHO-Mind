# PersonalAnswerEngine 复杂度审计（ERA 32 Batch A 第 8 项）

> 审计日期：2026-08-15（ERA 32 治理减法轮）。审计对象：
> `android/feature/intelligence/src/main/java/com/yunjue/echo/mind/intelligence/PersonalAnswerEngine.kt`
> 原则：ERA 35 §21–§25——**不追求回答更多，追求回答得像「我的 ECHO」**；只有触发条件满足才拆分，不为干净提前造几十个 class。

## 1. 事实

| 面 | 事实 |
|---|---|
| 规模 | 626 行，单 `object`，15 个回答族，33 条 canonical 问法（严格表，含 Core Set 26 条 + 变体 + R17 视觉问题） |
| Intent 层 | 已存在：`RAW_FAMILY_TABLE`（问法→(族,窗口)）+ `normalizeQuestion`（语气词/标点归一）+ 撞车 check（类初始化失败即报） |
| Computation 层 | 与表达融合在各族函数内（如 `driftingLater` 同时做中位数序列计算与文案拼接） |
| Composition 层 | 与计算融合；每个族直接返回 `PersonalAnswer(text, evidence, usedSources)` |
| QA 关系 | `QaAskEcho` 是引擎薄适配器（R3 起），QA 与产品同一引擎，无镜像重写 |
| 增长曲线 | R3 8→13 族、R4 →15 族（BATCH 2 期间快速增长）；R4 之后只加表行（R17 视觉问题同源映射 WHY_TODAY），族数稳定 |

## 2. 触发条件核对（ERA 35 §25）

| 触发条件 | 现状 | 判定 |
|---|---|---|
| 引擎继续明显增长 | 族数 R4 后稳定，新增只是问法映射到既有族 | 未触发 |
| 同一计算被多个问题重复 | 存在局部重复：月桶切分（MONTH_VS_MONTH / SCREEN_MONTH_DELTA 各做一次）、周末分类（WEEKEND / CONFIRMED_WEEKEND_CHECK）——但都在同一文件内、量小、有共享工具（medianOf/minuteText/wakeSeries） | 未触发（临界） |
| 测试难以定位 | `PersonalAnswerEngineTest` 与引擎 1:1；provider 层单独 Robolectric 测试；26/26 覆盖率断言锁死 | 未触发 |
| 新问题增加大量 branch | 新增族 = 1 表行 + 1 when 臂 + 1 函数，线性成本，无 if-else 蔓延 | 未触发 |

## 3. 结论：暂不拆分

- 意图层已经存在且被严格表锁死——不会退化成「一个新问法加一个 if」的 Chat Rules Engine（§21 的红线是 branch 蔓延，不是单文件规模）。
- 计算/表达融合在 15 个族 × 短函数的规模下，可读性与定位成本都可接受；强行拆三层（几十个 class）违反 §25「不要为了干净提前创造几十个 class」。
- 现有重复（月桶/周末分类）若继续扩大，将自然成为拆分信号——见 §4 触发条件。

**决定**：维持单 object + 严格表 + 共享工具函数的形态。冻结 Core Set 26 条不变；新增问题必须映射既有族或证明是新族（每轮 Delete Review 同查）。

## 4. 未来拆分触发条件（写入判断，非立即执行）

满足任一条时启动三层拆分（Intent / Computation / Composition，§22–§24）：

1. 回答族 ≥ 20，或单文件 > 900 行；
2. 月桶切分 / 周末分类 / 窗口序列任一计算出现第 3 个复制点；
3. 新族无法在 30 分钟内定位其测试与实现；
4. 需要 Provider 路径复用本地计算层（Provider 与确定性共用 Computation）。

拆分时：Computation 输出 structured result、禁止自然语言；Composer 统一 ECHO 语言。

## 5. ERA 32 R02 更新（Batch C 答案复核轮，2026-08-15）

对 26/26 捕获回答做 FACT/CONTEXT/VALUE/VOICE 四层复核，发现并修复三个真实缺陷（族数 15→16）：

1. **q032 答非所问（CONTEXT，P1 级错误理解）**：「和上个月比，我这个月更规律了吗？」此前错挂
   STABILITY 单窗口族——回答的是「最近两周算稳定」而非月间对比。新增 `STABILITY_MONTH_COMPARE`
   独立计算（本月 vs 上月「接近通常」占比对比，阈值 0.1），证据给出两个月具体占比。
2. **q025/q028 工程值泄漏（TRUST）**：相似日证据泄漏「z 距离 4.79」（同类缺陷：R25 whyToday / R40 QA 快照）。
   证据改为「更接近今天 → 稍远：日期序列」；全仓 0 处 z 距离残留。
3. **无变化结论不可验证（VALUE）**：q006 半年 / q013·q014·q017 月间 / q007·q009·q010 周间在
   「没有明显差别」分支只给结论句不给数字。现在证据给出两端实际中位数
   （如「活跃起点 08:49 → 08:51 · 屏幕 254 → 251 分钟（半年差都在日常波动内）」）。
4. q001/q004 漂移文案去窗口硬编码（「最近这一个月」→「和前半段相比」，90d/60d 窗口通用）。

回归：`PersonalAnswerEngineTest` +5（月间规律对比 / z 距离负向锁 / 半年数字 / 月间数字 / 周间数字）；
`answers.md` 与 7 profile 快照确定性重生成。族数 16，距拆分触发线（≥20）仍远，结构维持不变。

