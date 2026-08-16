# Android Dependency Graph —— 真实依赖图（ERA 13.2 §48，脚本生成）

> 状态：CURRENT · 本文件由 `scripts/generate_dependency_graph.py` 生成，禁止手写。

> 每轮结构变更后重新生成；CI source-integrity drift gate 强制同步。

## 1. 领域包图（com.yunjue.echo.mind.*，顶层领域聚合）

```text
data ──► intelligence, memory, observation, ports, presence, root, security
di ──► actions, data, intelligence, journey, ports, presence, root, security, wearable
intelligence ──► memory, observation, ports, security
journey ──► data, intelligence, memory, observation, presence, root, visual
me ──► data, memory, observation
memory ──► observation, ports
observation ──► root
ports ──► observation
presence ──► observation, root
root ──► observation, ports, visual
runtime ──► data, intelligence, observation, root
security ──► root
ui ──► actions, data, intelligence, journey, me, memory, observation, presence, presencevisual, root, visual, wearable
visual ──► observation
```

## 2. 领域文件数（实测）

| 领域 | Kotlin 文件数 |
|---|---|
| actions | 2 |
| data | 28 |
| di | 2 |
| intelligence | 18 |
| journey | 17 |
| me | 3 |
| memory | 7 |
| observation | 26 |
| ports | 3 |
| presence | 12 |
| root | 27 |
| runtime | 1 |
| security | 9 |
| ui | 41 |
| visual | 18 |
| wearable | 3 |

## 3. 跨领域边清单

- data → intelligence
- data → memory
- data → observation
- data → ports
- data → presence
- data → root
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
- intelligence → observation
- intelligence → ports
- intelligence → security
- journey → data
- journey → intelligence
- journey → memory
- journey → observation
- journey → presence
- journey → root
- journey → visual
- me → data
- me → memory
- me → observation
- memory → observation
- memory → ports
- observation → root
- ports → observation
- presence → observation
- presence → root
- root → observation
- root → ports
- root → visual
- runtime → data
- runtime → intelligence
- runtime → observation
- runtime → root
- security → root
- ui → actions
- ui → data
- ui → intelligence
- ui → journey
- ui → me
- ui → memory
- ui → observation
- ui → presence
- ui → presencevisual
- ui → root
- ui → visual
- ui → wearable
- visual → observation

## 4. 循环

- ⚠️ 检测到循环：observation → root → observation

## 5. 边界规则（ArchitectureBoundaryTest + CI 强制）

- observation → intelligence 禁止（Ground Truth 独立）
- presence 渲染层 → Room 禁止
- memory → concrete Provider 禁止
- intelligence → app UI / data 实现禁止（ERA 13.2 §37：只依赖 ports）
- journey/me 应用层 → ui 禁止

