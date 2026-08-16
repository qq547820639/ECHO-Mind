# ANSWATCH INTEGRATION CONTRACT

> ANSWatch = **High-fidelity Wrist Observation Reference / Source**。
> 不是 ECHO。不是 Stress Product。README-ONLY 参考仓（git HEAD `c376300`，2026-08-16 核对）。

## 1. 角色与边界

- 最值得继承：Signal Quality / Artifact Handling / Personal Baseline /
  Motion Disambiguation / Confidence / UNKNOWN / abstention。
- 禁止：把整个 ANSWatch 复制进 ECHO Mind；把 ANSWatch 当产品主仓；
  为方便重写其科学结论；本阶段把整套 Python wrist_pipeline 重写为 Kotlin
  （先做 Schema → Golden → decoder → mapper → research policy → tests）。

## 2. 输出契约（ANS_FRAME_V1）

- 事实源：ANSWatch `wrist_pipeline/output_schema.py`（`make_output`/`validate_output`）
  + SPEC-2 §8.1。冻结 schema：`integrations/answatch/ANS_FRAME_V1.schema.json`。
- 帧：60s 窗口 / 5s hop；字段：
  `schema_version("1.0") / subject_id / window_id / window_end_timestamp(µs) /
  signal_quality{sqi_eda,sqi_ppg,sqi_temp,sqi_imu(0..1),on_body,repaired} /
  physiological_arousal{activation(0..100),trend_30min} /
  stress_likelihood{value(0..1),disambiguation_flags[]} /
  confidence{value,calibrated,calibration_model_id} /
  recovery{active_event,tau_min,recovery_score} /
  physical_activity{activity_level(0..3),motion_energy,minutes_since_vigorous} /
  baseline_deviation{z_composite,layer,baseline_ready} /
  unknown{is_unknown,reason}`。
- UNKNOWN 原因码（SPEC-2 §7.6 冻结）：`SIGNAL_MOTION_COMPOUND / DUAL_MODALITY_DOWN /
  MODALITY_DOWN / OFF_BODY / CLOCK_FAULT / LOW_CONFIDENCE / CONTEXT_CONFLICT /
  BASELINE_NOT_READY`。
- UNKNOWN 语义：activation/stress/recovery 序列化为 JSON **null**（不是 0/normal/low）；
  signal_quality 永远如实上报；unknown.is_unknown=true 时 reason 必填。
- 代码已知偏差（保持）：activity_level 实际 4 级（0..3）；SCL_percentile 标量化 p90−p10；
  recovery.tau_min 恒 null。

## 3. Golden 门

- Golden 帧：`integrations/answatch/golden/ans_frame_ok_v1.json` +
  `ans_frame_unknown_offbody_v1.json`（取自真实契约字段与 SPEC-2 §8.1 示例值）。
- 跨语言黄金门：`integrations/answatch/tools/verify_golden.py` 断言
  ANS_FRAME_V1 schema 有效 + golden 帧通过 schema + Kotlin 测试内嵌 golden
  与仓库 golden 逐字节一致（Python output ↔ ANS_FRAME_V1 ↔ Kotlin decoder）。
- Kotlin decoder：`:feature:wearable` `research/AnsObservationFrame.kt`（org.json；
  null 与缺失区分；未知字段忽略 forward compatible）。

## 4. 映射与晋升（AnsObservationMapper / AnsPromotionPolicy）

| ANS 字段 | ECHO 角色 |
|---|---|
| signal_quality | evidence quality（GOOD/REVIEW/POOR/ABSTAINED；SQI ≥0.70 PASS，<0.40 REJECT） |
| physical_activity | neutral wrist activity（validation-gated） |
| physiological_arousal.activation | Research / validation-gated neutral observation |
| recovery.recovery_score | Research / validation-gated |
| baseline_deviation.z_composite | Research / validation-gated |
| stress_likelihood.value | **RESEARCH_ONLY（AFFECTIVE 硬锁，本次绝不解锁）** |
| unknown.is_unknown | **ECHO ABSTENTION（端到端保留）** |

- 晋升门（全部满足才晋升中性活动证据）：真机验证（clock/on-body/SQI/missingness/
  motion artifact/baseline/UNKNOWN rate）→ 设备 SQI 标定 → baseline_ready →
  非 abstain + on_body + SQI≥PASS。
- 禁止把 lab benchmark 变产品宣称（WESAD AUROC 0.9029 是 lab 结果；
  其限制必须保留：device SQI transfer / missingness confounding / sensor availability
  （WESAD E4 无陀螺仪）/ subject heterogeneity / purity selection bias）。

## 5. 硬件拓扑（未来）

ANSWatch Hardware →（BLE）→ Android ECHO → Observation。
**不是** ANSWatch → Xiaomi Band。Band 无公开 generic BLE 事实时绝不 reverse engineer。

## 6. 阻塞

`BLOCKED_EXTERNAL_ANS_HARDWARE`：无真机。已完成：schema / golden / decoder / mapper /
promotion policy / 全部测试。硬件到位后的确切人工步骤：先验 clock/on-body/SQI/
missingness/motion artifact/baseline/UNKNOWN rate，再验 physiological activation，
最后才研究 stress。禁止的宣称：ANSWatch 硬件已验证；stress 产品化。
