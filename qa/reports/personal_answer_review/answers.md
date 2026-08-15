# Core Personal Reasoning Set — 真实回答捕获（production PersonalAnswerEngine）

生成：PersonalAnswerReviewHarnessTest（:feature:qa）。回答 = 无 Provider/离线时用户所见。

## q001 最近我是不是越来越晚？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 90d · baseline=true · context=true · correction=false
- **PROFILE_A_STABLE · Day 180**：整体节奏和之前差不多，没有明显的后移。
  - 证据：活跃起点中位数 08:53 → 08:54（差 1 分钟）
- **PROFILE_D_TRAVEL · Day 90**：整体节奏和之前差不多，没有明显的后移。
  - 证据：活跃起点中位数 08:50 → 09:01（差 11 分钟）
- **PROFILE_F_LOW_DATA · Day 180**：整体节奏和之前差不多，没有明显的后移。
  - 证据：活跃起点中位数 09:04 → 09:11（差 7 分钟）

## q004 最近一个月我明显变晚了吗？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 90d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：整体节奏和之前差不多，没有明显的后移。
  - 证据：活跃起点中位数 08:55 → 08:54（差 -1 分钟）
- **PROFILE_D_TRAVEL · Day 90**：整体节奏和之前差不多，没有明显的后移。
  - 证据：活跃起点中位数 08:57 → 09:03（差 5 分钟）
- **PROFILE_F_LOW_DATA · Day 180**：整体节奏和之前差不多，没有明显的后移。
  - 证据：活跃起点中位数 09:07 → 09:10（差 2 分钟）

## q005 这段时间我的晚上结束时间有什么趋势？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 90d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：晚上结束时间和之前差不多，没有明显趋势。
  - 证据：结束时间中位数 23:02 → 23:07（差 5 分钟）
- **PROFILE_D_TRAVEL · Day 90**：晚上结束时间和之前差不多，没有明显趋势。
  - 证据：结束时间中位数 23:07 → 23:13（差 6 分钟）
- **PROFILE_F_LOW_DATA · Day 180**：晚上结束时间和之前差不多，没有明显趋势。
  - 证据：结束时间中位数 22:25 → 22:34（差 9 分钟）

## q006 我的活跃起点和半年前一样吗？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 90d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：半年前后你的核心节奏非常接近——这是属于你的稳定，不一定是没变化。
  - 证据：活跃起点/屏幕时间的半年差都在日常波动内
- **PROFILE_D_TRAVEL · Day 90**：半年前后你的核心节奏非常接近——这是属于你的稳定，不一定是没变化。
  - 证据：活跃起点/屏幕时间的半年差都在日常波动内
- **PROFILE_F_LOW_DATA · Day 180**：半年前后你的核心节奏非常接近——这是属于你的稳定，不一定是没变化。
  - 证据：活跃起点/屏幕时间的半年差都在日常波动内

## q007 为什么这个星期特别碎？

- 引擎覆盖：是
- 期望证据：task=SUMMARIZE_WEEK · 窗口 7d · baseline=true · context=true · correction=false
- **PROFILE_A_STABLE · Day 180**：这一周和上一周的节奏很接近，没有明显变化。
  - 证据：起点/屏幕/零散日三项周间差都在日常波动内
- **PROFILE_D_TRAVEL · Day 90**：这一周和上一周的节奏很接近，没有明显变化。
  - 证据：起点/屏幕/零散日三项周间差都在日常波动内
- **PROFILE_F_LOW_DATA · Day 180**：这一周和上一周的节奏很接近，没有明显变化。
  - 证据：起点/屏幕/零散日三项周间差都在日常波动内

## q009 这一周和上一周有什么变化？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 14d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：这一周和上一周的节奏很接近，没有明显变化。
  - 证据：起点/屏幕/零散日三项周间差都在日常波动内
- **PROFILE_D_TRAVEL · Day 90**：这一周和上一周的节奏很接近，没有明显变化。
  - 证据：起点/屏幕/零散日三项周间差都在日常波动内
- **PROFILE_F_LOW_DATA · Day 180**：这一周和上一周的节奏很接近，没有明显变化。
  - 证据：起点/屏幕/零散日三项周间差都在日常波动内

## q010 这个星期为什么看起来这么零散？

- 引擎覆盖：是
- 期望证据：task=SUMMARIZE_WEEK · 窗口 7d · baseline=true · context=true · correction=false
- **PROFILE_A_STABLE · Day 180**：这一周和上一周的节奏很接近，没有明显变化。
  - 证据：起点/屏幕/零散日三项周间差都在日常波动内
