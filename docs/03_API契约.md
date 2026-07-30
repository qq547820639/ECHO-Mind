# API 契约

完整契约见 `openapi.json`。关键接口：

| 方法 | 路径 | 权限 |
|---|---|---|
| POST | `/v1/onboarding/consents` | user/professional |
| POST | `/v1/checkins` | user |
| POST | `/v1/safety/check` | user |
| POST | `/v1/questionnaires/{code}/responses` | user |
| GET | `/v1/trends/summary` | user/professional |
| POST | `/v1/escalations` | user/safety service |
| GET | `/v1/escalations` | on_call/professional/auditor |
| POST | `/v1/escalations/{id}/ack` | on_call/professional |
| POST | `/v1/escalations/{id}/takeover` | on_call/professional |
| POST | `/v1/escalations/{id}/close` | professional/admin |
| GET | `/v1/audit/events` | auditor/admin |

所有写接口应携带客户端唯一 `event_id`；重复提交返回同一业务对象。
