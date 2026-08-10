"""P5 灰度回滚：租户级 feature flag 查询/修改服务。

v0.6 fail-closed 语义（02b 共享知识第 2 条）：
- 隐私敏感 flag（passive_sensing_enabled / sandbox_enabled）在未知 key、
  tenant 不存在、字段缺失时一律 **false**（不采集、不跑沙箱）；
- skills_delivery_enabled 非隐私敏感，可默认 true；
- 已显式配置的租户（feature_flags JSON 中字段存在）不受影响。

不写审计，由路由层负责；不 commit（set_tenant_flag 例外，因为它独立成操作）。
"""
from __future__ import annotations

from sqlalchemy.orm import Session

from app.models import Tenant

#: 已显式声明/授权租户的默认值（保持既有测试与部署兼容）。
DEFAULT_FEATURE_FLAGS: dict[str, bool] = {
    "passive_sensing_enabled": True,
    "sandbox_enabled": True,
    "skills_delivery_enabled": True,
}

#: fail-closed 回退值：隐私敏感开关未知/缺失一律 false；skills_delivery 可默认 true。
FAIL_CLOSED_DEFAULTS: dict[str, bool] = {
    "passive_sensing_enabled": False,
    "sandbox_enabled": False,
    "skills_delivery_enabled": True,
}

#: 允许被 PUT /v1/tenant/flags 修改的键集合。
ALLOWED_FLAG_KEYS: frozenset[str] = frozenset(DEFAULT_FEATURE_FLAGS)


def tenant_exists(db: Session, tenant_id: str) -> bool:
    """租户是否存在于当前 DB。"""
    return db.get(Tenant, tenant_id) is not None


def get_tenant_flags(db: Session, tenant_id: str) -> dict[str, bool]:
    """返回租户的 feature_flags。

    - tenant 不存在 → fail-closed 回退（敏感 flag false）
    - tenant 存在但字段缺失 → 该键 fail-closed（false），其余按显式值
    - skills_delivery_enabled 未显式设置时默认 true
    """
    tenant = db.get(Tenant, tenant_id)
    if tenant is None:
        return dict(FAIL_CLOSED_DEFAULTS)
    raw = tenant.feature_flags or {}
    result = dict(FAIL_CLOSED_DEFAULTS)
    for key in DEFAULT_FEATURE_FLAGS:
        if key in raw:
            result[key] = bool(raw[key])
    return result


def set_tenant_flag(db: Session, tenant_id: str, key: str, value: bool) -> dict[str, bool]:
    """更新租户的某个 flag 并 commit；返回更新后的完整 flags dict。

    若 tenant 不存在则抛出 ValueError（路由层应转 404）；若 key 不在默认
    flag 集合内则忽略（避免任意写入未知 flag）。
    """
    if key not in ALLOWED_FLAG_KEYS:
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
    """便捷查询：返回某 flag 是否开启。未知 key 默认 False（fail-closed）。"""
    flags = get_tenant_flags(db, tenant_id)
    return bool(flags.get(key, False))
