# ERA 31 Round 29 报告（Scene Action 安静化：按钮墙折叠成单一入口）

> 日期：2026-08-15。

## 走查发现

走查 Scene Action 面（§10「Action 只有真正相关时出现」），发现行动层**四个控件常驻**
第一屏：标题「想做点什么？」+「1 分钟呼吸」+「短暂离开屏幕」+「什么也不做」+
「更多能力（订阅）」（展开订阅槽位）——每位用户每次打开 Scene 都面对一座按钮墙，
且「订阅」入口对免费用户常驻（gate #9 更安静而不是更吵）。

## 修复

`EchoActionLayerContent` 改为**默认折叠**：

- 常驻 Scene 的只剩一个安静入口 TextButton「想做点什么？」（与 Journey / 问 ECHO
  同一 TextButton 视觉语言）；
- 展开后：L2 建议句（保持「打开时建议」语义）→ 呼吸/暂停 → 「什么也不做」
  （收起整个行动区）→ 订阅槽位（在展开区里，不混在免费行动中、不再常驻）；
- 展开区内布局与既有语义不变。

## 回归

- `EchoActionLayerContentSmokeTest` 重构为新语义（净 +1）：
  `collapsedByDefaultOnlyShowsQuietEntry`（默认无按钮墙）+ 其余用例先展开再断言；
  「什么也不做」现在收起整个行动区（含订阅分区）。

## 实测

- Android 1025 全绿（+1）+ detekt + `:app:lintDebug` PASS。
