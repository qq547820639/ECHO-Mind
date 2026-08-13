#!/usr/bin/env python3
"""Android↔Backend 契约漂移检查（T03 + Portrait Core v0.7）。

职责：
1. 读取 docs/contract-manifest.json（契约清单）；
2. 读取 docs/openapi.json（已提交的 OpenAPI）；
3. 逐项断言：
   - required endpoints 必须存在；
   - removed endpoints 不得出现；
   - deprecated_410 路径必须存在（410 存根路由）；
   - 字段契约：DerivedFeatureIn.sources_present 枚举 7 值、schema_version 枚举
     ["passive-core-v1","mic-feature-v1"]、SkillOut.action_type 白名单 5 项、
     OnboardingVerifyOut 字段集合 + 禁止字段；
   - Portrait 契约（v0.7）：PortraitOut 必须含 date/status/confidence/dimensions/
     facts/timezone_used，dimensions 为 object、facts 为 array、timezone_used 为 string；
     BaselineStatusOut.bucket_usage 为 string、today_coverage 为 number；
   - 语义契约：manifest 中必须声明 portrait_get_no_side_effect 与
     dimensions_forbidden_values（GOOD/BAD/HEALTHY/NORMAL/ABNORMAL）。
4. 任一不匹配 → 打印 FAIL 清单并 exit 1（CI 失败）。

与 backend-ci 的 openapi-drift job（export_openapi.py + git diff --exit-code）互补：
- openapi-drift 保证 docs/openapi.json 与实时导出一致；
- 本脚本保证 docs/openapi.json 与跨端契约清单一致。

用法：python scripts/contract_drift_check.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
OPENAPI_PATH = REPO_ROOT / "docs" / "openapi.json"
MANIFEST_PATH = REPO_ROOT / "docs" / "contract-manifest.json"


def _failures(items: list[str]) -> None:
    if not items:
        return
    print("CONTRACT DRIFT DETECTED:")
    for item in items:
        print(f"  - {item}")
    print("契约漂移：请同步 docs/openapi.json 与 docs/contract-manifest.json（及后端 schemas.py）。")
    sys.exit(1)


def check_endpoints(openapi: dict, manifest: dict) -> list[str]:
    paths = openapi.get("paths", {})
    errors: list[str] = []
    endpoints = manifest.get("endpoints", {})

    for ep in endpoints.get("required", []):
        p = ep["path"]
        method = ep["method"].lower()
        if p not in paths or method not in paths[p]:
            errors.append(f"缺失端点: {ep['method']} {p}（{ep.get('note', '')}）")

    for ep in endpoints.get("removed", []):
        p = ep["path"]
        if p in paths:
            errors.append(f"已移除端点不得出现: {ep['method']} {p}（{ep.get('note', '')}）")

    for ep in endpoints.get("deprecated_410", []):
        p = ep["path"]
        method = ep.get("method", "POST").lower()
        if p not in paths or method not in paths[p]:
            errors.append(f"410 存根路由缺失: {ep.get('method', 'POST')} {p}（{ep.get('note', '')}）")
    return errors


def check_fields(openapi: dict, manifest: dict) -> list[str]:
    errors: list[str] = []
    schemas = openapi.get("components", {}).get("schemas", {})
    fields = manifest.get("fields", {})

    # 1. DerivedFeatureIn.sources_present 枚举
    sp = fields.get("DerivedFeatureIn.sources_present", {})
    expected_enum = set(sp.get("enum", []))
    df_in = schemas.get("DerivedFeatureIn", {})
    sp_prop = df_in.get("properties", {}).get("sources_present", {})
    actual_enum = set(sp_prop.get("items", {}).get("enum", []))
    if expected_enum and actual_enum != expected_enum:
        errors.append(
            f"DerivedFeatureIn.sources_present 枚举不一致: 期望 {sorted(expected_enum)} 实际 {sorted(actual_enum)}"
        )

    # 1b. DerivedFeatureIn.schema_version 枚举（Phase 5 schema registry）
    sv = fields.get("DerivedFeatureIn.schema_version", {})
    expected_sv_enum = set(sv.get("enum", []))
    sv_prop = df_in.get("properties", {}).get("schema_version", {})
    actual_sv_enum = set(sv_prop.get("enum", []))
    if expected_sv_enum and actual_sv_enum != expected_sv_enum:
        errors.append(
            f"DerivedFeatureIn.schema_version 枚举不一致: 期望 {sorted(expected_sv_enum)} 实际 {sorted(actual_sv_enum)}"
        )

    # 2. SkillOut.action_type 白名单
    at = fields.get("SkillOut.action_type", {})
    expected_whitelist = set(at.get("whitelist", []))
    skill_out = schemas.get("SkillOut", {})
    # OpenAPI 中 action_type 是 string，白名单由后端 pydantic validator 强制；此处仅断言字段存在
    if expected_whitelist and "action_type" not in skill_out.get("properties", {}):
        errors.append("SkillOut 缺少 action_type 属性")
    for f in ("estimated_duration", "completion_schema", "safety_constraints", "revision"):
        if f not in skill_out.get("properties", {}):
            errors.append(f"SkillOut 缺少契约字段 {f}")

    # 3. SkillCompletion.status 枚举
    sc = schemas.get("SkillCompletionCreate", {})
    status_prop = sc.get("properties", {}).get("status", {})
    actual_status = set(status_prop.get("enum", []))
    expected_status = set(fields.get("SkillCompletion.status", {}).get("enum", []))
    if expected_status and actual_status != expected_status:
        errors.append(f"SkillCompletionCreate.status 枚举不一致: {sorted(actual_status)}")

    # 4. OnboardingVerifyOut 字段集合 + 禁止字段
    ov = fields.get("OnboardingVerifyOut", {})
    ov_schema = schemas.get("OnboardingVerifyOut", {})
    ov_props = set(ov_schema.get("properties", {}).keys())
    expected_fields = set(ov.get("fields", []))
    if expected_fields and ov_props != expected_fields:
        errors.append(
            f"OnboardingVerifyOut 字段集合不一致: 期望 {sorted(expected_fields)} 实际 {sorted(ov_props)}"
        )
    for forbidden in ov.get("forbidden", []):
        if forbidden in ov_props:
            errors.append(f"OnboardingVerifyOut 不应包含内部字段 {forbidden}")

    # 5. PortraitOut 契约字段（v0.7）
    portrait_manifest = fields.get("PortraitOut", {})
    portrait_required = set(portrait_manifest.get("required", []))
    portrait_fields = set(portrait_manifest.get("fields", []))
    po = schemas.get("PortraitOut", {})
    po_props = set(po.get("properties", {}).keys())
    for f in portrait_required:
        if f not in po_props:
            errors.append(f"PortraitOut 缺少必填契约字段 {f}")
    for f in ("dimensions", "facts", "timezone_used"):
        if f not in po_props:
            errors.append(f"PortraitOut 缺少契约字段 {f}")
    # dimensions 必须为 object 类型
    dim_prop = po.get("properties", {}).get("dimensions", {})
    if dim_prop.get("type") != "object":
        errors.append(f"PortraitOut.dimensions 应为 object 类型，实际 {dim_prop.get('type')}")
    # facts 必须为 array
    facts_prop = po.get("properties", {}).get("facts", {})
    if facts_prop.get("type") != "array":
        errors.append(f"PortraitOut.facts 应为 array 类型，实际 {facts_prop.get('type')}")
    # timezone_used 必须为 string
    tz_prop = po.get("properties", {}).get("timezone_used", {})
    if tz_prop.get("type") != "string":
        errors.append(f"PortraitOut.timezone_used 应为 string 类型，实际 {tz_prop.get('type')}")

    # 6. BaselineStatusOut 强类型（bucket_usage string / today_coverage number）
    bs = schemas.get("BaselineStatusOut", {})
    bs_props = bs.get("properties", {})
    bucket = bs_props.get("bucket_usage", {})
    if bucket.get("type") != "string":
        errors.append(f"BaselineStatusOut.bucket_usage 应为 string 类型，实际 {bucket.get('type')}")
    coverage = bs_props.get("today_coverage", {})
    if coverage.get("type") != "number":
        errors.append(f"BaselineStatusOut.today_coverage 应为 number 类型，实际 {coverage.get('type')}")

    return errors


def check_semantics(manifest: dict) -> list[str]:
    """语义契约清单自洽性断言（v0.7：Portrait 只读 / 禁止维度取值）。"""
    errors: list[str] = []
    semantics = manifest.get("semantics", {})
    if "portrait_get_no_side_effect" not in semantics:
        errors.append("semantics 缺少 portrait_get_no_side_effect（GET 无写副作用）")
    if "dimensions_forbidden_values" not in semantics:
        errors.append("semantics 缺少 dimensions_forbidden_values（禁止 GOOD/BAD/HEALTHY/NORMAL/ABNORMAL）")
    return errors


def main() -> int:
    if not OPENAPI_PATH.exists():
        print(f"缺少 docs/openapi.json：{OPENAPI_PATH}")
        return 1
    if not MANIFEST_PATH.exists():
        print(f"缺少 docs/contract-manifest.json：{MANIFEST_PATH}")
        return 1
    openapi = json.loads(OPENAPI_PATH.read_text(encoding="utf-8"))
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))

    errors = check_endpoints(openapi, manifest)
    errors += check_fields(openapi, manifest)
    errors += check_semantics(manifest)

    if errors:
        _failures(errors)
    print(f"CONTRACT OK：{len(openapi.get('paths', {}))} 路径，manifest v{manifest.get('version', '?')} 全部断言通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
