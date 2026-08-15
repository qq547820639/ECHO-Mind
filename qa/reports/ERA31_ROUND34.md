# ERA 31 Round 34 报告（Wallpaper Adoption：Scene 一次性壁纸引导）

> 日期：2026-08-15。

## 走查发现

走查 §14/§33「Wallpaper 是核心产品面，不是附加功能」与 PART 57 主指标
**Wallpaper adoption** 时发现：壁纸入口只有 Me → Presence 设置深处的
「选择 ECHO 壁纸」——新用户从不进 Me 就永远发现不了壁纸，采纳率天然为零。

## 修复

Scene 新增**一次性壁纸引导**（与 AI 提示同款安静模式）：

- `AppPreferences.wallpaperPromptDismissed`（默认 false）；
- Scene 状态透明区下方：未关闭时显示两个 TextButton——
  「让 ECHO 留在桌面：设置动态壁纸 →」（跳系统动态壁纸选择器，组件钉定
  EchoWallpaperService）+「以后再说」；
- 打开过或关闭过即持久化 dismissed，永不再出现（非阻塞、不推送——
  §10 更安静而不是更吵，与 AI 提示同一克制模式）；
- 选择器 intent 与 Me 入口同源（失败静默）。

## 回归

- `EchoSceneContentSmokeTest` +3：默认隐藏 / 未关闭显示 + 「以后再说」事件 /
  选择动作事件。

## 实测

- Android 1031 全绿（+3）+ detekt + `:app:lintDebug` PASS。