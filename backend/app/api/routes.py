"""API 聚合入口（v0.6.1）：按 bounded context 拆分后的统一 router。

每个子模块只依赖本领域 schema/service；共享 helper 集中在 app.api.deps；
本文件不再承载业务逻辑（routes.py 不再是 service layer）。

URL / OpenAPI contract 与拆分前完全一致（legacy 410/405 存根见 app.api.legacy）。
"""
from __future__ import annotations

from fastapi import APIRouter

from app.api import (
    admin,
    consent,
    data_rights,
    escalations,
    features,
    legacy,
    narratives,
    onboarding,
    portraits,
    profiles,
    sandbox,
    skills,
)

router = APIRouter()  # 无 prefix：各子 router 自带 /v1 prefix

#: 聚合顺序不影响 URL 匹配（各子 router 均无路径冲突）；保持可读性分组。
router.include_router(onboarding.router)
router.include_router(consent.router)
router.include_router(features.router)
router.include_router(narratives.router)
router.include_router(portraits.router)
router.include_router(profiles.router)
router.include_router(escalations.router)
router.include_router(data_rights.router)
router.include_router(skills.router)
router.include_router(sandbox.router)
router.include_router(admin.router)
router.include_router(legacy.router)
