# ECHO WRIST — 长跑验证协议（1h / 8h / 24h · Batch I）

> 状态：协议与软件仪表已就绪；实际长跑需真机 →
> `BLOCKED_EXTERNAL_LONG_RUN_DEVICE_TIME`（本环境无法等待真实设备长期实验）。
> 不伪造任何数据：所有"实测"栏位在真机执行后填写。

## 1. 目标

First install + interconnect 通过后执行 1h → 8h → 24h 三级长跑，验证：

- Band 电池 / Phone 电池
- 消息量（入站/推送，进程内计数已内置）
- 断连次数（进程内计数已内置）
- runtime restart / 进程死亡恢复（revision 单调性经 WearRevisionStore 持久化）
- Presence cache 存活（手环端 `presence_cache` 经 `system.storage` 持久化）
- UI 稳定性 / 触觉 / 加速度计成本

**不做 30-day**；先过 24h reliability。

## 2. 软件仪表（已内置，无需新造）

| 仪表 | 位置 | 说明 |
|---|---|---|
| inboundMessageCount | `WearableRuntimeState` | 入站消息（传输层，含重复/损坏） |
| outboundPushCount | `WearableRuntimeState` | Presence 推送尝试次数 |
| disconnectCount | `WearableRuntimeState` | CONNECTED→DISCONNECTED 次数（进程内） |
| lastPresencePushedAt / lastAckAt / lastObservationAt | `WearableRuntimeState` | 时间戳 |
| presenceRevision | `WearableRuntimeState` + `WearablePrefs` 持久化 | 进程死亡后单调性 |
| 观察日志 | `WristObservationLog`（有界 64） | 腕上中立证据（内存，进程死亡即清） |
| Me → Advanced 展示 | `WristSection` | "长跑仪表：入站 N · 推送 N · 断连 N（进程内计数）" |
| 手环 cache | `presence_cache` + `system.storage` | 进程死亡后 Identity 连续性 |

## 3. 执行清单（真机）

### 3.1 每级长跑记录

| 时刻 | 记录项 |
|---|---|
| 开始 | Band 电量% / Phone 电量% / 计数基线 / 时间 |
| 每小时 | 同上 + disconnectCount + 最后一次 ACK 时间 + UI 截图（如有） |
| 结束（1h/8h/24h） | 全部指标 + runtime restart 次数（进程死亡观察）+ cache 存活确认（关开 App） |

### 3.2 场景注入（24h 内至少一次）

- Phone Bluetooth off → on
- Phone process kill（`adb shell am force-stop com.yunjue.echo.mind`）
- Band app 退出重开
- Band 重连 / Phone 重连

期待：Identity 不变 / cache 安全 / 无第二 Identity / 无随机颜色 / 无红色 ERROR dashboard /
Presence 重连后恢复。

### 3.3 加速度计成本

仅前台订阅（manifest 无后台 sensor）；记录 Band 电池在"常开 ECHO 页"vs"仅打开片刻"的差异。
若耗电明显，按 §4 顺序优化。

## 4. 电池优化顺序（Phase 19，仅实测耗电明显时）

1. lower animation frequency（intervalMs 升档）
2. fewer DOM/visual elements（粒子/环层数下调）
3. lower sensor rate（interval 升档）
4. longer sensor windows（summary 窗口拉长）
5. lower message frequency（推送去抖加宽）
6. reduced visual updates（页面重绘收敛）

**不优先删除**：Identity continuity / Presence semantics。Battery > visual spectacle。

## 5. 结果记录

真机执行后回填 `docs/wearable/ECHO_WRIST_REAL_DEVICE_REPORT.md` 的
"battery / 24h 稳定性" 行；未执行前保持 `BLOCKED_EXTERNAL_LONG_RUN_DEVICE_TIME`。
