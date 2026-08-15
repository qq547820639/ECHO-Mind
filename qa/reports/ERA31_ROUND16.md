# ERA 31 Round 16 报告（进程死亡恢复锚点：快照 commit 落盘）

> 日期：2026-08-15。

## 走查发现

走查「进程死亡 → 重启」恢复链路（Wallpaper/Dream 与主进程分离，主进程被杀是常态）：

- 写侧：`PresenceRepository` 每 15 分钟刷新一次 `EchoPresenceState` →
  `preferences.echoPresenceSnapshot = EchoPresenceCodec.encode(presence)`（AppPreferences 属性 setter）。
- 读侧：`EchoWallpaperService`（启动 + `refreshSnapshot`）与 `EchoDreamService`（启动）解码
  `KEY_ECHO_PRESENCE_SNAPSHOT` → 同一 identity seed → 同一个 ECHO。

发现的真实缺陷：快照 setter 用的是 `SharedPreferences.Editor.apply()`——写入只排入内存队列，
异步落盘。**硬崩溃 / 被系统杀进程时排队写入会丢**：重启后快照是上一版甚至 null，
ECHO 退化回中性占位（无 identity）最长 15 分钟。这直接违反「crash-free Presence runtime」
发布指标（v0.10.0 Release Closure §18）的恢复语义。

## 修复

1. **`AppPreferences.echoPresenceSnapshot` setter：`apply()` → `commit()`**。
   15 分钟一次的小字符串同步写，主线程成本可忽略（几十字节 JSON，SharedPreferences
   commit 是一次写文件的极小开销）；换取「setter 返回即已持久化」的恢复锚点保证。
   KDoc 写明偏离原因（本仓库其余 setter 保持 apply 惯例不动）。
   lint `ApplySharedPref` 用 `@set:SuppressLint` 定点豁免（仅此一个属性，不全局关）。
2. **新增回归测试 `EchoPresenceSnapshotRecoveryTest`（Robolectric）**：
   - `snapshotSurvivesWriterProcessDeath`：写进程 AppPreferences 实例落盘 →
     读进程（Wallpaper/Dream 等价物）独立实例解码 → Presence 完全一致 →
     恢复后 `computeEchoSceneFrame` 与前台渲染**同一帧**（同一个 ECHO，不是新 ECHO）；
   - `writingNullRemovesRecoveryAnchor`：null 写从磁盘删 key，独立实例读不到（fail-closed → 中性占位）。

## 实测

- Android 1010 全绿（+2 恢复锚点回归：app 855 / intelligence 20 / presence 24 / qa 111）+ detekt + `:app:lintDebug` PASS。
