# ECHO_WRIST_REAL_DEVICE_REPORT

> 只记录事实；不记录私人用户数据。
> 当前状态：**尚未执行真机安装** —— `BLOCKED_EXTERNAL_BAND10_DEVICE` /
> `BLOCKED_EXTERNAL_XIAOMI_THIRD_PARTY_CHANNEL`（本环境无真机与 Debug 通道）。
> 真机执行后按下列字段如实填写（模板保留，禁止伪造）。

## 执行状态

| 字段 | 值 |
|---|---|
| 状态 | NOT_RUN — BLOCKED_EXTERNAL_BAND10_DEVICE / BLOCKED_EXTERNAL_XIAOMI_THIRD_PARTY_CHANNEL |
| 执行日期 | —（真机到位后填写） |

## 环境

| 字段 | 值 |
|---|---|
| device | —（Xiaomi Smart Band 10） |
| firmware | — |
| Mi Fitness version | — |
| Android device / OS | — |
| RPK filename + SHA256 | —（期望：wearable/xiaomi-vela/dist/*.debug.rpk） |
| APK filename + SHA256 | —（CI debug 产物） |
| 签名验证 | —（`scripts/verify_wrist_signing.py` 输出 MATCH/MISMATCH） |

## 结果

| 字段 | 值 |
|---|---|
| install result | — |
| runtime result | — |
| first install checklist（BAND10_INSTALL_GUIDE §3） | — |
| interconnect（PHONE ↔ WRIST） | — |
| 断连/重连/进程死亡恢复 | — |
| accelerometer（foreground summary） | — |
| vibration short/long + haptics 硬门 | — |
| battery / 24h 稳定性 | — |

## known limitations

- —（按事实填写）
