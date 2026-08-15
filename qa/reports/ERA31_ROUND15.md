# ERA 31 Round 15 报告（Why 层安静化：AI 催促移除 + Journey 入口一致化）

> 日期：2026-08-15。

## 审计发现与修复

走查 EchoWhyLayer 三层展示（确定性 headline / AI 增量层 / facts），发现两处安静性问题：

1. **常驻 AI 催促**：「连接 AI 后可获得更深入的解释。」——每当 `!intelligenceAvailable` 就出现，
   对免费用户是每次打开都要读一遍的催促。两重理由移除：
   - R3 起无 AI 也能得到确定性个人回答（26/26 Core Set），这句与产品事实相悖；
   - AI 引导已有唯一入口（EchoStatusOverlay 的一次性可关闭提示卡）。
   移除后：Why 层对无 AI 用户保持安静（§10 更安静而不是更吵 / gate #9）。
2. **Layer 3 按钮不一致**：「查看更多 → Journey」仍是 OutlinedButton（Scene 主入口 R1 已 TextButton 化）→
   TextButton 一致化（安静入口）。

- 回归：`EchoWhyLayerSmokeTest.aiUnavailableKeepsWhyLayerQuiet`（原
  aiUnavailableShowsConnectHint 语义反转——锁定的新行为 = 催促不出现）；
  Journey 按钮锚点（hasClickAction）不变仍绿。

## 实测

- Android 1008 全绿 + detekt + lint PASS。
