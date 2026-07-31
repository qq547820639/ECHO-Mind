"""P5 灰度回滚：租户级 feature flag 查询/修改服务。

提供对 Tenant.feature_flags 字段的统一访问入口：
- get_tenant_flags：返回租户当前 flags（tenant 不存在或字段为 None 时回退默认）
- set_tenant_flag：更新单个 flag 并 commit
- is_flag_enabled：便捷查询某 flag 是否开启

不写审计，由路由层负责；不 commit（set_tenant_flag 例外，因为它独立成操作）。
"""
from __future__ import annotations

from sqlalchemy.orm import Session

from app.models import Tenant


DEFAULT_FEATURE_FLAGS: dict[str, bool] = {
    "passive_sensing_enabled": True,
    "sandbox_enabled": True,
    "skills_delivery_enabled": True,
}


def get_tenant_flags(db: Session, tenant_id: str) -> dict[str, bool]:
    """返回租户的 feature_flags；tenant 不存在或字段为 None 时回退默认。"""
    tenant = db.get(Tenant, tenant_id)
    if tenant is None or tenant.feature_flags is None:
        return dict(DEFAULT_FEATURE_FLAGS)
    # 合并默认值，避免新增 flag 时旧租户缺字段
    merged = dict(DEFAULT_FEATURE_FLAGS)
    merged.update(tenant.feature_flags)
    return merged


def set_tenant_flag(db: Session, tenant_id: str, key: str, value: bool) -> dict[str, bool]:
    """更新租户的某个 flag 并 commit；返回更新后的完整 flags dict。

    若 tenant 不存在则抛出 ValueError（路由层应转 404）；若 key 不在默认
    flag 集合内则忽略（避免任意写入未知 flag）。
    """
    if key not in DEFAULT_FEATURE_FLAGS:
        raise ValueError(f"unknown feature flag: {key}")
    tenant = db.get(Tenant, tenant_id)
    if tenant is None:
        raise ValueError(f"tenant not found: {tenant_id}")
    flags = dict(DEFAULT_FEATURE_FLAGS)
    flags.update(tenant.feature_flags or {})
    flags[key] = bool(value)
    tenant.feature_flags = flags
    db.commit()
    db.refresh(tenant)
    return get_tenant_flags(db, tenant_id)


def is_flag_enabled(db: Session, tenant_id: str, key: str) -> bool:
    """便捷查询：返回某 flag 是否开启。未知 key 默认 True（保守启用）。"""
    flags = get_tenant_flags(db, tenant_id)
    return bool(flags.get(key, True))
