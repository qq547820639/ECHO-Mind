# ECHO Wrist — Xiaomi Vela 快应用（Band 10）

SAME ECHO / SECOND BODY。手环端只做三件事：
显示同一个 ECHO（缓存 PUBLIC_SAFE Presence）、回答 WHY、发起 BREATHING / PAUSE。
手环没有 Memory / SelfModel / Journey / AI Provider / API Key。

## 构建（外部 proprietary 工具，不进 OSS CI）

1. 安装 Xiaomi **AIoT-IDE**（官方 Vela 工具链，获取方式见
   https://iot.mi.com/vela/quickapp/zh/guide/start/use-ide.html ）；
2. 用 IDE 打开本目录 → 模拟器调试 → 打包 RPK。

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
  manifest.json            # package/features(interconnect+sensor+vibrator+fetch)/router
  app.ux                   # 连接恢复 → 请求 Presence；前台加速度计订阅/退订
  pages/echo/index.ux      # TIME + ECHO ORGANISM + 一行公开表达（默认无）
  pages/why/index.ux       # WHY（headline 来自手机；降级时安静）
  pages/action/index.ux    # BREATHING / PAUSE（同一个手机 Action）
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
