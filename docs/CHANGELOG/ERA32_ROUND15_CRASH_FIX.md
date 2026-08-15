# ERA 32 R15 — 真机首启闪退修复（冷启动容器构建失败隔离）

> 2026-08-15。真实用户反馈（首次真机安装 v0.11.0）：「安装直接闪退」——ERA 32 第一条
> 真机缺陷报告（ANDROID_RUNTIME_DEFECT，P1）。

## 1. 代码级根因审计（无设备日志约束下的静态定位）

冷启动链（首帧前同步执行，此前零隔离）：
`MainActivity.onCreate` → `AppContainer` → `CoreContainer` →
① `AndroidKeystoreKeyProvider`（**fail-closed：AndroidKeyStore 不可用即抛 IllegalStateException，绝不降级**）
② `AndroidKeystoreFieldCipher.ensureSecret`（受保护秘密供给）
③ `openDatabase` → SQLCipher `SupportFactory` 建库。

该生产链从未被任何自动化测试覆盖（Robolectric 全走 JvmTestFieldCipher + 内存 Room），
v0.10.0/v0.11.0 的 Release Closure 也从未做真机 smoke（外部门一直未执行）——任何设备侧
Keystore/原生库异常都会在首帧前裸崩，即「打开就闪退」。

排除项（已实测）：R8 未剥离任何 app/关键 androidx 类（usage/mapping 复核）；
四 ABI 原生库齐全；manifest/startup provider 完整；release 变体测试失败为 Robolectric
基础设施（activity 解析），非产品代码。

## 2. 修复

`MainActivity` 容器构建改 `runCatching` + 兜底画面 `ContainerInitFailedScreen`：
- 构建失败不再闪退——显示「ECHO 没能安全地启动」+ 异常类名（仅类名，不泄漏消息/路径）+ 重试/退出；
- 数据零影响（fail-closed 未降级，保持安全契约）；
- 异常类名可见 = 用户可反馈精确根因，下轮定点修复。

## 3. 回归与交付

- `ContainerInitFailedScreenTest`（Robolectric Compose）：文案 + 重试/退出回调双锚点；
- 全量 `testDebugUnitTest` + detekt + lint 绿（WindowAckTest 一次并行污染 flake，孤立重跑与全量重跑均绿，与本次改动无关）；
- 热修复 APK：`ECHO_Mind_v0.11.0.hotfix1.apk`（SHA-256 `1ef3342d…`，v2/v3 签名，新测试密钥——
  与 v0.11.0 发布包签名不同，安装前需先卸载旧版）。

## 4. 待用户反馈

安装 hotfix1 后若仍异常，屏幕会显示具体异常类名（如 KeyStoreException / UnsatisfiedLinkError）——
据此可完成根因定点修复（Keystore 兼容 / 原生库 ABI / OEM 差异）。
