# Source Reality Report —— 源码事实报告（ERA 12.7，脚本生成）

> 生成时间戳随提交更新；本文件由 `scripts/generate_source_reality.py` 生成，禁止手写行数。

## Kotlin 包
- `com.yunjue.echo.mind`
- `com.yunjue.echo.mind.data`
- `com.yunjue.echo.mind.data.outbox`
- `com.yunjue.echo.mind.di`
- `com.yunjue.echo.mind.journey`
- `com.yunjue.echo.mind.me`
- `com.yunjue.echo.mind.memory`
- `com.yunjue.echo.mind.presence`
- `com.yunjue.echo.mind.runtime`
- `com.yunjue.echo.mind.sensing`
- `com.yunjue.echo.mind.ui`
- `com.yunjue.echo.mind.ui.echo`
- `com.yunjue.echo.mind.ui.echo.actions`
- `com.yunjue.echo.mind.ui.echo.components`
- `com.yunjue.echo.mind.ui.echo.conversation`
- `com.yunjue.echo.mind.ui.echo.why`
- `com.yunjue.echo.mind.ui.journey`
- `com.yunjue.echo.mind.ui.me`

## Python 包（backend/app 顶层）
- ``
- `api`
- `services`

## 数量事实
- Kotlin 文件：145
- Python 文件：68
- Manifest Components：5（缺失源类：1）
- Worker：5（缺失实现：0）
- Repository：13
- Runtime/Coordinator：4
- ViewModel：7
- Gradle modules：['app', 'feature:actions', 'feature:memory', 'feature:observation', 'feature:presence', 'feature:intelligence', 'feature:journey', 'core:security', 'core:model', 'core:ports']

## Manifest Components
- `.main.MainActivity` ✅
- `.main.sensing.PassiveSensingService` ✅
- `.main.sensing.NotificationCollector` ❌ 缺源类
- `.main.presence.EchoWallpaperService` ✅
- `.main.presence.EchoDreamService` ✅

## Worker
- `EveningReminderWorker`
- `MessageCheckWorker`
- `PresenceRefreshWorker`
- `SensingWatchdogWorker`
- `SyncWorker`

## Repository / Runtime / ViewModel
- Repository：ConsentRepository, EscalationRepository, FeatureFlagRepository, JourneyMemoryRepository, JourneyRepository, MemoryRepository, MessageRepository, OnboardingRepository, PortraitRepository, PresenceRepository, SensingRepository, SkillRepository, SyncStateRepository
- Runtime：EchoActionRuntime, EchoRuntimeCoordinator, SensingRuntimeStatus, SkillSessionCoordinator
- ViewModel：DataAndSensingViewModel, EchoSceneViewModel, IntelligenceSettingsViewModel, JourneyViewModel, MeViewModel, MemoryManagementViewModel, PresenceSettingsViewModel

## Domain packages（必须存在）
- `sensing` ✅
- `localportrait` ✅
- `presence` ✅
- `intelligence` ✅
- `memory` ✅
- `actions` ✅
- `journey` ✅
- `runtime` ✅

## unresolved project symbol 候选（自动发现；人工复核）
- 无

## 文档宣称但缺失实现候选
- 由 SourceIntegrityTest（runtime/intelligence/presence/memory 关键类）与 unresolved 扫描联合覆盖；本轮无已知项。

