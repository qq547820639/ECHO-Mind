# PERFORMANCE BASELINES —— JVM 防退化门禁（ERA 18 收尾）

> 语义：**防数量级退化**，不是基准分数。预算 = JVM 典型值 × 20-100 倍安全边际；
> CI 硬件波动不误报。真机数字（Cold startup / Room startup / first meaningful frame /
> Wallpaper CPU/GPU/frame/memory/wakeups/battery / Dream）由 CI connected-test
> 与真机矩阵执行（见 `docs/performance/PRESENCE_BENCHMARKS.md`）。

## 已冻结的 JVM 预算（PerformanceBaselineTest）

| 场景 | 输入规模 | 预算（取最优 3 次） | 关联 |
|---|---|---|---|
| Journey 365 天全装配（画像 → JourneyDay → Year View） | 365 天 | < 2000 ms | §108 preaggregation：真实路径读 Canonical 快照 ≤365 行，绝不全量实时计算 |
| Journey 365 天**完整 UI 状态装配**（assembleJourneyUiState 全链：周期/河流/年视图/解释） | 365 天 + 上下文例外 | < 2000 ms | ERA 39：§108「禁止每次全量实时计算 365 天」的 UI 装配代表项 |
| Life Season 计算 | 365 画像窗口 | < 1000 ms | §56 数周/月窗口 |
| Memory 排序（JVM 重排） | 1000 条 | < 1000 ms | §109：SQL 侧已 LIMIT + 复合索引（v11），JVM 只重排候选 |
| Derived Pattern 派生（Worker 维护路径） | 1000 条观察 | < 1000 ms | ERA 39：§77 重复 + 证据 + 置信才成长期模式 |
| 空/单条边界组合 | empty | < 200 ms | 边界不退化 |
| Presence 装配全链（Identity + LifeSeason + Daily + Moment + 平滑） | 200 次（60 天窗口） | < 2000 ms | PART PERFORMANCE「Presence assembly」 |
| Context 编译（§68 排序 + §69 三重预算 + token 截断；含禁止数据剔除路径） | 500 条证据 × 20 次 | < 2000 ms | PART PERFORMANCE「Context retrieval」纯函数段 |
| ECHO Scene 帧计算（VisualMapper → 确定性帧；渲染器只画帧） | 1000 帧 | < 2000 ms | PART PERFORMANCE「first meaningful frame」JVM 代表项 |

## 长历史策略（§108/§109 已落地）

- **Journey**：Canonical Daily State 预聚合快照（Room v10 `journey_canonical_days`，
  userId/localDate 索引）——年视图只读 ≤365 行参数行；河流/月聚合只在窗口内计算。
- **Memory**：查询全部带 userId + LIMIT（topByUser 3 倍候选截断）；
  Room v11 复合索引 `(userId, deleted, importance)` 与 `(userId, type, deleted)`
  覆盖 top/observe/byType 三条热路径——避免「SELECT everything → JVM sort」随规模退化。

## 扩展规则

- 新预算必须与具体产品路径绑定（不得为测试而测）；
- 预算只允许在「实现真实优化 + 复测」后收紧；发现数量级退化 → 阻断发布。
