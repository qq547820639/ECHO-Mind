# VISUAL_RUNTIME_V3 — UI 报告

> IMPLEMENTATION_HEAD=`c4ff17be9a116884b4a6e46ca5c07b6e0c615401`（分支 `visual-runtime-v3`）

## 1. ECHO Scene（§37–§43）

| 项 | 状态 | 证据 |
|---|---|---|
| READY = Scene 非 Feed（Box 单结构） | PASS | `EchoSceneContent` 重写；`echoSceneIsNotAFeed` 回归锁 |
| 首 viewport organism ≥52%（目标 61.5%） | PASS | `visualFractionFor`（1.0→0.615 / ≥1.3→0.52）+ `visualRegionRatioWithinSpec` |
| 首屏 major Card=0 / chart=0 / numeric KPI=0 | PASS | `firstViewportHasNoFeedItems` + 源码无 Card/chart |
| Why 48dp / Ask 52dp 在场 | PASS | `firstViewportHasOrganismNarrativeWhyAsk` + testTags |
| 移除项迁移（feedback→WHY / actions→sheet / conversation→Ask / wallpaper·AI→pill / Journey 重复入口→WHY 内） | PASS | smoke 全套 20 项 |
| LOADING quiet（无 spinner） | PASS | `loadingShowsQuietEchoCopyWithoutSpinner`（PORTRAIT_COPY_LOADING_QUIET） |
| SENSING_DISABLED（identity 保留 + 真实 re-enable + motion×.30/detail×.55） | PASS | `sensingDisabledKeepsIdentityAndOffersReEnable` + renderer options 链 |
| ERROR（identity + secondary retry，非红色全屏） | PASS | `errorKeepsIdentityWithSecondaryRetry` |

## 2. Progressive surfaces（§44–§47）

| 项 | 状态 | 证据 |
|---|---|---|
| WHY PEEK 42% / EXPANDED 82%（九段内容；默认无 chart） | PASS | `whySheetShowsEvidenceThenFeedbackAfterExpand` + `whyExpandedShowsWindowCoverageSourceNotUsed`（时间窗口/coverage/source/未使用 全真实字段） |
| Correction 视觉脉冲（不只 Toast） | PASS | §45 链 + `correctionPulseTimeline` |
| Ask 保留 mini live ECHO（顶部 28–34%）+ 非气泡墙 | PASS | `askSheetRetainsLiveEchoAndConversation`（identity glyph 用 identity palette） |
| Action sheet：真实 actions + 选择后关闭 → 现有 Runtime/overlay | PASS | `actionSheetHostsAvailableActions` + `actionSheetClosesWhenActionStarts` |

## 3. Shell / Onboarding / Journey / Me

| 项 | 状态 | 证据 |
|---|---|---|
| 三世界导航（quiet bar；不加 Tab；Role.Tab；≥48dp） | PASS | `QuietWorldBar`（§48 视觉参数）+ app 单测绿 |
| Crisis 可达性不降（非 Me 一键 FAB，error 语义） | PASS | `shouldShowEmergencyFab` 不变 + 回归锁 |
| Onboarding 无 DONE / 无 legacy Page / 2200ms / 增强权限未回流 | PASS | `onboardingHasNoDoneAndNoLegacyPage` + `AwakeningTimelineTest` + 既有 onboarding smoke |
| Awakening same identity + Home 连续 | PASS | 同一 identitySeed + dayZeroSeedPresence + 末帧全 1.0 测试 |
| Journey：无 FilterChip root / Memory River / Week arc / Month constellation / 证据仅在依据后 | PASS | `journeyRootHasNoFilterChipsAndChartsStayBehindEvidence` + journey smoke（scale 选择/河流点击） |
| Journey 历史确定性（无 now() 参与历史视觉） | PASS | canonical time 链 + roundtrip 同帧测试 |
| Me Intelligence Map（四领域 168dp 中心）+ 单展开 + 更多控制下沉 + Crisis 不下沉 | PASS | `domainExpansionIsExclusive` + `allSlotsComposedWithTitle`（me_intelligence_map / me_domain_detail tags） |
| Memory 只真实字段 / Data map 只真实 sources / Presence Control 真实面 | PASS | 未改语义层（既有契约测试全绿） |

## 4. Accessibility（§79/§30）

| 项 | 状态 |
|---|---|
| organism 单语义节点 + 真实 state contentDescription + 禁心理话术 | PASS（`OrganismA11yTest`） |
| fontScale 1.0/1.15/1.3/1.5（占比 + narrative 滚动区） | PASS（函数锚点 + scroll region） |
| Reduced Motion（编译期精确系数 + 180ms crossfade + wallpaper parallax off） | PASS |
| touch target ≥48dp（Why/Ask/Action/tabs/map nodes/pill buttons） | PASS |

## 5. Visual Lab（§33/§34/§91）

| 项 | 状态 |
|---|---|
| debug-only（BuildConfig.DEBUG 双重门；不进 production nav） | PASS |
| 5 presets × 3 surfaces × backend 选择 + 11 参数滑杆 + identity 切换 | PASS |
| 一键 PNG + metrics JSON + Reference 门评估导出 | PASS（`exportLab`） |
| AGSL/ADVANCED 按本机可用性启用；ULTRA 恒禁用 | PASS |

## 6. PENDING_HUMAN_REVIEW（§83 五问，机器不代替人眼）

1. 7 个用户 Day180 不看名字能否认出不同身份？（机器代理：几何维度多样性已锁；人眼待答）
2. 同一用户 Day0→180 是否像同一个 ECHO 长大？（代理：连续性测试绿；人眼待答）
3. App 第一眼是不是 ECHO？（结构：organism 61.5% 首屏 + 0 卡片；人眼待答）
4. Wallpaper 5 秒生命感 / 1 小时不烦？（待真机 + 人眼）
5. Quiet / Low Data 是否像安静存在而非坏掉？（待人眼）

画廊基线已重生成：`qa/visual-review/rendered/`（7 profile × 6 锚点日 × 5 surface）+
`android/feature/qa/visual-review/organism/`（含 36s 运动序列拼图）。
