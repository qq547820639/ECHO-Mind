# ERA 31 Felt Product Reality — 验收证据总表（R1–R35）

> 最终唯一标准：用户用了半年以后，看到手机上的那个生命形态时，自然认为
> **「这是我的 ECHO」**。本表把 PART 2 十道门与 FINAL ACCEPTANCE 五个时点
> 逐条对应到已交付轮次与可复核证据。

## A. PART 2 十道门（每项至少明确改善其一才实施）

| 门 | 交付轮次 | 证据 |
|---|---|---|
| 1. 更懂自己 | R11/R12/R23（自然句 Headline/Why 分钟数）、R17/R25（Ask ECHO 问法归一/证据人话） | PersonalAnswerEngineTest 28 族、EchoSceneUiStateTest |
| 2. 更自然存在于手机 | R13/R14/R20（自适应帧率实测 4fps=缓慢呼吸）、R16（进程死亡恢复锚点）、R22/R31（苏醒=第一次 Presence） | WallpaperMotionRealityTest、EchoPresenceSnapshotRecoveryTest、dayZeroSeedPresence 契约 |
| 3. 同一个 ECHO 在成长 | R1（结构身份入帧模型）、R27（一条河流=时间线+故事）、R31（identity 逐字段一致） | qa/visual-review（不同用户/连续性拼图）、IdentityStructureVisibilityTest |
| 4. 纠正后未来理解改变 | R18（纠正→CONTEXT 桥梁）、R32（production 全链验收） | CorrectionReuseAcceptanceTest、EchoCorrectionServiceTest |
| 5. Ask ECHO 只有我才有 | R3（确定性引擎 26/26，基线）、R17（界面建议离线可答）、R24（依据如实） | AskEchoSuggestionContractTest、answerSourcesAreHonest |
| 6. Journey 看见自己的时间 | R5/R6（基线）、R19（叙事说人话/河流锚定）、R27（一条河流） | JourneyNaturalSummaryTest、JourneyRiverTest、JourneyScreenSmokeTest |
| 7. 清楚知道什么/不知道什么 | R11（人类语言）、R21（人称方向统一）、R26（检查台去重） | WhatEchoKnowsContentSmokeTest、EchoDoesNotKnowTest |
| 8. Wallpaper 值得保留 | R13（电池现实）、R20（4fps 人眼证据）、R34（采纳入口） | motion_wallpaper_idle4fps_*.png、WallpaperMotionRealityTest |
| 9. 更安静而不是更吵 | R15（Why 去催促）、R17（AI 催促清零）、R28（进度条退役）、R29（按钮墙折叠） | aiUnavailableKeepsWhyLayerQuiet、collapsedByDefaultOnlyShowsQuietEntry |
| 10. 信任/隐私/控制增强 | R16（恢复锚点）、R24（依据诚实）、R25（z 分数退役）、R26（控制权唯一） | EchoDoesNotKnowTest、DeterministicPersonalAnswerProviderTest |

## B. FINAL ACCEPTANCE 五时点

| 时点 | 契约 | 交付轮次 | 证据 |
|---|---|---|---|
| Day 0 | ECHO 醒了，不空、不伪造 | R22/R31（真实 ECHO 苏醒）、R12（初见文案） | dayZeroSeedPresenceMatchesFirstRuntimeIdentity、SeedPortraitBlock |
| Day 7 | 第一次点 Why 能理解 | R23（开始活跃 delta「晚/早 N 分钟」镜像两端） | startTimeDeltaIsHumanReadableMinutes、backend e2e 晚 180 分钟 |
| Day 30 | 壁纸愿意保留 + 个人问答来自自己的 30 天 | R13/R20/R34 + R17/R23–R25 | WallpaperMotionRealityTest、PersonalAnswerEngineTest（90 天窗口族） |
| Day 90 | 纠正真的被用 + Journey 有值得看的时间 | R18/R32 + R19/R27 | CorrectionReuseAcceptanceTest、JourneyRiverTest |
| Day 180 | 两个用户明显不同 / 同一用户明显同一个 ECHO | R1（结构身份）+ 视觉画廊 | users_APP_day180.png、continuity_APP_*.png、motion_APP_*.png |

## C. 复核方式

- 机器证据：Android/backend 全门禁实测数字**由 `scripts/refresh_status_numbers.py` 自动生成**，
  见 `docs/STATUS.md` §3（禁止手写计数；ERA 32 起生效）。
- 发布证据：v0.11.0 Release Closure 全链（ERA 32 R05）——provenance / final package §18 门禁 /
  test_release_set + test_source_archive 16/16，锚点 `docs/RELEASE_BASELINE.md`。
- 人眼证据：浏览器打开 `qa/visual-review/index.html`——不同用户 Day 180 并排、
  同一用户连续性、36s 运动序列、真实帧率 4fps/30fps 拼图（R20）。
- 真机证据：`qa/visual-review/DEVICE_CHECKLIST.md`（R35 对齐当前 UX，部署侧执行）。
- 下一 Release Closure 时全部重新生成，不带旧 proof 发布（RELEASE_BASELINE.md 纪律）。

## D. ERA 32 增量证据（R01–R13，v0.11.0）

| 门 / 时点 | ERA 32 增量 | 证据 |
|---|---|---|
| 1. 更懂自己 | R02（q032 月间规律度答对 / 无变化结论可验证 / z 距离退役）、R03（上下文 14 天活跃窗口，旧上下文不再被当「这几天」） | stabilityMonthCompareAnswersTheQuestionAsked、travelContextOldContextIsTreatedAsEnded、similarDaysEvidenceSpeaksHumanNotZDistance |
| 4. 纠正后未来理解改变 | R03（AI 路径记忆人话化——内部格式不再进 Provider；纠正→CONTEXT 桥梁复核） | EvidenceAssemblerHumanizeTest、CorrectionReuseAcceptanceTest 复验 |
| 6. Journey 看见自己的时间 | R06（§41 四问全部可答：期间故事 + 现在 vs 一个月前） | periodStoryAndMonthAgoAnswerTheNinetyDayTest、insufficientHistoryDoesNotClaimStability |
| 7. 知道什么/不知道什么 | R08（静默陈旧模式改说「以前观察到…最近没再看到」，不把过去当现在） | staleConfirmedPatternWithoutCorrectionIsPresentedAsPast、outdatedPatternIsPresentedAsChallengedNotCurrentTruth |
| 10. 信任/隐私/控制 | R09（§52 依据词表精确化：「原始音频」替代假声明「麦克风」）、R10（QA 隔离双保险）、R13（QA 快照 Me 面吃 production，不再展示产品不存在的语句） | EchoConversationLayerSmokeTest、productionModulesNeverDependOnQaPackage、7 profile 快照 |
| Day 30/90/180 | R07（20k 记忆 / 1000 天 Journey 护栏）、R11（阶段终检 16/16 + 漂移门补齐）、R12（Day-0 链终验） | twentyThousandScaleGuardrails、journeyUiStateAssembly1000StaysUnderBudget、`docs/CHANGELOG/ERA32_ROUND11_PHASE_CHECK.md` |
