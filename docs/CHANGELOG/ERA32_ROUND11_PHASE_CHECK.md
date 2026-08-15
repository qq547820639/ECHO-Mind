# ERA 32 — 阶段终检（R11 全门禁复核）

> 2026-08-15。R01–R10 全部合入后的一次全量复检：证明 10 轮改动零回归，
> 并把外部依赖批次的状态如实收敛。本文件是 ERA 32 在环境内的收官证据。

## 1. 全门禁实测（当前 HEAD）

| 门 | 结果 |
|---|---|
| Android 全模块单测 | 全绿（自动计数见 `docs/STATUS.md` §3） |
| detekt + lintDebug | 全绿 |
| backend pytest | 1077 passed + 1 skipped |
| ruff / mypy strict | 0 / 0 |
| SOURCE_REALITY 漂移门 | 本轮重生成并入（166/123/36 —— R03 MemoryHumanizer 增量此前未入档，已修复） |
| ANDROID_DEPENDENCY_GRAPH 漂移门 | 本轮重生成并入（memory 6→7） |
| CONTRACT_COMPLIANCE | 24/24 |
| verify_source_manifest | 1089 PASS |
| test_release_set + test_source_archive | 16/16（v0.11.0 发布包完整性在 10 轮后保持） |

## 2. ERA 32 十轮交付总览

| 轮次 | 交付 | 缺陷修复 |
|---|---|---|
| R01 | 治理减法：Source Reality 多模块自动发现 / 状态文档收敛 / 数字自动生成 | NotificationCollector 误报 Missing |
| R02 | 26/26 答案四层复核 | q032 答非所问 / z 距离泄漏 / 无变化结论不可验证 / 漂移文案硬编码 |
| R03 | Context / Correction 深度走查 | 上下文永不过期（P1）/ AI 路径内部格式泄漏 |
| R04 | Delete Review | v0.7 遗留零消费函数 ×2 |
| R05 | **v0.11.0 完整 Release Closure**（版本收口 + 全链 + provenance + 16/16） | SBOM 环境陷阱 / APK 全 hash 钉定 / closure 后清单纪律 |
| R06 | Journey 90 天测试（§41 四问） | 数据不足妄断「平稳」/ shift 检测工程键泄漏 / QA z 镜像退役 |
| R07 | Memory/Journey 规模护栏（20k / 1000d） | — |
| R08 | Old Me（§32） | 静默陈旧模式现在时（把过去当现在） |
| R09 | §52 依据词表精确化 | 「没有使用：麦克风」假声明 |
| R10 | §35 隔离审计 + 模块清单自动发现收尾 | —（回归加固） |

**共修复 9 个真实产品缺陷，全部 fixture 化回归。**

## 3. 外部依赖批次状态（如实收敛）

- **Batch B 真机**：无设备；DEVICE_CHECKLIST + collect_wallpaper_metrics.sh 就绪（本轮复核：协议级设计，不受 R02–R10 文案变化影响，无过时引用）。
- **Batch D dogfood**：协议就绪（六类缺陷 + 每日五问模板）；真实数据回流后执行 §19 fixture 化。
- **Batch E/F 深水区**：Journey/Memory 已按 §41/§58/§59/§32 在 fixture 尺度预检通过；
  真实长期数据评估仍待 dogfood。
- **Affective**：契约冻结不变（affectiveState 恒 null，测试强制）。

## 4. 下一轮

环境内高价值审计面已收敛。持续节奏：每轮 Delete Review + 定期全门禁复核 + 答案复核复跑；
外部数据/真机可用即切换对应批次。
