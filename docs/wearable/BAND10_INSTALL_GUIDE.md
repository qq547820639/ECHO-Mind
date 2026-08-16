# BAND10 真机安装指引（Phase 12 基准流程）

> 当前状态：本环境无 Band10 真机、无 Mi Fitness 第三方应用 Debug 通道 →
> `BLOCKED_EXTERNAL_BAND10_DEVICE` + `BLOCKED_EXTERNAL_XIAOMI_THIRD_PARTY_CHANNEL`。
> 本文件给出**实施当天的官方流程基准**与确切人工下一步；真机执行后按
> `ECHO_WRIST_REAL_DEVICE_REPORT.md` 模板如实记录（不伪造数据）。

## 1. 前置产物

| 产物 | 状态 | 路径 |
|---|---|---|
| Vela 源码 + 静态验证 | PASS（node tests/run.js + tests/preflight.js 全绿） | `wearable/xiaomi-vela/` |
| `.debug.rpk` | **BUILT（ERA 33 R4，官方 aiot-toolkit 2.0.5 真实打包，JSC 字节码）** | `wearable/xiaomi-vela/dist/com.yunjue.echo.mind.debug.1.0.rpk`（不入库，.gitignore 已排除） |
| Android debug APK | **BUILT（ERA 33 R1 本地实测，debug 测试密钥签名 v2/v3，含 wearable dex）** | 无空格路径副本 `assembleDebug`；SHA256 见下 |
| 签名身份 | **APK ↔ RPK MATCH（同一 Android debug 证书）** | `scripts/verify_wrist_signing.py` 实测 MATCH |
| 官方模拟器实测 | **RUN（ERA 33 R4）**：安装成功（重启持久）+ 全生命周期无异常 + 渲染像素级验证（`ECHO_WRIST_REAL_DEVICE_REPORT.md`） | VVD `Vela_Band10`（xiaomi_band_10 skin） |

RPK 溯源（ERA 33 R4）：

| 字段 | 值 |
|---|---|
| filename | `com.yunjue.echo.mind.debug.1.0.rpk`（官方命名；build mode = debug 内嵌于文件名） |
| size | 42,942 bytes |
| sha256 | `1b90a61ce0c55da6ab0c5cb73437d60feb59dce69856fc03bca7270b22cc4f0b` |
| build mode | debug（官方 `aiot build --enable-jsc`，webpack 编译 + JSC 字节码 + RPK 打包） |
| source commit | ERA 33 R4（本轮 git HEAD） |
| 签名指纹 | `8A0AA19965BB82E53067728B0CBFB11535472AAD8D2C57EF74542C8134E246F6`（Android Debug 证书） |
| 官方工具链 | npm `aiot-toolkit@2.0.5` + `@aiot-toolkit/jsc`（iot.mi.com 官方 CLI；`aiot build` 于项目根） |
| 内容 | echo/why/action 编译页（.jsc）+ app.jsc + common/echo.css + i18n(zh-CN/en/defaults) + manifest + META-INF 签名 |

本机实测 debug APK 溯源（ERA 33 R1）：

| 字段 | 值 |
|---|---|
| filename | `app-debug.apk`（无空格路径副本构建，源仓库路径含空格时 AGP dexing 限制见 STATUS §5） |
| size | 22,277,895 bytes |
| sha256 | `88cb3acdbaea18c4927bf2f0c66295cf2e94577f3624617c26c23f785dc3c1c8` |
| build mode | debug（本地 AGP debug keystore，v2/v3） |
| source commit | ERA 33 R1（本轮 git HEAD） |
| 签名指纹 | `8A0AA19965BB82E53067728B0CBFB11535472AAD8D2C57EF74542C8134E246F6`（Android Debug 证书） |
| interconnect 签名联调 | debug RPK 必须用同一证书的 pem（`docs/wearable/ECHO_WRIST_SIGNING.md` §4；`verify_wrist_signing.py` 已实测 MATCH 流程） |

## 2. 官方安装流程基准（实施当天重新确认）

当前公开流程基准：

```
Mi Fitness / 小米运动健康
  ↓ Me / 我的
  ↓ About / 关于
  ↓ Debug
  ↓ Third-party apps / 第三方应用
  ↓ Install third app
  ↓ 选择本地 .rpk（wearable/xiaomi-vela/dist/*.debug.rpk）
```

- 实施当天按最新官方文档核对步骤与入口位置（官方文档：
  [Vela Quick App 指南](https://iot.mi.com/vela/quickapp/zh/guide/start/use-ide.html)）。
- RPK 签名：interconnect 联调用的 debug RPK 必须与 Android debug APK 同一证书
  （`docs/wearable/ECHO_WRIST_SIGNING.md` §4；`scripts/verify_wrist_signing.py` 输出 MATCH 再联调）。

## 3. 首次安装验收清单（ECHO_WRIST_FIRST_DEVICE_INSTALL = PASS 判据）

- [ ] RPK 安装成功
- [ ] App icon / entry visible
- [ ] ECHO Wrist launches
- [ ] no immediate crash
- [ ] ECHO organism visible
- [ ] time visible
- [ ] visual fits screen（212×520，无裁剪）
- [ ] WHY opens
- [ ] Action opens
- [ ] Breathing visual works
- [ ] Pause visual works
- [ ] short vibration works if enabled（Me → 触觉 ON）
- [ ] haptics disabled really disables vibration（Me → 触觉 OFF → 全静默）
- [ ] app reopens after exit
- [ ] cache survives expected app lifecycle if platform permits

## 4. 记录要求（不记录私人用户数据）

安装执行后填写 `docs/wearable/ECHO_WRIST_REAL_DEVICE_REPORT.md`：
device / firmware / Mi Fitness version / RPK SHA256 / APK SHA256 / install result /
runtime result / known limitations。