- **PROFILE_D_TRAVEL · Day 90**：这一周和上一周的节奏很接近，没有明显变化。
  - 证据：起点/屏幕/零散日三项周间差都在日常波动内
- **PROFILE_F_LOW_DATA · Day 180**：这一周和上一周的节奏很接近，没有明显变化。
  - 证据：起点/屏幕/零散日三项周间差都在日常波动内

## q013 这个月和上个月最大的区别是什么？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 90d · baseline=true · context=true · correction=false
- **PROFILE_A_STABLE · Day 180**：这两个月的节奏很接近，没有明显差别。
  - 证据：活跃起点/屏幕时间的月间差都在日常波动范围内
- **PROFILE_D_TRAVEL · Day 90**：这两个月的节奏很接近，没有明显差别。
  - 证据：活跃起点/屏幕时间的月间差都在日常波动范围内
- **PROFILE_F_LOW_DATA · Day 180**：这两个月的节奏很接近，没有明显差别。
  - 证据：活跃起点/屏幕时间的月间差都在日常波动范围内

## q014 这个月我比上个月更晚睡了吗？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 90d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：这两个月的节奏很接近，没有明显差别。
  - 证据：活跃起点/屏幕时间的月间差都在日常波动范围内
- **PROFILE_D_TRAVEL · Day 90**：这两个月的节奏很接近，没有明显差别。
  - 证据：活跃起点/屏幕时间的月间差都在日常波动范围内
- **PROFILE_F_LOW_DATA · Day 180**：这两个月的节奏很接近，没有明显差别。
  - 证据：活跃起点/屏幕时间的月间差都在日常波动范围内

## q015 上个月和这个月我的屏幕时间差多少？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 90d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：这个月屏幕时间和上个月几乎一样。
  - 证据：上月每天约 251 分钟 · 本月 251 分钟
- **PROFILE_D_TRAVEL · Day 90**：这个月屏幕时间和上个月差不多（多 1 分钟）。
  - 证据：上月每天约 248 分钟 · 本月 249 分钟
- **PROFILE_F_LOW_DATA · Day 180**：这个月屏幕时间和上个月差不多（多 14 分钟）。
  - 证据：上月每天约 207 分钟 · 本月 221 分钟

## q017 最近两个月我最大的改变是什么？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 90d · baseline=true · context=true · correction=false
- **PROFILE_A_STABLE · Day 180**：这两个月的节奏很接近，没有明显差别。
  - 证据：活跃起点/屏幕时间的月间差都在日常波动范围内
- **PROFILE_D_TRAVEL · Day 90**：这两个月的节奏很接近，没有明显差别。
  - 证据：活跃起点/屏幕时间的月间差都在日常波动范围内
- **PROFILE_F_LOW_DATA · Day 180**：这两个月的节奏很接近，没有明显差别。
  - 证据：活跃起点/屏幕时间的月间差都在日常波动范围内

## q019 周末和平时有什么变化？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 60d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：周末比工作日晚起 23 分钟。
  - 证据：工作日起床 08:51 · 周末 09:14；工作日屏幕 249 分钟 · 周末 256 分钟
- **PROFILE_D_TRAVEL · Day 90**：周末比工作日晚起 20 分钟。
  - 证据：工作日起床 08:52 · 周末 09:12；工作日屏幕 249 分钟 · 周末 243 分钟
- **PROFILE_F_LOW_DATA · Day 180**：周末比工作日晚起 55 分钟。
  - 证据：工作日起床 09:00 · 周末 09:55；工作日屏幕 215 分钟 · 周末 217 分钟

## q021 工作日和周末我的节奏差多少？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 60d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：周末比工作日晚起 23 分钟。
  - 证据：工作日起床 08:51 · 周末 09:14；工作日屏幕 249 分钟 · 周末 256 分钟
- **PROFILE_D_TRAVEL · Day 90**：周末比工作日晚起 20 分钟。
  - 证据：工作日起床 08:52 · 周末 09:12；工作日屏幕 249 分钟 · 周末 243 分钟
- **PROFILE_F_LOW_DATA · Day 180**：周末比工作日晚起 55 分钟。
  - 证据：工作日起床 09:00 · 周末 09:55；工作日屏幕 215 分钟 · 周末 217 分钟

## q023 我的周末和工作日像两个人吗？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 60d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：周末比工作日晚起 23 分钟。
  - 证据：工作日起床 08:51 · 周末 09:14；工作日屏幕 249 分钟 · 周末 256 分钟
- **PROFILE_D_TRAVEL · Day 90**：周末比工作日晚起 20 分钟。
  - 证据：工作日起床 08:52 · 周末 09:12；工作日屏幕 249 分钟 · 周末 243 分钟
