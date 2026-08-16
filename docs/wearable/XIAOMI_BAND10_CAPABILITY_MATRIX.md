# XIAOMI BAND 10 — CAPABILITY MATRIX

> 事实源纪律：本矩阵只记录**官方文档事实**，禁止"我觉得应该支持"。
> 状态取值：`SUPPORTED_PUBLIC` / `SUPPORTED_REQUIRES_VENDOR_SDK` / `UNSUPPORTED_PUBLIC` / `UNKNOWN` /
> `BLOCKED_EXTERNAL_VERIFICATION` / `UNVERIFIED_CURRENT_DOCS`。
>
> 核实日期：2026-08-16（实施当天重新核对）。所有结论附官方来源 URL。

## 0. 设备事实（官方规格）

| 项目 | 值 | 来源 |
|---|---|---|
| 型号 | Xiaomi Smart Band 10（含 Glimmer / Ceramic 版本） | [mi.com UK specs](https://www.mi.com/uk/product/xiaomi-smart-band-10/specs/) |
| 屏幕 | 1.72-inch AMOLED Touch Display，**212 × 520 px** | 同上（官方 specs 页 "Display Resolution 212 x 520 pixels"） |
| 传感器（硬件） | Accelerometer, Gyroscope, Electronic compass, Optical heart rate & pulse oximeter, Ambient light sensor | 同上 |
| 平台 | Xiaomi Vela OS，支持 **Vela JS 快应用**（Quick App / RPK） | [Vela Quick App 官方文档](https://iot.mi.com/vela/quickapp/) |

**设计约束**：212×520 为狭长屏，禁止把 Android UI 缩小复制；按窄带纵向布局设计。

## 1. Vela JS 应用能力（手环端）

| 能力 | 状态 | 官方事实 |
|---|---|---|
| Band 10 支持 Vela JS 应用 | `SUPPORTED_PUBLIC` | 官方 sensor 页支持列表明确列出 "Xiaomi Band 10"；Vela Quick App 文档面向 Band 10 开放 |
| 项目结构：manifest.json + .ux（template/style/script）+ pages | `SUPPORTED_PUBLIC` | [项目配置](https://iot.mi.com/vela/quickapp/zh/guide/framework/manifest.html) |
| manifest：package/name/icon/versionName/versionCode/minAPILevel/features/config(logLevel,designWidth,background)/router/display/deviceTypeList/permissions | `SUPPORTED_PUBLIC` | 同上 |
| 页面路由 router.entry + pages（component 对应 .ux 文件名） | `SUPPORTED_PUBLIC` | 同上 |

### 1.1 传感器

| 能力 | 状态 | 官方事实 |
|---|---|---|
| `system.sensor.subscribeAccelerometer` / `unsubscribeAccelerometer` | `SUPPORTED_PUBLIC` | 官方支持列表含 "Xiaomi Band 9/9 Pro, **Xiaomi Band 10**, Xiaomi Watch S5"。回调 x/y/z；interval：game≈20ms / ui≈60ms / normal≈200ms。[官方 sensor 文档](https://iot.mi.com/vela/quickapp/en/features/system/sensor.html) |
| `system.sensor.subscribePressure` / `unsubscribePressure` | `SUPPORTED_PUBLIC` | 官方支持列表含 "Xiaomi Watch S3, Xiaomi Band 9 Pro, **Xiaomi Band 10**, Xiaomi Watch S4, S5"。回调 pressure（hPa 浮点）。[官方 sensor 文档](https://iot.mi.com/vela/quickapp/en/features/system/sensor.html) |
| `system.sensor.subscribeCompass` | `UNSUPPORTED_PUBLIC` | 官方支持列表明确把 Band 10 列入 **不支持** 设备（仅 Watch S4 / REDMI Watch 5 / 6 / Watch S5）。 |
| 传感器后台持续订阅（24h sensor loop） | `UNSUPPORTED_PUBLIC` | 官方[后台运行文档](https://iot.mi.com/vela/quickapp/zh/guide/framework/other/background-running.html)：应用切后台即停止，除非 manifest `config.background.features` 声明后台接口；可申请的只有 `system.audio` / `system.request` / `system.geolocation`。**传感器不在允许列表**。→ 本产品不做 Vela JS 传感器后台守护，不 hack 保活。 |

### 1.2 振动

| 能力 | 状态 | 官方事实 |
|---|---|---|
| `system.vibrator.vibrate({mode})`，mode ∈ {`"short"`, `"long"`} | `SUPPORTED_PUBLIC` | 官方支持列表含 "**Xiaomi Band 10**"（vibrate 行）。[官方 vibrator 文档](https://iot.mi.com/vela/quickapp/en/features/system/vibrator.html) |
| `system.vibrator.start/stop`（任意 pattern：duration/interval/count） | `UNSUPPORTED_PUBLIC` | 官方支持列表把 Band 10 明确列入 start/stop **不支持** 设备（仅 Watch S5）。 |
| `system.vibrator.getSystemDefaultMode` | `UNSUPPORTED_PUBLIC` | 同上（仅 Watch S5）。 |

**Haptic Constitution 结论**：v1 只依赖 `vibrate({mode:'short'|'long'})`。不假设任意 pattern。

### 1.3 通信与网络

| 能力 | 状态 | 官方事实 |
|---|---|---|
| `system.interconnect`：与配对手机 App 自动建连、`connect.send(Object)`、`connect.onmessage(String)`、`onopen(isReconnected)`、`onclose`、`onerror`、`getReadyState`（1=连接成功，2=断开） | `SUPPORTED_PUBLIC` | [官方 interconnect 文档](https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html)。连接由系统自动建立/维持，应用内不需管理连接生命周期。错误码 1001 = 手机端 App 未安装。 |
| interconnect 身份要求：Quick App `manifest.json` 的 `package` 字段必须与手机端 Android 应用包名一致；Quick App 签名必须使用该 Android 应用同一签名（jks → p12 → pem 提取 private.pem/certificate.pem） | `SUPPORTED_PUBLIC`（规则公开；**生产签名材料** → `BLOCKED_EXTERNAL_PRODUCTION_SIGNING`） | 官方 interconnect 文档 "Development Considerations" 节。任何私钥/证书/pem 一律不进 git，CI secret injection。 |
| `system.fetch`：GET/POST/PUT/DELETE 等 + responseType text/json/file/arraybuffer | `SUPPORTED_PUBLIC` | [官方 fetch 文档](https://iot.mi.com/vela/quickapp/en/features/network/fetch.html)（核心网络接口）。 |
| 通用 BLE（generic BLE peripheral/central API，用于连接第三方 ANSWatch 硬件） | `UNKNOWN` | 官方文档侧边栏存在 "Bluetooth bluetooth" 模块，但本实施未能定位到面向 Band 10 的**通用 BLE 数据传输**公开 API 文档（该模块在可穿戴场景指向音频类蓝牙能力）。结论：**不 reverse engineer、不通过私有通道接 ANSWatch**；ANSWatch 自有硬件未来走其自身官方 BLE → Android 手机路径。 |

## 2. Android 手机端 SDK（Xiaomi 穿戴第三方 App 能力开放）

来源：[小米穿戴第三方APP能力开放接口文档 v1.4](https://www.aristore.top/posts/mi_band_app_dev/)（官方文档镜像；官方开发平台提供 SDK 本体）。

| 能力 | 状态 | 官方事实 |
|---|---|---|
| SDK 本体（`Wearable` 类 + NodeApi/AuthApi/DataApi/MessageApi/NotifyApi 的官方 AAR） | `SUPPORTED_REQUIRES_VENDOR_SDK` + `BLOCKED_EXTERNAL_XIAOMI_SDK`（本环境未持有 SDK；文档 v1.4 公开可读） | v1.4 兼容 Android R；1.3 起兼容小米穿戴/小米健康合并项目，api 无变化直接替换 sdk。**未拿到 SDK 前禁止猜 class/interface 签名写死实现**。 |
| 查询已连接设备：`Wearable.getNodeApi(context).getConnectedNodes()`（不需要权限；目前一次只能连接一个设备） | `SUPPORTED_REQUIRES_VENDOR_SDK` | 文档 §1 |
| 权限：`AuthApi.checkPermission/checkPermissions/requestPermission`，`Permission.DEVICE_MANAGER`、`Permission.NOTIFY`；首次授权默认授予两项 | `SUPPORTED_REQUIRES_VENDOR_SDK` | 文档 §2 |
| 设备状态查询（需 `Permission.DEVICE_MANAGER`）：连接状态 / 电量（0~100）/ 充电状态 / 佩戴状态 / 睡眠状态 | `SUPPORTED_REQUIRES_VENDOR_SDK` | 文档 §3.1/§3.2 |
| 设备状态订阅（需 `Permission.DEVICE_MANAGER`）：连接（成功/断开/失败/设备被删除）、充电（开始/完成/停止）、佩戴（佩戴/未佩戴）、睡眠（入睡/出睡）；**电量只可查询不可订阅** | `SUPPORTED_REQUIRES_VENDOR_SDK` | 文档 §3.1/§3.3 |
| 手环端应用安装检测：`NodeApi.isWearAppInstalled(nodeId)` | `SUPPORTED_REQUIRES_VENDOR_SDK` | 文档 §3.2 |
| 打开手环端应用：`NodeApi.launchWearApp(nodeId, uri)`（uri 自定义页面） | `SUPPORTED_REQUIRES_VENDOR_SDK` | 文档 §3.2 |
| 应用间消息：`MessageApi.sendMessage(nodeId, byte[])` + 注册/取消消息监听 | `SUPPORTED_REQUIRES_VENDOR_SDK` | 文档 §4 |
| 消息通知（系统通知，手环端应用无感知）：`NotifyApi.sendNotify(nodeId, title, message)`（需 `Permission.NOTIFY`） | `SUPPORTED_REQUIRES_VENDOR_SDK` | 文档 §5。ECHO v1 **不使用** 该能力做 Presence 通知（低打扰宪法）。 |
| 服务连接状态管理（`Wearable.getServiceStateApi` 类接口，不需要权限） | `SUPPORTED_REQUIRES_VENDOR_SDK` | 文档 §6 |

## 3. 本产品对能力的使用决定

| 决定 | 依据 |
|---|---|
| v1 只依赖 `vibrate short/long`；禁止 pattern 振动 | 官方矩阵 §1.2 |
| 加速度计只在**应用前台**订阅，5–15s 本地窗口输出 summary，不发 raw | 官方矩阵 §1.1（无传感器后台）；[后台运行文档](https://iot.mi.com/vela/quickapp/zh/guide/framework/other/background-running.html) |
| pressure：v1 仅 CAPABILITY / DIAGNOSTIC，不进 Portrait/Presence/Memory/Journey | 官方矩阵 §1.1（能力存在，产品价值未验证） |
| 持续感知不来自 Vela JS daemon；stock Band 持续能力 = system/vendor companion state（Android SDK 状态订阅） | 官方矩阵 §1.1 / §2 |
| 佩戴/睡眠状态：Android SDK 支持 → 经真机验证 + 用户显式授权后才进入 neutral context；未验证前保持 `UNKNOWN` | 官方矩阵 §2；ECHO_WRIST_CONTRACT |
| ANSWatch 硬件不经过手环中转（无公开 generic BLE 事实） | 官方矩阵 §1.3 |

## 4. 外部阻塞（唯一允许保留的 BLOCKED_EXTERNAL_*）

| 标记 | 缺失资源 | 已完成的软件侧工作 | 确切的下一步人工动作 | 禁止的宣称 |
|---|---|---|---|---|
| `BLOCKED_EXTERNAL_XIAOMI_SDK` | 官方 Xiaomi 穿戴 SDK AAR（文档 v1.4 已读，SDK 本体未获得） | 协议/domain/Vela/Noop+Fake 适配器/全部单元测试已完成 | 从官方开发者平台获取 SDK AAR，按 `docs/wearable/ECHO_WRIST_CONTRACT.md` §Android Vendor Boundary 在 vendor-enabled build 接入 | 不得宣称"Android SDK 已集成验证" |
| `BLOCKED_EXTERNAL_BAND10_DEVICE` | 真实 Xiaomi Band 10 设备 | 全部可在模拟环境验证的软件闭环已完成 | 真机执行 `qa/`（如有）设备清单：RPK install / package/signature / interconnect / 消息延迟 / 断连重连 / launchWearApp / accelerometer / vibrate / 8h/24h 稳定性 / 电池 | 不得宣称 "Band10 Production Verified"（允许 Developer Preview / Integration Preview） |
| `BLOCKED_EXTERNAL_PRODUCTION_SIGNING` | 生产签名私钥/证书（不进仓库） | 开发测试密钥构建与全部测试完成 | 运营环境注入签名 → CI secret injection 构建签名 RPK + APK | 不得宣称"生产签名完成" |
| `BLOCKED_EXTERNAL_ANS_HARDWARE` | ANSWatch 真机硬件 | ANS_FRAME_V1 schema + Golden frames + Kotlin decoder + mapper + promotion policy + 测试全部完成 | 硬件到位后先验 clock/on-body/SQI/missingness/motion artifact/baseline/UNKNOWN rate，再验 physiological activation，最后才研究 stress | 不得宣称"ANSWatch 硬件已验证"；stress/affective 永远 RESEARCH_ONLY（AFFECTIVE_CONTRACT 冻结） |
