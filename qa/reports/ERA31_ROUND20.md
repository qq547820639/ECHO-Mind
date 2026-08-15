# ERA 31 Round 20 报告（Wallpaper 运动现实检查：4fps 静态期实测 + 真实帧率人眼证据）

> 日期：2026-08-15。

## 背景

R13/R14 落地了 §16 Battery Reality（静态期 4fps / 过渡期 33ms）。策略正确性此前只有
三档间隔锚点（纯策略测试），没有回答人眼问题：**4fps 的静态期画面到底像不像跳帧？**
ECHO 在静态期还活着吗？（§14 Ambient Evidence + §17 缓慢·有机·克制）

## 实测（production 帧管线，真实像素）

新增 `WallpaperMotionRealityTest`（Robolectric NATIVE 图形）——对稳定用户与周末差异型
两位 profile，用 production 帧模型按真实采样间隔渲染壁纸帧，计算帧间变化像素占比：

| Profile | 静态期 250ms 单帧跳变 | 静态期 2s 累计变化 | 过渡期 33ms 单帧跳变 |
|---|---|---|---|
| PROFILE_A_STABLE（Day 90） | 1.06% | 1.23% | 0.31% |
| PROFILE_G_WEEKEND_DIFFERENT（Day 90） | 1.10% | 9.99% | 0.45% |

结论：4fps 静态期单帧跳变 ~1%（远低于 6% 跳帧红线）——**是缓慢呼吸，不是跳帧**；
2 秒累计变化 1.2%~10%（稳定用户安静、周末型活跃）——**静态期 ECHO 仍然活着**；
过渡期 33ms 单帧跳变（0.3%~0.45%）小于静态期 250ms 跳变——流畅窗口真实生效。
三项均转为回归断言（静态期单帧 < 6%、2s 累计 > 0.3%、过渡 < 静态）。

## 人眼证据工件

`qa/visual-review/` 新增 4 张真实帧率运动拼图（A_STABLE / G_WEEKEND 各两张）：
- `motion_wallpaper_idle4fps_*.png` —— 3 秒 × 250ms/帧（真实静态期节奏）；
- `motion_wallpaper_transition30fps_*.png` —— 0.4 秒 × 33ms/帧（真实过渡期节奏）。

`index.html` 已列入画廊（浏览器打开直接看：静态期 12 帧几乎是一组缓慢呼吸帧，
与 3s/帧的 36s 序列互补——后者看运动方向，前者看真实采样间隔下的观感）。

## 实测

- Android 1023 全绿（+1 WallpaperMotionRealityTest）+ detekt + `:app:lintDebug` PASS；
  qa 视觉工件重生成（4 张新拼图 + 画廊更新）。
