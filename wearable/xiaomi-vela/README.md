# ECHO Wrist — Xiaomi Vela 快应用（Band 10）

SAME ECHO / SECOND BODY。手环端只做三件事：
显示同一个 ECHO（缓存 PUBLIC_SAFE Presence）、回答 WHY、发起 BREATHING / PAUSE。
手环没有 Memory / SelfModel / Journey / AI Provider / API Key。

## 构建（官方 CLI，不进 OSS CI）

官方命令行工具链（[AIoT-toolkit 文档](https://iot.mi.com/vela/quickapp/zh/tools/toolkit/start.html)；
AIoT-IDE 亦可）：

```bash
npm i aiot-toolkit -g            # 官方 CLI（本仓不写入依赖）
# 签名身份（interconnect 联调）：把 Android debug/生产证书导出到 sign/（gitignored）
#   keytool jks→p12 → openssl pkcs12 → sign/private.pem + sign/certificate.pem
aiot build                        # 编译 + 打包 debug RPK → dist/com.yunjue.echo.mind.debug.1.0.rpk
aiot start                        # 构建并运行到官方 Vela 模拟器（VVD + xiaomi_band_10 skin）
```

- 页面布局约定（官方）：路由页直接位于 `src/<page>/<component>.ux`（非 src/pages/）；
- i18n 约定：`zh-CN.json` / `en.json` / `defaults.json`（编译器打包全部 `i18n/*.json`）；
- features = 使用即声明：router / interconnect / sensor / vibrator / storage
  （`tests/declared_features_test.js` 锁定，官方 demo 同规则）；
- ERA 33 R3 实测：`aiot build` 成功产出 debug RPK（SHA256 见 BAND10_INSTALL_GUIDE）；
  与 Android debug APK 签名 MATCH（`scripts/verify_wrist_signing.py`）。

## interconnect 身份要求（官方规则，见 docs/wearable/XIAOMI_BAND10_CAPABILITY_MATRIX.md §1.3）

- `src/manifest.json` 的 `package` 必须 = Android 应用包名（`com.yunjue.echo.mind`）；
- RPK 签名必须使用 Android 应用同一签名（jks → p12 → pem 提取 private.pem / certificate.pem，
  官方在线工具可本地生成）；
- 任何私钥/证书/pem **不得提交 git**；CI 走 secret injection
  （BLOCKED_EXTERNAL_PRODUCTION_SIGNING）。

## 测试（普通 Node 即可运行）

```
node tests/run.js
```

无 Vela 工具链依赖（协议 parity / cache 纪律 / 视觉确定性 / 降级全在纯 JS 层锁定）。

## 结构

```
src/
  manifest.json            # package/features(interconnect+sensor+vibrator)/router（MINIMUM CAPABILITY DECLARATION）
  app.ux                   # 连接恢复 → 请求 Presence；前台加速度计订阅/退订
  echo/index.ux             # TIME + ECHO ORGANISM + 一行公开表达（默认无）
  why/index.ux              # WHY（headline 来自手机；降级时安静）
  action/index.ux           # BREATHING / PAUSE（同一个手机 Action；振动受 surface.hapticsEnabled 硬门）
  common/protocol/         # Wear Protocol v1 JS 编解码（与手机 Kotlin 逐字段一致）
  common/transport/        # system.interconnect 封装
  common/presence/         # revision/TTL 缓存 + 降级（Identity 保留 / Moment → QUIET）
  common/visual/           # 确定性低维渲染参数（few shapes / slow organic movement）
  common/sensor/           # 前台加速度计 10s 窗口 summary（不发 raw）
  common/cache/            # system.storage 包装（只缓存当前 PUBLIC_SAFE Presence）
  i18n/                    # zh / en / defaults
```

## 纪律

- 传感器只在应用前台订阅（官方后台模型不允许 sensor loop；不 hack 保活）；
- 振动只用官方确认的 short/long（`system.vibrator.vibrate`）；默认 SILENT；
- 断连/过期：保留 Identity，Moment 缓慢降级 QUIET，不显示 ERROR、不震动提醒。
