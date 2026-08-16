# ECHO WRIST SIGNING — Phone ↔ Wrist 签名身份（Phase 8）

> 状态：文档与工具已就绪；生产签名材料 → `BLOCKED_EXTERNAL_PRODUCTION_SIGNING`（私钥永不进仓库）。
> 权威事实源：`docs/wearable/XIAOMI_BAND10_CAPABILITY_MATRIX.md` §1.3（官方 interconnect 身份要求）。

## 1. 为什么必须匹配签名

官方 Vela interconnect 的身份规则（[官方 interconnect 文档](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html)
"Development Considerations"）：

1. 手环端 Quick App `manifest.json` 的 `package` 必须与手机端 Android 应用**包名完全一致**
   （本仓库由 `AndroidWearPackageParityTest` 自动锁死）；
2. Quick App（.rpk）的签名必须使用该 Android 应用**同一签名**——
   两端证书指纹不匹配时系统 interconnect 拒绝建立身份关联（错误码 1001 类场景的签名侧）。

**签名不是装饰**：它是"手机 ECHO 与手环 ECHO 是同一个 ECHO"在系统层的身份锚点。
签名不匹配 → Phone ↔ Wrist 永远连不通，即使协议代码全对。

## 2. 身份链

```
Android signing identity（.jks / .keystore）
        ↓ 官方转换（jks → p12 → pem）
Vela private.pem  +  certificate.pem（RPK 签名）
        ↓
相同证书指纹 = 同一身份
```

- Android 侧：APK 用 keystore 签名（v2/v3）。
- Vela 侧：AIoT-IDE 打包 RPK 时使用同一证书导出的 `private.pem` / `certificate.pem`。
- 验证：`scripts/verify_wrist_signing.py` 读取两端证书指纹，输出 `MATCH` / `MISMATCH`。

## 3. 绝不进 git（.gitignore 已排除）

| 材料 | 处理 |
|---|---|
| Android keystore / `.jks` / `.keystore` | 不进仓库（已有 .gitignore 规则） |
| `private.pem`（RPK 私钥） | 不进仓库 |
| `certificate.pem` 私钥侧材料 / `.p12` | 不进仓库 |
| 任何密码（keystore/p12/pem 口令） | 不进仓库；生产环境 secret injection |

允许进仓库：本说明文档、`scripts/verify_wrist_signing.py`（只读指纹，不输出私钥）、
构建脚本与验证脚本。

## 4. 开发环境：ECHO Wrist Debug Signing Identity

真机 interconnect 联调要求**同一对**证书：

- **Android debug APK** 使用 ECHO Wrist Debug keystore（如 `~/.echo-signing/echo-wrist-debug.jks`，
  本机生成、永不提交）；
- **Vela debug RPK** 用同一证书导出的 `private.pem` / `certificate.pem`
  （`~/.echo-signing/echo-wrist-debug/`，AIoT-IDE 打包时选用）。

生成参考（本机，密码只进本机 keychain/密码管理器）：

```bash
mkdir -p ~/.echo-signing/echo-wrist-debug && cd ~/.echo-signing/echo-wrist-debug
keytool -genkeypair -v -keystore echo-wrist-debug.jks -alias echo-wrist-debug \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass <本机秘密> -keypass <本机秘密> \
  -dname "CN=ECHO Wrist Debug, OU=Dev, O=ECHO Mind, L=Local, C=CN"
# jks → p12 → pem（官方在线工具或本地 openssl/keytool 均可）
keytool -importkeystore -srckeystore echo-wrist-debug.jks -destkeystore echo-wrist-debug.p12 \
  -deststoretype PKCS12 -srcstorepass <秘密> -deststorepass <秘密>
openssl pkcs12 -in echo-wrist-debug.p12 -out private.pem -nocerts -nodes
openssl pkcs12 -in echo-wrist-debug.p12 -out certificate.pem -nokeys
```

之后：

1. Android debug build 用 `echo-wrist-debug.jks` 签名（CI 或本地 `signingConfig`）；
2. AIoT-IDE 打包 Vela debug RPK 用上述 `private.pem` / `certificate.pem`；
3. `python3 scripts/verify_wrist_signing.py --apk <debug.apk> --vela-cert <certificate.pem>` 必须输出 `MATCH`。

## 5. 生产签名（BLOCKED_EXTERNAL_PRODUCTION_SIGNING）

- 缺失资源：生产签名私钥/证书（运营环境持有，不进仓库）。
- 下一步人工动作：运营环境注入签名 → CI secret injection → 构建签名 RPK + APK。
- 禁止的宣称：在材料到位并真机验证前，不得宣称"生产签名完成"。

## 6. 验证工具行为

`scripts/verify_wrist_signing.py`：

- 读 Android APK 证书指纹（优先 `apksigner verify --print-certs`，退化 `keytool -printcert`）；
- 读 Vela `certificate.pem` 指纹（`openssl x509 -fingerprint -sha256`）；
- 输出 `MATCH` / `MISMATCH` / 缺失报告；
- **绝不输出私钥内容、密码**；只输出指纹（指纹不是秘密）。
