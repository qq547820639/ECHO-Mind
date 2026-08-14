# Affective Contract —— 可选情绪智能契约

> 状态：v1.0 · 冻结（门槛文档）· **任何 Affective Intelligence 实现前必须满足本契约全部条款。**
> 位置：`docs/intelligence/AFFECTIVE_CONTRACT.md`
> 规则：Affective 是**独立版本化能力**，永远不偷偷扩展 Portrait。未满足本契约前，
> 系统内 `affectiveState` 恒为 null，一切输出统一称为「状态、节律、变化」，不称「情绪感知」。

## 1. 授权（explicit opt-in）

- 单独 consent 类型（`affective_intelligence`），与 `passive_sensing` 完全独立；
- 独立开关（Me → Intelligence → Affective），默认**关闭**；
- 开启前必须单独解释：用什么信号、推断什么、不推断什么、数据去哪、如何撤回；
- 撤回立即生效（复用 voice_features 的 consent 证据 + 412 校验闭环模式）。

## 2. 允许 / 禁止的信号

允许（仅在有对应授权时）：
- 已授权的行为节律特征（作为上下文，不单独成为情绪证据）；
- 用户主动陈述（Felt——最高优先，不需要推断）；
- 未来经评审新增的信号：每项必须单独过 PIPIA 与数据最小化审计。

禁止：
- 原始音频、通知内容、消息正文；
- 键盘输入、屏幕内容、照片、位置轨迹；
- 任何未经单独授权的生理/心理信号。

## 3. 表示：连续 latent state，禁止标签

禁止 `HAPPY/SAD/ANXIOUS` 作为主要内部表示。使用连续维度：

```text
activation / pleasantness / tension / mental_load /
social_load / recovery_need / certainty
```

每个值独立携带：`confidence / source / updatedAt`。状态表达必须支持「不确定」。

## 4. 置信度门槛

| 置信度 | 允许的行为 |
|---|---|
| 低 | 仅抽象视觉调制；禁止用户语言；禁止干预；禁止写入强记忆 |
| 中 | 允许带不确定性措辞的私有语言（应用内）；仍禁止干预 |
| 高 | 允许私有叙事；仍禁止基于情绪的主动干预（见 §7） |

- 允许输出 `UNKNOWN / INSUFFICIENT_EVIDENCE / CONFLICTING_EVIDENCE`；
- 门槛数值由离线模型验证确定（§8），写入本契约 v1.1。

## 5. 保留政策

- 推断结果默认**不落盘**（临时解释，EPHEMERAL 类记忆）；
- 用户确认过的 affective 陈述按 USER_CONFIRMED 记忆规则保留；
- 撤回授权 → 删除全部 affective 推断与记忆（保留撤回审计记录）。

## 6. 用户纠错

- 每条 affective 判断允许「像我/不太像」+ 原因；
- **Felt outranks inference**：用户说「其实我今天特别开心」→ 以用户为准，推断立即作废；
- 纠错进入 Correction Memory，用于该用户校准（per-user，不跨用户）。

## 7. 视觉 / 语言 / 干预政策

- 视觉：只允许连续调制（如弥散度、呼吸周期），**禁止**「情绪色」图例（禁止用户学会某色=某情绪）；
- 语言：仅私有叙事（应用内），锁屏/壁纸/屏保永不可见；措辞必须不确定（「可能有些紧绷」）；
- 干预：affective 推断**永不**自动触发通知、支持请求、危机链路；Safety/人工 Support 与 affective 链不自动连接；
- 医疗边界：不宣称诊断、不识别疾病、不生成健康建议。

## 8. 模型验证（实现前置）

- 离线验证集（合成 + 试点脱敏数据）：grounding / overreach / calibration 三组指标；
- 通过标准（示例，需临床/安全评审定稿）：心理越界输出率 < 阈值、置信度校准误差 < 阈值；
- 同一 fixture 必须能在不同 Provider 上复跑（`docs/intelligence/AI_EVAL.md`，实现时建立）。

## 9. 隐私审查（实现前置）

- PIPIA 更新：新增信号、推断、保留、删除的完整数据流图；
- 数据最小化审计：每个 affective 字段回答「对个人化理解是否必要」；
- 上云规则：affective 推断默认端侧；如需上云必须走独立 opt-in + 单独审计。

## 10. 错误恢复（实现前置）

- Provider 失败 → affective 输出静默降级为 null（视觉回退节律状态）；
- 用户纠错优先于模型输出；连续纠正 N 次 → 该维度进入低置信模式；
- 撤回/删除路径与数据权利矩阵对齐（分项删除包含 Affective）。

## 11. 版本与评审

- 本契约变更需产品 + 临床/安全评审；
- 实现任何 affective 代码前：本契约 §8/§9/§10 全部完成 → 才能把 `affectiveState` 置为非 null。
