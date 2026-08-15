# ERA 31 Round 31 报告（苏醒 = 第一次 Presence：Day-0 SEED 单一构建点）

> 日期：2026-08-15。

## 走查发现

Me 根页面 / 行动覆盖层 / 订阅槽位走查未发现新缺陷（架构与顺序良好）。随后核对
R22 苏醒改动的**身份连续性契约**，发现一处真实不一致：

- R22 的 AwakeningScreen 用 `baselineStability = 0.5f` 派生 Day-0 SEED identity；
- 而运行时 Day-0 的 PresenceRepository 用 `ambient.vector.regularity`（AmbientEngine
  无数据初值 = **0f**）派生。

accent/color/texture/topology/orbit 只依赖 seed（不受影响），但 motionPersonality
含 stability×15% 项 → 苏醒画面与随后进入 Scene 的第一帧**运动人格相差 0.075**——
不是同一个 ECHO 的严格连续呼吸，只是「碰巧长得像」。

## 修复

1. **`dayZeroSeedPresence(identitySeed, motionPreference, now)`**（feature:presence
   单一构建点）：maturity=SEED、identityGenome = deriveIdentityGenome(seed,
   **stability=0f**（与 AmbientEngine 无数据初值同源）, preference)——苏醒与运行时
   第一次 Presence 由契约保证逐字段一致；
2. AwakeningScreen 改用该构建点（并清 5 个废弃 import）。

## 回归

- `IdentityStructureVisibilityTest.dayZeroSeedPresenceMatchesFirstRuntimeIdentity`
  （+1）：SEED 成熟度 + identity 与运行时同参派生完全一致 + 同 seed 确定性。

## 实测

- Android 1026 全绿（+1）+ detekt + `:app:lintDebug` PASS。
