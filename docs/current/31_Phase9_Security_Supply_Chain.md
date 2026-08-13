# Phase 9.3 — Security Supply Chain 现状与补充计划

> 生成：2026-08-12 · 说明：不抢占 P0/P1 Portrait Closure；补充项为 v0.8 计划。

## 现有（保留）

| 项 | 位置 | 状态 |
|---|---|---|
| TruffleHog | .github/workflows/security-ci | 已配置 |
| SBOM | `sbom.spdx.json` + scripts/generate_sbom.py | 已配置 |
| Python CodeQL | .github/workflows | 已配置 |
| pip-audit | security CI | 已配置 |
| OSV | security CI | 已配置 |
| Trivy | security CI（容器镜像扫描） | 已配置 |

## 补充计划（v0.8，不抢占 P0）

| 项 | 说明 | 优先级 |
|---|---|---|
| Kotlin/Java SAST | detekt / SpotBugs（Gradle 集成） | P1 |
| Gradle dependency verification | gradle/verification-metadata.xml（依赖校验和） | P1 |
| GitHub Actions immutable SHA pinning | actions 用 commit SHA 而非 tag 引用 | P1 |
| Provenance / attestation | SLSA provenance 生成（release artifact 签名） | P2 |

## 本环境验证

- `python3 scripts/generate_sbom.py`：本机可运行（SBOM 从依赖树生成）
- TruffleHog / CodeQL / pip-audit / OSV / Trivy：需 CI 环境（本机不运行，如实标记）

> 结论：supply chain 基线保留完整；补充项列入 v0.8 计划，不影响 Portrait Core 封板。
