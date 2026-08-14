"""画像路由（v0.6.1 拆分）：只读画像缓存 + 显式重建。

- GET 绝不 rebuild / version+1 / commit（无任何写副作用）；
- 重建为显式动作（POST /rebuild），写入审计。
"""
from __future__ import annotations
from typing import Any

from fastapi import APIRouter, HTTPException

from app.services.audit import append_audit
from app.services.profile import get_profile as get_profile_cached
from app.services.profile import rebuild_profile

from app.api.deps import DB, PRINCIPAL, ensure_user

router = APIRouter(prefix="/v1")


@router.get("/profile/{user_id}")
def get_profile(user_id: str, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    """只读画像缓存：绝不 rebuild / version+1 / commit（无任何写副作用）。"""
    ensure_user(db, principal, user_id)
    row = get_profile_cached(db, tenant_id=principal.tenant_id, user_id=user_id)
    if row is None:
        raise HTTPException(status_code=404, detail="profile not built; POST /v1/profile/{user_id}/rebuild to build")
    return {"user_id": user_id, "traits": row.traits, "version": row.version, "updated_at": row.updated_at}


@router.post("/profile/{user_id}/rebuild")
def rebuild_user_profile(user_id: str, db: DB, principal: PRINCIPAL) -> dict[str, Any]:
    """显式重建画像：traits + version+1；仅写路径调用。"""
    ensure_user(db, principal, user_id)
    row = rebuild_profile(db, tenant_id=principal.tenant_id, user_id=user_id)
    append_audit(db, tenant_id=principal.tenant_id, actor_type=principal.role, actor_id=principal.subject,
                 action="profile.rebuild", object_type="user_profile", object_id=row.id,
                 metadata={"version": row.version})
    db.commit()
    return {"user_id": user_id, "traits": row.traits, "version": row.version, "updated_at": row.updated_at}
