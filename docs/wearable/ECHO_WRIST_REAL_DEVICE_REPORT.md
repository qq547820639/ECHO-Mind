# ECHO_WRIST_REAL_DEVICE_REPORT

> 只记录事实；不记录私人用户数据。

## 执行状态

| 字段 | 值 |
|---|---|
| 状态 | **RUN — 官方 Vela 模拟器（Band 10 profile）实测**（ERA 33 R4）；真机安装仍未执行 → `BLOCKED_EXTERNAL_BAND10_DEVICE` |
| 执行日期 | 2026-08-16 |

## 环境（官方模拟器 = 真机同源 Vela 运行时）

| 字段 | 值 |
|---|---|
| device | Xiaomi Vela 模拟器 `Vela_Band10`（官方 `xiaomi_band_10` skin，212×520；system-image `vela-miwear-watch-5.0`） |
| firmware | Vela 5.0（miwear-watch-5.0，官方 system image 20250716） |
| Mi Fitness version | —（模拟器无 Mi Fitness；真机走 BAND10_INSTALL_GUIDE §2） |
| 打包工具 | 官方 `aiot-toolkit` 2.0.5 CLI（npm） |
| RPK filename + SHA256 | `com.yunjue.echo.mind.debug.1.0.rpk`，42,942 B，`1b90a61ce0c55da6ab0c5cb73437d60feb59dce69856fc03bca7270b22cc4f0b` |
| APK filename + SHA256 | `app-debug.apk`，22,277,895 B，`88cb3acdbaea18c4927bf2f0c66295cf2e94577f3624617c26c23f785dc3c1c8` |
| 签名验证 | **MATCH**（Android debug 证书 8A:0A:A1:99…；`scripts/verify_wrist_signing.py --vela-rpk` 实测） |

## 结果

| 字段 | 值 |
|---|---|
| install result | **成功**（`pm install` → `InstallState_Finished`；`/data/app/com.yunjue.echo.mind/` 持久化，模拟器重启后仍在） |
| runtime result | **运行成功**：app onCreate/onShow → page onInit/onShow 全链无异常；页面渲染出正确色彩/几何（organism hue/ring/glow/core 实测像素匹配）；**修复 8 个真机级缺陷后零运行时错误** |
| 修复的缺陷（模拟器实测发现） | ① 页面布局必须 `src/<page>/<component>.ux`（官方编译器拒收 src/pages/）；② i18n 官方命名为 `zh-CN.json`；③ features 需声明 router/storage（官方 demo 同规则）；④ `self.x=(self.x+v).toFixed(2)` 字符串污染 → 每 tick TypeError；⑤ 页面数据必须 `private:`（`data:` 无响应式）；⑥ app 生命周期是 `onCreate`（`onInit` 静默跳过 → 应用不显示）；⑦ app 上下文**无 require**（模块级 require → 全部 app 钩子 call failed → 应用不显示）；⑧ div 上的绑定 style 属性（多属性/整串）**整页不渲染** → 迁移 class 绑定 + CSS keyframes（官方 stack 容器 + 官方动画形态） |
| 模拟器 dev-flow 限制 | 无 AIoT-IDE inspector/MQTT 调试通道（`channel manager start failed`）时，adb 启动的 quickapp 以预览形态合成（start_source=10），未走 launcher 前台打开流；页面渲染/生命周期/颜色几何均已验证，全屏前台合成留待真机/IDE 通道 |
| 断连/重连/进程死亡 | 模拟器无 interconnect 对端（手机 SDK 缺失）→ 真机验证（LONG_RUN_PROTOCOL） |
| accelerometer | 代码路径就绪；模拟器无传感器数据流 → 真机验证 |
| vibration / haptics 硬门 | 代码 + Node 测试锁定；真机验证 |

## known limitations

- 本报告为**官方模拟器**实测（Vela 5.0 运行时，与 Band 10 同源平台）；真机安装/前台验收仍需 Band 10 + Mi Fitness 第三方 Debug 通道（`BAND10_INSTALL_GUIDE.md`）。
- 模拟器无 AIoT-IDE debug 通道时 quickapp 前台合成受限（dev-flow 限制，非应用缺陷）。
