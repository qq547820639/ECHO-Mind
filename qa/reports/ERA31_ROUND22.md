# ERA 31 Round 22 报告（Day-0 苏醒：第一次见面就是「这个 ECHO」）

> 日期：2026-08-15。

## 走查发现

走查 FINAL ACCEPTANCE Day 0（授权完成 → ECHO 苏醒 → 第一眼），发现苏醒过渡用的是
**通用占位圆**：120dp 的 Material radialGradient 圆斑 + 呼吸缩放动画，源码注释自认
「P1 换 Generative Visual Engine」。而 production 生成视觉引擎（identity → 帧模型 →
双渲染器）早已存在——用户与 ECHO 的**第一次见面**看到的却是任何 App 都能画的圆，
不是「他的 ECHO」。§12：「用户授权完成后看到：ECHO 活了」——圆斑只是「一个动画」。

## 修复

`AwakeningScreen` 占位圆 → **真实 ECHO 第一次呼吸**：

- Day-0 SEED presence 由用户真实 `identitySeed`（安装随机种子，一次性持久化）经
  `deriveIdentityGenome`（与 Scene/Wallpaper/Dream 同源）派生；
- `EchoLifeField`（production 帧管线：computeEchoSceneFrame → drawEchoFrame）
  以 220dp 呈现，自带帧时钟呼吸——与随后进入的 ECHO Scene 是同一个 ECHO 的连续两次呼吸，
  苏醒到首页零切换感；
- 文案不变（「ECHO 已开始了解你」「今天是我们认识的第一天。」）；
- 清掉占位动画的 6 个废弃 import（rememberInfiniteTransition/animateFloat/
  infiniteRepeatable/tween/RepeatMode/scale/CircleShape）。

## 回归

- identity 派生确定性/结构可见性由既有 `IdentityStructureVisibilityTest` 锁定
  （同 seed 同 identity；结构差异不靠颜色）——苏醒视觉与 Scene 同源同 seed，契约共享。

## 实测

- Android 1024 全绿 + detekt + `:app:lintDebug` PASS（本轮回归无新增测试——复用
  production 组件而非新增 QA 面）。
