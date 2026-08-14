# Clean-room Reproduction（ERA 18 §96）

> 目标：社区开发者能够验证「这个 APK 确实来自这一份源码」。
> 本文档记录等价可重现构建所需的环境、锁定状态与步骤；与
> `BUILD_PROVENANCE.json` / `RELEASE_ARTIFACT_MANIFEST.sha256` 互为交叉验证。

## 1. 锁定状态（dependency lock state）

| 组件 | 锁定方式 | 事实源 |
|---|---|---|
| JDK | 17（实测 Corretto 17.0.20；CI `setup-java` 固定 major，provenance 记录 `jdk_version`） | `BUILD_PROVENANCE.json` |
| Gradle | **8.13** wrapper + `distributionSha256Sum`（`20f1b117…ed78`，官方发行校验和） | `android/gradle/wrapper/gradle-wrapper.properties` |
| Android 依赖 | **Gradle dependency locking**：全部模块 `gradle.lockfile`（10 个，`lockAllConfigurations()`；升级须显式 `./gradlew dependencies --update-locks`） | `android/**/gradle.lockfile` |
| Kotlin/AGP/KSP/Room/Compose | 版本目录锁定（AGP 8.13.2 / Kotlin 2.3.20 / KSP 2.3.9 / Room 2.8.4 / Compose BOM 2026.06.00）+ lockfile 双保险 | `android/gradle/libs.versions.toml` + lockfiles |
| Python | 3.12（实测 3.12.13）；backend 依赖按 `pyproject.toml` 版本约束（CI pip-audit + osv-scanner 审计） | `backend/pyproject.toml` |
| Android SDK | compileSdk/targetSdk 36、minSdk 26、build-tools 36.0.0 | `android/app/build.gradle.kts` |
| Actions | 全部 64 个 `uses:` pin 到 immutable commit SHA（`scripts/verify_workflow_pins.py` CI 门禁） | `.github/workflows/*.yml` |

## 2. APK 内嵌构建信息（§95）

`BuildConfig`：`BUILD_VERSION`（= versionName 0.9.0）、`GIT_COMMIT`（HEAD 40 位 SHA，
CI 无 git 时 `unknown`）、`BUILD_TIMESTAMP`（HEAD 提交时间 ISO；发布流水线可
`-PECHO_BUILD_TIMESTAMP` 显式注入）。Me → About 展示三要素（`BuildInfoTest` 断言
格式与无敏感 CI 信息）。

## 3. 复现步骤（clean checkout → 产物链）

```bash
# 0) 环境：JDK 17 + Android SDK（build-tools 36.0.0 / platform android-36）+ Python 3.12
git clone <repo> && cd <repo> && git checkout <release-commit>
python3 scripts/verify_source_manifest.py        # Git source = SOURCE_MANIFEST
cd android
./gradlew testDebugUnitTest lintDebug detekt assembleRelease \
    -PECHO_API_BASE_URL=https://echo-mind.example.invalid   # 依赖按 lockfile 解析
cd ..
# 1) 签名（发布者密钥；社区验证用 apksigner verify 校验签名与 provenance）
apksigner sign --v1-signing-enabled false --ks <release-keystore> \
    --out ECHO_Mind_v0.9.0.apk android/app/build/outputs/apk/release/app-release-unsigned.apk
# 2) 确定性 source archive（同 commit 同字节：NFC + UTF-8 标志 + commit 时间戳）
python3 scripts/build_source_archive.py --out-dir releases
python3 scripts/verify_source_archive.py releases/ECHO_Mind_PortraitCore_v0.9.0.zip
python3 scripts/verify_source_archive.py releases/ECHO_Mind_PortraitCore_v0.9.0.tar.gz
# 3) SBOM → provenance（root APK 绑定）→ delivery → artifact manifest
python3 scripts/generate_sbom.py
ANDROID_GRADLE_BUILD_RESULT=passed REUSE_REPORT=1 python3 scripts/update_release_metadata.py
python3 scripts/generate_provenance.py --require-clean
# 4) final package + §18 门禁 + §94 release set 完整性
python3 scripts/build_final_package.py --out-dir releases
python3 scripts/verify_final_package.py releases/ECHO_Mind_v0.9.0.release.zip
python3 -m pytest scripts/test_release_set.py -q
```

CI 参考实现：`.github/workflows/release-closure.yml`（§17 原子流程，同一 run 全链）。

## 4. 可复现性边界（诚实声明）

- **字节级可复现**：source archive（zip/tar.gz）——同 commit 同字节（NFC/UTF-8/时间戳确定）；
  `SOURCE_MANIFEST` 与 git 受控文件集恒等（`verify_source_manifest.py` 哈希逐文件复核）。
- **等价可复现**：unsigned APK——同 commit + lockfile + JDK 17 + SDK 36 工具链下产物一致
  （BuildConfig 内嵌 commit/time 确定；gradle 缓存不影响产物字节）。
- **可验证但非字节恒等**：signed APK（v2/v3 签名块含签名者与时间戳字段）——社区验证路径 =
  `apksigner verify` 签名有效 + `release_apk_sha256`/`unsigned_apk_sha256` 与 provenance 交叉绑定
  + 包内 archive 递归验证（`verify_final_package.py` §18 终态门禁）。
- **发布者签名密钥**不入库、不进 provenance；provenance 只记录签名阶段与方案（v2,v3）。
