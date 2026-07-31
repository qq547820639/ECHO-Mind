# API 契约

完整契约见 `openapi.json`。关键接口：

## 现行接口（v0.2 被动感知范式）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/v1/onboarding/consents` | user/professional | 同意录入（含 `passive_sensing` 类型） |
| GET | `/v1/onboarding/consents/latest` | user | 查询最新同意状态 |
| POST | `/v1/onboarding/l0` | user | L0 准入门禁筛查 |
| POST | `/v1/onboarding/emergency-contact` | user | 紧急联系人 |
| POST | `/v1/features/ingest` | user | **被动感知派生特征上传**（summary+vector，无原始 payload；`passive_sensing` consent 撤回返回 412；`source=mic_opt` 额外校验 `voice_features` consent，撤销返回 412；`passive_sensing_enabled=false` 返回 410） |
| GET | `/v1/profile/{user_id}` | user/professional | 用户画像 |
| GET | `/v1/narratives` | user/professional | 每日叙事（趋势视图数据源） |
| GET | `/v1/trends/summary` | user/professional | 趋势汇总（保留） |
| GET | `/v1/journals` | user | 历史日记只读查询 |
| POST | `/v1/escalations` | user/safety service | 创建危机事件（含被动 RED 触发） |
| GET | `/v1/escalations` | on_call/professional/auditor | 事件列表 |
| GET | `/v1/escalations/metrics` | on_call/professional/auditor | 事件指标 |
| POST | `/v1/escalations/{id}/ack` | on_call/professional | 确认事件 |
| POST | `/v1/escalations/{id}/takeover` | on_call/professional | 接管事件 |
| POST | `/v1/escalations/{id}/close` | professional/admin | 关闭事件 |
| POST | `/v1/escalations/{id}/review` | professional/admin | 复盘事件 |
| POST | `/v1/data-subject-requests` | user | DSR 申请（export/delete/revoke_service） |
| GET | `/v1/data-subject-requests` | user | DSR 列表 |
| POST | `/v1/data-subject-requests/{id}/complete` | admin | DSR 完成（delete 清理 DerivedFeature+RiskSignal） |
| POST | `/v1/sandbox/runs` | admin | 触发沙箱运行（`sandbox_enabled=false` 返回 410；超并发返回 429 `sandbox concurrency limit reached`；每小时速率超限返回 429 `sandbox rate limit exceeded`；单次 run 超 `sandbox_timeout_seconds` 转 failed） |
| GET | `/v1/sandbox/runs/{id}` | admin/professional | 查询沙箱运行状态（`sandbox_enabled=false` 返回 410） |
| GET | `/v1/skills` | user | **拉取已下发 Skill**（仅 reviewed/signed 状态；空列表时返回 `cold_start_hint` 字段：`stage_0`/`stage_1_3`/`stage_4_7`/`stage_7_plus`，按 `observation_days` 分阶段；`skills_delivery_enabled=false` 返回 410） |
| GET | `/v1/skills/{id}` | user | Skill 详情（`skills_delivery_enabled=false` 返回 410） |
| POST | `/v1/skills/{id}/transition` | admin/professional | Skill 治理状态机转换（reviewed/signed/retired） |
| POST | `/v1/skills/batch-retire` | admin | **批量回滚 Skill**（body=`{"skill_ids": [...]}`，状态转 retired，不再下发） |
| GET | `/v1/tenant/portrait` | admin/professional/auditor | **机构去标识群体画像**（mood 分布/observation 统计/近 7 天活跃/escalation 计数/Skill 下发数；聚合桶 <5 合并到 "other" 防重标识；不含单个用户 ID） |
| GET | `/v1/config/flags` | user | **拉取本租户 feature flags**（`passive_sensing_enabled`/`sandbox_enabled`/`skills_delivery_enabled`，端侧灰度联动） |
| PUT | `/v1/tenant/flags` | admin | 修改本租户 feature flag（body=`{"flag_key": "...", "value": false}`，审计记录 `tenant.flags.update`） |
| GET | `/v1/audit/events` | auditor/admin | 审计事件查询 |
| GET | `/v1/audit/verify` | auditor/admin | 审计哈希链验证 |

## 已停用接口（410 Gone）

> v0.2 主动输入范式已停用，有效身份请求返回 410 Gone，引导使用被动感知范式。

| 方法 | 路径 | 替代方案 |
|---|---|---|
| POST | `/v1/checkins` | `POST /v1/features/ingest` |
| POST | `/v1/journals` | `POST /v1/features/ingest` |
| POST | `/v1/journals/{id}/revisions` | `POST /v1/features/ingest` |
| DELETE | `/v1/journals/{id}` | — |
| POST | `/v1/safety/check` | `POST /v1/features/ingest`（被动 RED 自动触发） |
| POST | `/v1/questionnaires/{code}/responses` | `GET /v1/skills`（Skill 驱动） |
| POST | `/v1/practices/completions` | `GET /v1/skills`（Skill 驱动） |

所有写接口应携带客户端唯一 `event_id`；重复提交返回同一业务对象。

## 隐私契约

- `DerivedFeatureIn` schema 启用 `extra="forbid"`：拒绝 `audio_buffer` / `raw_samples` / `payload` 等原始传感字段。
- `passive_sensing` consent 撤回后 `POST /v1/features/ingest` 返回 412 Precondition Failed。
- `source=mic_opt` 的派生特征额外要求有效 `voice_features` consent（granted=true），撤销返回 412；端侧麦克风开关切换与系统权限撤回均写入证据哈希（SHA-256 of `voice-features-consent-2026.07:{userId}:{granted}`）。
- 租户 feature flag 关闭对应能力时路由返回 410 Gone（`passive_sensing_enabled`/`sandbox_enabled`/`skills_delivery_enabled`）。
- Skill 下发前经 sanitizer 脱敏：移除其他用户数据、原始特征引用（`passive_feature.summary` / `derived_feature.*`）。
- DSR delete 清理 DerivedFeature + RiskSignal 记录。
