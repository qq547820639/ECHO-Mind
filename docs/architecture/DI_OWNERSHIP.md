# DI Ownership —— 构造职责与生命周期所有权（ERA 13.3）

> 状态：CURRENT · 与 `di/EchoContainers.kt` / `AppContainer.kt` / ArchitectureBoundaryTest 同步。

## 1. 容器结构（§43/§44）

```
AppContainer（composition root）
 ├── CoreContainer(context)            安全/数据库/同步/基础仓库
 ├── ObservationContainer(core)        Ground Truth + 感知/同意
 ├── PresenceContainer(core, obs)      单一 Current ECHO State
 ├── MemoryContainer(core)             EchoMemory
 ├── IntelligenceContainer(core, obs, mem)  Provider + 叙事 + Context 检索
 └── ActionContainer(core)             Skill 内容源
```

- Root **不构造任何领域对象**（ArchitectureBoundaryTest.appContainerIsCompositionRootOnly 防回归）；
- Root 只做：六容器组合 + 跨域编排（EchoRuntimeCoordinator / JourneyRepository / SkillSessionCoordinator）+ Transient 工厂；
- 兼容访问器（`container.preferences` 等）是委托属性，所有权仍在子容器。

## 2. 生命周期所有权（§45）

| 对象 | Scope | 说明 |
|---|---|---|
| Core/Observation/Presence/Memory/Intelligence/Action 容器及成员 | **Application** | EchoMindApplication lazy 单例；进程内唯一 |
| EchoRuntimeCoordinator / JourneyRepository / SkillSessionCoordinator | **Application** | 跨域编排，由 Root 持有 |
| ViewModel（EchoScene/Journey/Me + 四子 VM） | **ViewModel scoped** | viewModelFactory 构造；配置变更存活，进程死亡重建 |
| WorkManager Workers（Sync/PresenceRefresh/EveningReminder/…） | **Worker scoped** | 经 Application container 访问；WorkManager 管理生命周期 |
| Services（PassiveSensingService / EchoWallpaperService / Dream） | **Service scoped** | 系统构造；Wallpaper/Dream 只读 Presence 快照，不初始化容器 |
| Collectors / SensingWindowScheduler / EventHub 工厂 | **Transient** | Root 工厂每次新建；EventHub 为进程单例（getInstance） |

## 3. 依赖方向（§46 不制造 God DI 文件）

- observation 不依赖 intelligence/affective（永久边界）；
- presence 渲染不依赖 Room；
- memory 不依赖具体 Provider；
- intelligence 只依赖 ports（ERA 13.2）；
- 新代码走领域入口（`container.core.*` / `container.intelligence.*` …），
  兼容访问器仅用于存量调用点逐步迁移。

## 4. DI Framework 裁决（§47）

- **采用 structured manual DI**：viewModelFactory + 领域容器已满足 ViewModel/Worker/Service/testing/lifecycle 需求；
- 不引入 Hilt：单模块阶段 Hilt 的 ksp 代码生成与多注解面不带来等价收益；
- 物理模块化（ERA 13.5）后如出现跨模块注入需求再复评。
