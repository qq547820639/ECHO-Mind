# ERA 31 Round 41 报告（Release Build Readiness：R8 全量构建通过）

> 日期：2026-08-15。

## 背景

R11–R40 改动后未验证过 release 构建路径（R8 压缩 + release 资源/网络策略）。
下一次 Release Closure 的第一步就是 assembleRelease——提前证明 dev head 可发布，
不带旧 proof、也不等 closure 当天才发现 R8 冲突。

## 实测

- `./gradlew assembleRelease -PECHO_API_BASE_URL=https://echo-mind.example.invalid
  -PECHO_GIT_COMMIT=<HEAD>`（与 closure 同参数钉定）**BUILD SUCCESSFUL**：
  - R8 minify + resource shrink 全量通过（92 tasks executed）；
  - 产出 `app-release-unsigned.apk`（15.7MB，本地未签名——签名/归档/元数据
    留给下一次正式 Release Closure，本轮不触碰 release 元数据）。
- Journey 365 天装配预算（§53）与 1k/5k/20k 记忆检索预算（§54）在既有
  PerformanceBaselineTest 中保持锁定（历轮全量门禁覆盖）。

## 结论

Development Head 处于 release-ready 状态；Release Baseline 仍为 v0.10.0
（纪律不变：closure 时才重新生成全部 proof）。