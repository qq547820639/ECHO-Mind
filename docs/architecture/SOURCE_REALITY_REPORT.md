# Source Reality Report —— 源码事实报告（ERA 32，脚本生成 · 多模块自动发现）

> 生成时间戳随提交更新；本文件由 `scripts/generate_source_reality.py` 生成，禁止手写行数。

> Gradle modules 自动发现自 `android/settings.gradle.kts`；每 module 扫描 `src/main/java` 与 `src/main/kotlin`。

## Kotlin 包（Production）
- `com.yunjue.echo.mind`
- `com.yunjue.echo.mind.actions`
- `com.yunjue.echo.mind.data`
- `com.yunjue.echo.mind.data.database`
- `com.yunjue.echo.mind.data.outbox`
- `com.yunjue.echo.mind.di`
- `com.yunjue.echo.mind.intelligence`
- `com.yunjue.echo.mind.journey`
- `com.yunjue.echo.mind.localportrait`
- `com.yunjue.echo.mind.me`
- `com.yunjue.echo.mind.memory`
- `com.yunjue.echo.mind.model`
- `com.yunjue.echo.mind.ports`
- `com.yunjue.echo.mind.presence`
- `com.yunjue.echo.mind.presencevisual`
- `com.yunjue.echo.mind.runtime`
- `com.yunjue.echo.mind.security`
- `com.yunjue.echo.mind.sensing`
- `com.yunjue.echo.mind.ui`
- `com.yunjue.echo.mind.ui.debug`
- `com.yunjue.echo.mind.ui.echo`
- `com.yunjue.echo.mind.ui.echo.actions`
- `com.yunjue.echo.mind.ui.echo.components`
- `com.yunjue.echo.mind.ui.echo.conversation`
- `com.yunjue.echo.mind.ui.echo.why`
- `com.yunjue.echo.mind.ui.journey`
- `com.yunjue.echo.mind.ui.me`
- `com.yunjue.echo.mind.ui.theme`
- `com.yunjue.echo.mind.visual.math`
- `com.yunjue.echo.mind.visual.model`
- `com.yunjue.echo.mind.visual.motion`
- `com.yunjue.echo.mind.visual.render`
- `com.yunjue.echo.mind.visual.surface`
- `com.yunjue.echo.mind.visual.testing`
- `com.yunjue.echo.mind.wearable`
- `com.yunjue.echo.mind.wearable.research`

## Python 包（backend/app 顶层）
- ``
- `api`
- `services`

## 数量事实
- Production Kotlin：240
- Test Kotlin：173
- QA Kotlin（:feature:qa，不属于 Production Runtime）：38
- Python 文件：69
- Manifest Components：5（缺失源类：0）
- Worker：5（缺失实现：0）
- Repository：13
- Runtime/Coordinator：6
- ViewModel：8
- Gradle modules（自动发现）：app, core:model, core:ports, core:security, core:visual, feature:actions, feature:intelligence, feature:journey, feature:memory, feature:observation, feature:presence, feature:presencevisual, feature:qa, feature:wearable


## Wearable 面（ERA 33）
- Vela JS 源文件（wearable/xiaomi-vela/src，**.ux + **.js**）：11 —— **Vela ≠ Android Kotlin production count**（JS 快应用独立计数，不并入上文 Kotlin 数量）
- ANS 集成 schema/golden（integrations/answatch/*.json）：4 —— ANSWatch 为 READ-ONLY 参考仓，其源码不计入本仓 Production 计数
## Manifest Components（跨全部 production module 解析）
- `:app` `.main.MainActivity` ✅
- `:app` `.main.PassiveSensingService` ✅
- `:app` `.main.sensing.NotificationCollector` ✅
- `:app` `.main.EchoWallpaperService` ✅
- `:app` `.main.EchoDreamService` ✅

## Worker
- `EveningReminderWorker`
- `MessageCheckWorker`
- `PresenceRefreshWorker`
- `SensingWatchdogWorker`
- `SyncWorker`

## Repository / Runtime / ViewModel
- Repository：ConsentRepository, EscalationRepository, FeatureFlagRepository, JourneyMemoryRepository, JourneyRepository, MemoryRepository, MessageRepository, OnboardingRepository, PortraitRepository, PresenceRepository, SensingRepository, SkillRepository, SyncStateRepository
- Runtime：EchoActionRuntime, EchoRuntimeCoordinator, SensingRuntimeStatus, SkillSessionCoordinator, WearableRuntime, WearableRuntimeState
- ViewModel：DataAndSensingViewModel, EchoSceneViewModel, IntelligenceSettingsViewModel, JourneyViewModel, MeViewModel, MemoryManagementViewModel, PresenceSettingsViewModel, SubscriptionViewModel

## Domain packages（必须存在）
- `sensing` ✅
- `localportrait` ✅
- `presence` ✅
- `intelligence` ✅
- `memory` ✅
- `actions` ✅
- `journey` ✅
- `runtime` ✅
- `wearable` ✅

## unresolved project symbol 候选（自动发现；人工复核）
- 无

## 文档宣称但缺失实现候选
- 由 SourceIntegrityTest（runtime/intelligence/presence/memory 关键类）与 unresolved 扫描联合覆盖；本轮无已知项。

