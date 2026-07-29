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

## 兼容性和迁移

- Android 本地数据库从 v1 升级至 v2，新增日记、量表和练习表。
- 后端 v0.2 使用新的 Alembic 基线；生产迁移前必须备份并在 staging 回放。
- v0.1 本地明文敏感字段只允许在 local 环境读取；pilot/production 必须先完成加密迁移。

## 已知限制

- 未在当前环境生成 APK/AAB。
- 工作台是试点演示前端，不代替机构 IAM/MFA 和正式个案系统。
- 确定性规则不是经过临床验证的风险分类器。
- 不包含手环、iOS、摄像头和开放式无限陪聊。
