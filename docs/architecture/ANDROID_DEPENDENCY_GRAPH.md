# Android Dependency Graph —— 真实依赖图（ERA 13.2 §48；V3 §E 双图，脚本生成）

> 状态：CURRENT · 本文件由 `scripts/generate_dependency_graph.py` 生成，禁止手写。

> 每轮结构变更后重新生成；CI source-integrity drift gate 强制同步。

## 1. Gradle Module Dependency Graph（settings.gradle.kts + build.gradle.kts 自动解析）

```text
app ──► core:model, core:ports, core:security, core:visual, feature:actions, feature:intelligence, feature:journey, feature:memory, feature:observation, feature:presence, feature:presencevisual, feature:wearable
core:model ──► （无项目依赖）
core:ports ──► core:model
core:security ──► （无项目依赖）
core:visual ──► core:model
feature:actions ──► （无项目依赖）
feature:intelligence ──► core:model, core:ports, core:security, feature:memory
feature:journey ──► core:model, core:visual, feature:intelligence, feature:memory, feature:presence, feature:presencevisual
feature:memory ──► core:model
feature:observation ──► core:model
feature:presence ──► core:model, core:visual, feature:observation
feature:presencevisual ──► core:model, core:visual
feature:qa（QA，不入生产图） ──► core:model, core:ports, core:visual, feature:intelligence, feature:journey, feature:memory, feature:observation, feature:presence, feature:presencevisual
feature:wearable ──► core:model, core:ports
```

模块图循环：无（必须无环）

## 2. Package Domain Dependency Graph（com.yunjue.echo.mind.*，显式领域聚合）

```text
data ──► intelligence, memory, model, observation, ports, presence, security
di ──► actions, data, intelligence, journey, ports, presence, root, security, wearable
intelligence ──► memory, model, ports, security
journey ──► data, intelligence, memory, model, observation, presence, visual
memory ──► model, ports
observation ──► model
ports ──► model
presence ──► model, observation, visual
presencevisual ──► visual
root ──► data, model, observation, presence, presencevisual, security, visual
runtime ──► data, intelligence, model, observation
ui ──► actions, data, intelligence, journey, memory, model, observation, presence, presencevisual, root, visual, wearable
visual ──► model
wearable ──► model, ports
```

## 3. 领域文件数（实测）

| 领域 | Kotlin 文件数 |
|---|---|
| actions | 2 |
| data | 24 |
| di | 2 |
| intelligence | 18 |
| journey | 17 |
| memory | 7 |
| model | 7 |
| observation | 17 |
| ports | 3 |
| presence | 10 |
| presencevisual | 7 |
| root | 14 |
| runtime | 1 |
| security | 9 |
| ui | 44 |
| visual | 20 |
| wearable | 25 |

## 4. 跨领域边清单

- data → intelligence
- data → memory
- data → model
- data → observation
- data → ports
- data → presence
- data → security
- di → actions
- di → data
- di → intelligence
- di → journey
- di → ports
- di → presence
- di → root
- di → security
- di → wearable
- intelligence → memory
- intelligence → model
- intelligence → ports
- intelligence → security
- journey → data
- journey → intelligence
- journey → memory
- journey → model
- journey → observation
- journey → presence
- journey → visual
- memory → model
- memory → ports
- observation → model
- ports → model
- presence → model
- presence → observation
- presence → visual
- presencevisual → visual
- root → data
- root → model
- root → observation
- root → presence
- root → presencevisual
- root → security
- root → visual
- runtime → data
- runtime → intelligence
- runtime → model
- runtime → observation
- ui → actions
- ui → data
- ui → intelligence
- ui → journey
- ui → memory
- ui → model
- ui → observation
- ui → presence
- ui → presencevisual
- ui → root
- ui → visual
- ui → wearable
- visual → model
- wearable → model
- wearable → ports

## 5. 循环

- 模块图：无。
- 领域图：无。

## 6. 边界规则（ArchitectureBoundaryTest + 本脚本双重强制）

- Gradle 模块图必须无环。
- 领域图必须无环，并满足：
  - observation 不得依赖 ['data', 'di', 'intelligence', 'root', 'runtime', 'ui']
  - presence 不得依赖 ['data', 'di', 'root', 'ui']
  - memory 不得依赖 ['data', 'di', 'intelligence', 'root', 'ui']
  - intelligence 不得依赖 ['data', 'di', 'root', 'ui']
  - journey 不得依赖 ['ui']
  - actions 不得依赖 ['data', 'di', 'intelligence', 'journey', 'memory', 'observation', 'presence', 'presencevisual', 'root', 'ui', 'visual']
  - visual 不得依赖 ['data', 'di', 'intelligence', 'journey', 'memory', 'observation', 'presence', 'presencevisual', 'root', 'runtime', 'ui']
  - presencevisual 不得依赖 ['data', 'di', 'intelligence', 'journey', 'memory', 'observation', 'root', 'ui']
  - wearable 不得依赖 ['actions', 'data', 'di', 'intelligence', 'journey', 'memory', 'presence', 'presencevisual', 'root', 'ui']

边界违规：无。

