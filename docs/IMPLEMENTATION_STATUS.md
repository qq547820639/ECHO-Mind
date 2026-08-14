# ECHO Mind Implementation Status

> 本文件是长期自主演进的**唯一状态锚点**（v2 §92 格式）。
> 最高产品原则：`docs/product/ECHO_PRODUCT_CONSTITUTION.md` + Master Prompt v1/v2。

## Current Era

**v2 第一/二轮全部完成 → 产品进入纯优化周期（v0.9.0）✅**
预留事项清单：**清零**（Known Boundaries 均为最终产品边界决策，非遗留工程项）。

## Current Product Surface

- 一级导航：**ECHO（EchoSceneScreen）/ Journey（TrendScreen）/ Me（SupportScreen）**（v2 §3）
- ECHO Scene：生命场 + 一句话（fallback 链）+ 为什么？（Layer 2）+ 查看更多 → Journey（Layer 3）+ 问 ECHO（依据双清单 + 反馈纠错）+ 行动（Scene 内 + 订阅分区）+ 初次 AI 非阻塞提示
- Journey：天/周/月/**季（90d）/年（365d）** 五尺度视觉记忆河流 + 长期叙事（Context Retriever 真实检索）
- Me：AI Intelligence（Current provider / **测试连接四步** / 更换 / 断开）+ ECHO Presence + What ECHO Knows（编辑/确认/忘记）+ 数据与感知
- 运行时：`EchoRuntimeCoordinator`（六态/Presence/Provider 统一广播）+ `EchoSceneUiState` 纯函数装配 + `EchoContextRetriever` 真实检索

## Completed Vertical Slices

- v1 ERA 1-10（宪法/Scene/Presence/BYOM/ContextCompiler/Memory/Conversation/Journey/Actions/Affective 契约）——`RELEASE_NOTES_v0.8.0.md`
- **v2-1 应用壳接管**：三世界导航；EchoRuntimeCoordinator；EchoSceneUiState；Progressive Why 三层；SkillListScreen 删除
- **v2-2 AI 链路收口**：Provider 测试连接四步；初次 AI 提示；Context Retriever（timeWindow/memoryPolicy 实例化）；对话依据双清单 + 反馈 → Correction Memory；Journey 季/年尺度（backend 窗口 365）；版本 v0.9.0

## In Progress

- 无。

## Architecture Decisions

- ADR-001~023（最新：ADR-022 Context Retriever 真实检索 / ADR-023 Provider 测试连接 + 对话依据反馈闭环）

## Legacy Remaining（全部已处置：keep=最终决策，delete 已完成）

| 模块 | 分类 | 处置 |
|---|---|---|
| `SkillListScreen`（旧「能力」Tab 全页） | **deleted** | v2-1 已删除 |
| `rememberSkillList / coldStartHint / SkillCardHost` | keep | ECHO Scene「更多能力（订阅）」分区使用 |
| `TrendScreen` 七态/NO_DATA 纯函数 | keep | Journey 世界复用 |
| `SupportScreen`「同步」区块与时间戳 | keep | Me → 数据与感知（信任控制中心） |
| backend legacy 410 存根 | keep | migration compatibility（机构历史数据只读） |
| `LegacyScreens.kt`（3 个停用文案常量） | keep | 单测不变量锚点（DeprecatedInputRemovalTest） |

## Known Bugs

- 无已知阻塞 bug。

## Tests

- Android：**449 tests 全绿**；assembleDebug / lintDebug（0 errors）/ detekt 全 PASS
- backend：**1070 passed + 1 skipped（1071 收集全绿）**；days 窗口 365 + openapi 一致性 PASS

## Performance / Security

- 生命场 draw-phase 渲染（无每帧 recomposition）；Wallpaper 不可见 0 CPU；Secret Keystore 隔离；Provider 探测请求不含个人数据。

## Next Highest-Value Slice（纯优化周期，v2 §110）

1. 真机矩阵验收：Wallpaper/Dream 能耗与帧率、Awakening 动画、Provider 真实服务连通；
2. 视觉质量：Identity Genome 长期演化、Life Season 拓扑漂移；
3. 推理质量：AI Evaluation fixtures（grounding/幻觉/纠错尊重）；
4. 物理模块化：domain boundary 清晰后拆 Gradle module；
5. 多设备架构（下一产品周期，ADR-020 已裁决）。

