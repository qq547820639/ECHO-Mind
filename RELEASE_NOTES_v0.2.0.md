# ECHO Mind Path A v0.2.0 Release Notes

状态：`pilot-candidate`，不适用于无人值守生产服务。

## 主要变化

- Android 首次使用、L0、同意、签到、日记、量表、练习、趋势和数据权利流程可用化
- Room v2、Keystore 加密、离线 Outbox 和风险事件优先同步
- 后端扩展至完整 Path A 数据域和人工接管状态机
- AES-256-GCM 字段加密、生产密钥预检和明文旧字段拒绝
- 租户级幂等、数据修订链、数据主体请求和审计完整性验证
- 650 条合成安全回归集、内容包门禁和宣称扫描
- Alembic、OpenAPI、CI、安全扫描和试点治理包
- Android 依赖基线调整为 AGP 8.13.2 / Gradle 8.13 / Kotlin 2.3.20 / KSP 2.3.9

## 待明确项收尾（2026-07-31）

- **P1 麦克风授权证据闭环**：麦克风开关切换写入 `voice_features` consent（含 SHA-256 证据哈希），端侧监听权限撤回并停止采集，后端对 `mic_opt` 特征校验 consent（撤销 412）。
- **P2 沙箱算力预算**：单次 run 超时 120s 转 failed、租户并发上限 4（429）、每小时速率限制 10（429）。
- **P3 冷启动兜底文案分阶段**：`GET /v1/skills` 空列表返回 `cold_start_hint`（按 observation_days 4 档），端侧区分加载中/失败/空态三态。
- **P4 机构去标识群体画像**：`GET /v1/tenant/portrait` 聚合 mood/observation/活跃/escalation/Skill 计数，小桶 <5 合并到 "other" 防重标识。
- **P5 灰度回滚方案**：`Tenant.feature_flags` 三开关 + 路由前置 410 + `GET /v1/config/flags` 端侧联动 + `POST /v1/skills/batch-retire` 批量回滚。
- 后端测试 671 → **781 passed**；OpenAPI/PRD/API契约/威胁模型/测试计划/风险登记册同步。

## 兼容性和迁移

- Android 本地数据库从 v1 升级至 v2，新增日记、量表和练习表。
- 后端 v0.2 使用新的 Alembic 基线；生产迁移前必须备份并在 staging 回放。
- v0.1 本地明文敏感字段只允许在 local 环境读取；pilot/production 必须先完成加密迁移。

## 已知限制

- 未在当前环境生成 APK/AAB。
- 工作台是试点演示前端，不代替机构 IAM/MFA 和正式个案系统。
- 确定性规则不是经过临床验证的风险分类器。
- 不包含手环、iOS、摄像头和开放式无限陪聊。
