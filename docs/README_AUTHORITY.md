# Documentation Authority —— 文档读取优先级（v3 §85-89）

> 状态：CURRENT · 本文件规定**后续任何 Agent/协作者读取仓库文档时的权威顺序**，防止被旧 spec 污染。

## 1. 文档生命周期状态

所有文档必须标注以下状态之一：

| 状态 | 含义 | 处理 |
|---|---|---|
| `CURRENT` | 当前事实，唯一可信 | 直接采用 |
| `SUPERSEDED` | 已被更新的文档取代 | 不采用；查其替代者 |
| `ARCHIVED` | 已归档历史（docs/archive/） | 仅历史参考 |
| `HISTORICAL` | 旧时代规格（.trae/.workbuddy/specs 等） | **禁止作为当前要求来源** |

## 2. 读取优先级（从高到低）

1. **Product Constitution** — `docs/product/ECHO_PRODUCT_CONSTITUTION.md`（产品最高原则，冻结）
2. **Version Source** — `scripts/version_source.json`（版本唯一事实源）
3. **Implementation Status** — `docs/IMPLEMENTATION_STATUS.md`（每轮状态锚点）
4. **Current Contracts** — `PORTRAIT_CONTRACT.md`、`docs/intelligence/PERSONAL_INTELLIGENCE_CONTRACT.md`、`AFFECTIVE_CONTRACT.md`、`docs/providers/AI_PROVIDER_SPEC.md`、`docs/presence/ECHO_PRESENCE_ARCHITECTURE.md`
5. **ADR** — `docs/architecture/ADRS.md`（ADR-001~058）
6. **Architecture Inventory** — `docs/architecture/ANDROID_CODE_INVENTORY.md`（文件分类与迁移目标）
7. **README** — 当前用户可见产品（与 Version Source 强制一致）
9. **Historical specs** — `docs/archive/`、`.trae/specs`、`.workbuddy/`、`.codebuddy/`（仅考古）

## 3. 冲突裁决

- 若两份文档冲突：**按上述优先级取高者**；
- 若 CURRENT 文档与代码冲突：**代码是最终事实**（文档须更新）；
- 若 README 与 version_source 冲突：README 错（CI 断言拦截）。

## 4. 强制一致性（CI 已覆盖）

- README 版本 == backend APP_VERSION == pyproject == Android versionName == DELIVERY_MANIFEST.version（`backend/tests/test_version_consistency.py`）
- README Room vX == EchoDatabase version；README 后端测试数 == pytest 实际收集数
- docs/openapi.json == 当前 app schema（contract_drift_check / test_openapi_contains_all_v1_routes）

## 5. 历史目录治理

- `.trae/specs`、`.workbuddy/`、`.codebuddy/`：标记 HISTORICAL，禁止作为当前要求来源；有价值内容需迁移到 docs/ 并标注 CURRENT 才生效。
- 删除原则：不删有价值历史，但绝不保留「双权威」。