- **PROFILE_F_LOW_DATA · Day 180**：周末比工作日晚起 55 分钟。
  - 证据：工作日起床 09:00 · 周末 09:55；工作日屏幕 215 分钟 · 周末 217 分钟

## q025 最近哪几天最像今天？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 28d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：最像的是 06-29、06-08、06-14。
  - 证据：06-29（z 距离 4.79） · 06-08（z 距离 5.14） · 06-14（z 距离 5.62）
- **PROFILE_D_TRAVEL · Day 90**：最像的是 03-08、03-24、03-20。
  - 证据：03-08（z 距离 0.93） · 03-24（z 距离 0.98） · 03-20（z 距离 0.98）
- **PROFILE_F_LOW_DATA · Day 180**：最像的是 06-27、06-08、06-18。
  - 证据：06-27（z 距离 1.17） · 06-08（z 距离 1.46） · 06-18（z 距离 2.07）

## q028 今天最像最近什么时候的我？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 28d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：最像的是 06-29、06-08、06-14。
  - 证据：06-29（z 距离 4.79） · 06-08（z 距离 5.14） · 06-14（z 距离 5.62）
- **PROFILE_D_TRAVEL · Day 90**：最像的是 03-08、03-24、03-20。
  - 证据：03-08（z 距离 0.93） · 03-24（z 距离 0.98） · 03-20（z 距离 0.98）
- **PROFILE_F_LOW_DATA · Day 180**：最像的是 06-27、06-08、06-18。
  - 证据：06-27（z 距离 1.17） · 06-08（z 距离 1.46） · 06-18（z 距离 2.07）

## q029 我最近稳定了吗？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 28d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：最近两周里大多数日子都和你的通常状态接近，算稳定的。
  - 证据：近 14 天：接近通常 12/14 天 · 节律漂移 0.30
- **PROFILE_D_TRAVEL · Day 90**：最近两周里大多数日子都和你的通常状态接近，算稳定的。不过这段时间的整体节奏有些漂移，我在慢慢观察。
  - 证据：近 14 天：接近通常 10/14 天 · 节律漂移 0.35
- **PROFILE_F_LOW_DATA · Day 180**：最近两周里大多数日子都和你的通常状态接近，算稳定的。不过这段时间的整体节奏有些漂移，我在慢慢观察。
  - 证据：近 14 天：接近通常 12/14 天 · 节律漂移 0.33

## q031 我最近是不是波动很大？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 28d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：最近两周里大多数日子都和你的通常状态接近，算稳定的。
  - 证据：近 14 天：接近通常 12/14 天 · 节律漂移 0.30
- **PROFILE_D_TRAVEL · Day 90**：最近两周里大多数日子都和你的通常状态接近，算稳定的。不过这段时间的整体节奏有些漂移，我在慢慢观察。
  - 证据：近 14 天：接近通常 10/14 天 · 节律漂移 0.35
- **PROFILE_F_LOW_DATA · Day 180**：最近两周里大多数日子都和你的通常状态接近，算稳定的。不过这段时间的整体节奏有些漂移，我在慢慢观察。
  - 证据：近 14 天：接近通常 12/14 天 · 节律漂移 0.33

## q032 和上个月比，我这个月更规律了吗？

- 引擎覆盖：是
- 期望证据：task=FIND_LONGITUDINAL_PATTERN · 窗口 90d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：最近两周里大多数日子都和你的通常状态接近，算稳定的。
  - 证据：近 28 天：接近通常 21/28 天 · 节律漂移 0.30
- **PROFILE_D_TRAVEL · Day 90**：最近两周里大多数日子都和你的通常状态接近，算稳定的。不过这段时间的整体节奏有些漂移，我在慢慢观察。
  - 证据：近 28 天：接近通常 22/28 天 · 节律漂移 0.35
- **PROFILE_F_LOW_DATA · Day 180**：最近两周里大多数日子都和你的通常状态接近，算稳定的。不过这段时间的整体节奏有些漂移，我在慢慢观察。
  - 证据：近 28 天：接近通常 20/27 天 · 节律漂移 0.33

## q033 为什么你觉得今天不一样？

- 引擎覆盖：是
- 期望证据：task=EXPLAIN_CURRENT_STATE · 窗口 7d · baseline=true · context=true · correction=false
- **PROFILE_A_STABLE · Day 180**：主要是晚间屏幕比平时更晚。
  - 证据：维度 SCREEN_TIMING = LATER（z=4.2）
- **PROFILE_D_TRAVEL · Day 90**：其实今天和你的通常节奏很接近，我没觉得特别不一样。
  - 证据：STABILITY = VERY_SIMILAR
