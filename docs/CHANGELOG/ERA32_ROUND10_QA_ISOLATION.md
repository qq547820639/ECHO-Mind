# ERA 32 R10 — §35 Internal QA UI 隔离审计 + 模块清单自动发现收尾

> 2026-08-15。§35：Production Release 不展示内部 QA Feedback；Debug/Internal 允许；严格隔离。
> 同时完成最后一处手写模块清单的自动发现改造（与 §5 Source Reality 纪律同源）。

## 1. §35 审计结论（全绿）

| 检查点 | 结论 |
|---|---|
| InternalQualityFeedback | 组件入口 `if (!BuildConfig.DEBUG) return`——release 构建零渲染 ✅ |
| 其余 debug/internal 表面 | 全 app/main 扫描仅此一处 BuildConfig.DEBUG 门；EchoWhyLayer/JourneyViewModel 的匹配均为用户可见信任信息（依据标签/采集同步状态，R26 判定保留），非内部 QA 反馈 ✅ |
| QA 模块进 APK | `:app` 与全部 production module 均不依赖 `:feature:qa`；无任何 production 文件引用 `com.yunjue.echo.mind.qa` 包 ✅ |

## 2. 回归加固（防止隔离静默退化）

- `ArchitectureBoundaryTest` 新增 `productionModulesNeverDependOnQaPackage`：
  全部 production 模块源码禁止引用 qa 包（dependency + 包引用双保险）。
- 顺带完成最后一处手写模块清单改造：`moduleRoots` 从手写 10 模块列表改为
  settings.gradle.kts 自动发现（排除 :feature:qa）——与 Source Reality/依赖图生成器同源纪律，
  新增模块不再需要手写登记。

## 3. 验证

- ArchitectureBoundaryTest 全断言通过（自动发现根与旧手写列表等价：10 个 production 模块）；
- Android 全模块单测 + detekt + lint 全绿。

## 4. 下一轮

隔离面审计项收敛完成。持续：Delete Review + 答案复核复跑 + 外部批次待命（真机/dogfood）。
