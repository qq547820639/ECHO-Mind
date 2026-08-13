"""Feature Schema Registry（Phase 5, C1）：派生特征 schema 的单一事实来源。

设计目标：
- 集中记录每个 schema 的 source 组合 / vector 布局 / 聚合资格 / 保留期 / 隐私分级，
  取代散落在 calculator.py 头部注释里的隐式布局约定；
- ingest 校验（DerivedFeatureIn）查本注册表：schema_version 必须在册、
  schema/source 组合必须匹配，否则 422 拒绝；
- 后端聚合只消费 ``aggregate_eligible`` 的 schema（mic_opt 等外围特征可 ingest
  存储但不进入 DailyBehaviorAggregate）。

布局约定（与 Android FeatureExtractor / MicFeatureExtractor 对齐）：
- passive-core-v1 的 22 维布局与 calculator.py 头注释一致；
- mic-feature-v1 的 256 维布局与 MicFeatureExtractor.VECTOR_DIM=256 一致
  （0..7 标量 + 4×32-bin 直方图 + 补零）。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Final

#: 显式 Final：让常量携带 Literal 类型，供 schemas.DerivedFeatureIn.schema_version
#: 的 Literal 默认值直接引用（避免 str 常量被赋给 Literal 字段时报 assignment 错误）。
PASSIVE_CORE_V1: Final = "passive-core-v1"
MIC_FEATURE_V1: Final = "mic-feature-v1"

#: passive-core-v1 22 维布局：index → (name, unit)（0-21 与 calculator.py 头注释一致）
PASSIVE_FIELD_INDICES: dict[int, tuple[str, str]] = {
    0: ("accel_mean_x", "m/s²"),
    1: ("accel_mean_y", "m/s²"),
    2: ("accel_mean_z", "m/s²"),
    3: ("accel_std_x", "m/s²"),
    4: ("accel_std_y", "m/s²"),
    5: ("accel_std_z", "m/s²"),
    6: ("accel_magnitude_mean", "m/s²"),
    7: ("accel_magnitude_std", "m/s²"),
    8: ("gyro_mean_x", "rad/s"),
    9: ("gyro_mean_y", "rad/s"),
    10: ("gyro_mean_z", "rad/s"),
    11: ("gyro_std_x", "rad/s"),
    12: ("gyro_std_y", "rad/s"),
    13: ("gyro_std_z", "rad/s"),
    14: ("screen_on_count", "count"),
    15: ("screen_off_count", "count"),
    16: ("screen_on_duration_ms", "ms"),
    17: ("notification_total_count", "count"),
    18: ("notification_social_count", "count"),
    19: ("notification_other_count", "count"),
    20: ("app_switch_count", "count"),
    21: ("app_top_app_duration_ms", "ms"),
}

#: mic-feature-v1 256 维布局（MicFeatureExtractor.VECTOR_DIM=256）：
#: 0..7 标量特征 + 4×32-bin 直方图 + 136..255 补零。
#: 直方图块以起始 index 表示（逐 bin 无业务命名）。
MIC_FIELD_INDICES: dict[int, tuple[str, str]] = {
    0: ("rms_db_norm", "0..1"),
    1: ("speech_rate", "count/s"),
    2: ("pause_count", "count"),
    3: ("f0_mean_norm", "0..1"),
    4: ("energy_std_ratio", "0..1"),
    5: ("energy_min", "raw"),
    6: ("energy_max", "raw"),
    7: ("zcr_mean", "ratio"),
    8: ("energy_hist_32", "block 8..39"),
    40: ("energy_delta_hist_32", "block 40..71"),
    72: ("zcr_hist_32", "block 72..103"),
    104: ("f0_hist_32", "block 104..135"),
    136: ("reserved_padding", "block 136..255"),
}


@dataclass(frozen=True)
class FeatureSchema:
    """一条派生特征 schema 的注册信息。"""

    schema_id: str
    #: 允许声明该 schema 的 source 集合（与 DerivedFeatureIn.source Literal 对齐）
    sources: tuple[str, ...]
    #: vector 固定维度（越界下标防御读 0）
    vector_length: int
    #: index → (name, unit) 布局映射（未列出的 index 视为保留/补零）
    field_indices: dict[int, tuple[str, str]]
    #: 主单位描述（整条 schema 的通用单位，None 表示混合单位）
    unit: str | None
    #: 是否参与 DailyBehaviorAggregate 统计（False=外围特征，仅 ingest 存储）
    aggregate_eligible: bool
    #: 保留期（天）
    retention_days: int
    #: 隐私分级（与 consent 类型对齐：passive_sensing / voice_features）
    privacy_classification: str


#: 注册表：schema_id → FeatureSchema（Phase 5 起点仅两条，后续新增在此登记）
SCHEMA_REGISTRY: dict[str, FeatureSchema] = {
    PASSIVE_CORE_V1: FeatureSchema(
        schema_id=PASSIVE_CORE_V1,
        sources=("accel", "gyro", "screen", "notification", "app_activity", "health"),
        vector_length=22,
        field_indices=PASSIVE_FIELD_INDICES,
        unit=None,
        aggregate_eligible=True,
        retention_days=28,
        privacy_classification="passive_sensing",
    ),
    MIC_FEATURE_V1: FeatureSchema(
        schema_id=MIC_FEATURE_V1,
        sources=("mic_opt",),
        vector_length=256,
        field_indices=MIC_FIELD_INDICES,
        unit=None,
        aggregate_eligible=False,
        retention_days=28,
        privacy_classification="voice_features",
    ),
}

#: 参与聚合统计的 schema 集合（upsert_daily_aggregate 查询过滤用）
AGGREGATE_ELIGIBLE_SCHEMA_IDS: tuple[str, ...] = tuple(
    s.schema_id for s in SCHEMA_REGISTRY.values() if s.aggregate_eligible
)

#: 参与聚合统计的信号源（health 仅在 registry 声明、经 sources_present 参与覆盖度，
#: 但无 22 维布局中的统计槽位，故不进入统计值计算）
AGGREGATE_SOURCES: tuple[str, ...] = (
    "accel",
    "gyro",
    "screen",
    "notification",
    "app_activity",
)


def get_schema(schema_id: str) -> FeatureSchema | None:
    """按 schema_id 查询注册信息；未注册返回 None。"""
    return SCHEMA_REGISTRY.get(schema_id)


def is_aggregate_eligible(schema_id: str) -> bool:
    """schema 是否可参与 DailyBehaviorAggregate 统计。"""
    schema = get_schema(schema_id)
    return bool(schema is not None and schema.aggregate_eligible)


def schema_for_source(source: str) -> FeatureSchema | None:
    """返回声明了该 source 的 schema（按注册顺序取第一个）。"""
    for schema in SCHEMA_REGISTRY.values():
        if source in schema.sources:
            return schema
    return None


def validate_schema_source(schema_id: str, source: str) -> str | None:
    """校验 schema/source 组合；合法返回 None，否则返回可读错误信息。"""
    schema = get_schema(schema_id)
    if schema is None:
        return f"unsupported feature schema version: {schema_id}"
    if source not in schema.sources:
        allowed = ", ".join(schema.sources)
        return f"source '{source}' is not valid for schema '{schema_id}' (allowed: {allowed})"
    return None