- **PROFILE_F_LOW_DATA · Day 180**：主要是屏幕时间比平时长。
  - 证据：维度 SCREEN_AMOUNT = MORE（z=1.4）

## q035 今天的状态和平时有什么不同？

- 引擎覆盖：是
- 期望证据：task=EXPLAIN_CURRENT_STATE · 窗口 7d · baseline=true · context=true · correction=false
- **PROFILE_A_STABLE · Day 180**：主要是晚间屏幕比平时更晚。
  - 证据：维度 SCREEN_TIMING = LATER（z=4.2）
- **PROFILE_D_TRAVEL · Day 90**：其实今天和你的通常节奏很接近，我没觉得特别不一样。
  - 证据：STABILITY = VERY_SIMILAR
- **PROFILE_F_LOW_DATA · Day 180**：主要是屏幕时间比平时长。
  - 证据：维度 SCREEN_AMOUNT = MORE（z=1.4）

## q037 今天为什么这么碎？

- 引擎覆盖：是
- 期望证据：task=EXPLAIN_CURRENT_STATE · 窗口 7d · baseline=true · context=true · correction=false
- **PROFILE_A_STABLE · Day 180**：其实今天不算特别零散，和平时接近。
  - 证据：STABILITY = CLEARLY_DIFFERENT
- **PROFILE_D_TRAVEL · Day 90**：其实今天不算特别零散，和平时接近。
  - 证据：STABILITY = VERY_SIMILAR
- **PROFILE_F_LOW_DATA · Day 180**：其实今天不算特别零散，和平时接近。
  - 证据：STABILITY = SLIGHTLY_DIFFERENT

## q038 我说过最近在出差，这有没有影响？

- 引擎覆盖：是
- 期望证据：task=ANSWER_PERSONAL_QUESTION · 窗口 28d · baseline=false · context=true · correction=true
- **PROFILE_A_STABLE · Day 180**：我这里没有找到出差相关的上下文，所以暂时没有把它算进去。
  - 证据：无出差窗口记录
- **PROFILE_D_TRAVEL · Day 90**：最近一次出差已经结束了，你的节奏看起来正在回到平时。
  - 证据：上次出差 01-25~02-01
- **PROFILE_F_LOW_DATA · Day 180**：我这里没有找到出差相关的上下文，所以暂时没有把它算进去。
  - 证据：无出差窗口记录

## q040 我纠正过你的那次，后来你改了吗？

- 引擎覆盖：是
- 期望证据：task=ANSWER_PERSONAL_QUESTION · 窗口 28d · baseline=false · context=false · correction=true
- **PROFILE_A_STABLE · Day 180**：你还没有纠正过我。等你纠正时我会记住，并且以后优先考虑你的说法。
  - 证据：纠正记录 0 条
- **PROFILE_D_TRAVEL · Day 90**：你还没有纠正过我。等你纠正时我会记住，并且以后优先考虑你的说法。
  - 证据：纠正记录 0 条
- **PROFILE_F_LOW_DATA · Day 180**：你还没有纠正过我。等你纠正时我会记住，并且以后优先考虑你的说法。
  - 证据：纠正记录 0 条

## q042 我确认过周末会晚起，你的观察一致吗？

- 引擎覆盖：是
- 期望证据：task=ANSWER_PERSONAL_QUESTION · 窗口 60d · baseline=true · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：一致。你的观察也对：周末确实比工作日晚起约 23 分钟。
  - 证据：工作日起床 08:51 · 周末 09:14
- **PROFILE_D_TRAVEL · Day 90**：一致。你的观察也对：周末确实比工作日晚起约 20 分钟。
  - 证据：工作日起床 08:52 · 周末 09:12
- **PROFILE_F_LOW_DATA · Day 180**：一致。你的观察也对：周末确实比工作日晚起约 55 分钟。
  - 证据：工作日起床 09:00 · 周末 09:55

## q043 你还记得我确认过的那些事情吗？

- 引擎覆盖：是
- 期望证据：task=ANSWER_PERSONAL_QUESTION · 窗口 28d · baseline=false · context=false · correction=false
- **PROFILE_A_STABLE · Day 180**：你还没有确认过什么。你确认过的事情我会一直记得，并且优先相信你的说法。
  - 证据：确认记录 0 条
- **PROFILE_D_TRAVEL · Day 90**：你还没有确认过什么。你确认过的事情我会一直记得，并且优先相信你的说法。
  - 证据：确认记录 0 条
- **PROFILE_F_LOW_DATA · Day 180**：你还没有确认过什么。你确认过的事情我会一直记得，并且优先相信你的说法。
  - 证据：确认记录 0 条

## 覆盖率

引擎覆盖 26/26 条；其余诚实交回 AI 路径。
