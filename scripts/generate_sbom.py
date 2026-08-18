#!/usr/bin/env python3
import json
import re
import sys
from pathlib import Path
from datetime import datetime, timezone

if sys.version_info >= (3, 11):
    import tomllib
else:
    tomllib = None

ROOT = Path(__file__).resolve().parents[1]
packages = []

# v0.7.1 修复：SBOM 名称/命名空间从版本事实源读取（原硬编码 v0.2.0 长期过期）。
VERSION = json.loads((ROOT / "scripts/version_source.json").read_text(encoding="utf-8"))[
    "release_version"
]

# ERA 18 §96：backend 依赖以 uv.lock 为准（精确版本 + 完整传递闭包）。
# T7-P2-1 fail-closed（2026-08-18 深化轮）：python < 3.11（无 tomllib）或 uv.lock
# 缺失/不可解析时**报错退出**——曾存在的 pyproject 声明范围静默回退已删除
#（该回退曾致 SBOM 45 包静默降级，丢 35 个传递依赖且无任何告警/标记）。
if tomllib is None:
    raise SystemExit(
        f"FAIL：SBOM 生成需要 python >= 3.11（tomllib）解析 uv.lock 闭包；"
        f"当前 python {sys.version.split()[0]}。fail-closed：禁止回退 pyproject 声明范围。"
    )
lock_path = ROOT / "backend" / "uv.lock"
if not lock_path.is_file():
    raise SystemExit("FAIL：backend/uv.lock 缺失——SBOM 必须由 lock 闭包生成（fail-closed，无回退）。")
try:
    lock = tomllib.loads(lock_path.read_text(encoding="utf-8"))
except Exception as exc:  # tomllib.TOMLDecodeError 等
    raise SystemExit(f"FAIL：uv.lock 不可解析（{exc}）——fail-closed：禁止回退 pyproject 声明范围。")
for pkg in lock.get("package", []):
    name = pkg.get("name", "")
    version = pkg.get("version", "")
    if not name:
        continue
    packages.append({
        "SPDXID": f"SPDXRef-Python-{re.sub(r'[^A-Za-z0-9.-]', '-', name)}",
        "name": name,
        "versionInfo": version or "declared",
        "downloadLocation": "NOASSERTION",
        "licenseConcluded": "NOASSERTION",
        "licenseDeclared": "NOASSERTION",
        "supplier": "NOASSERTION",
    })

catalog = (ROOT / "android/gradle/libs.versions.toml").read_text(encoding="utf-8")
versions = dict(re.findall(r'^(\w+)\s*=\s*"([^"]+)"$', catalog, re.MULTILINE))
for alias, module, version_ref, direct in re.findall(
    r'^(\S+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"(?:,\s*version\.ref\s*=\s*"([^"]+)")?(?:,\s*version\s*=\s*"([^"]+)")?\s*\}',
    catalog,
    re.MULTILINE,
):
    version = direct or versions.get(version_ref, "BOM-managed")
    packages.append({
        "SPDXID": f"SPDXRef-Android-{re.sub(r'[^A-Za-z0-9.-]', '-', alias)}",
        "name": module,
        "versionInfo": version,
        "downloadLocation": "NOASSERTION",
        "licenseConcluded": "NOASSERTION",
        "licenseDeclared": "NOASSERTION",
        "supplier": "NOASSERTION",
    })

def sbom_timestamp() -> str:
    """§96：SBOM 时间戳 = 版本事实源 sbom_created_utc（版本冻结，commit 无关）。

    语义：SBOM 描述 release vX.Y.Z——created = 该发布周期冻结日期；版本升级时才更新。
    这样任何 checkout/任何提交顺序下重生成 SBOM 都字节一致（无 commit-SHA 循环依赖）。
    """
    try:
        version_source = json.loads((ROOT / "scripts" / "version_source.json").read_text(encoding="utf-8"))
        ts = version_source.get("sbom_created_utc")
        if ts:
            return datetime.fromisoformat(ts).astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    except Exception:
        pass
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

TS = sbom_timestamp()
spdx = {
    "spdxVersion": "SPDX-2.3",
    "dataLicense": "CC0-1.0",
    "SPDXID": "SPDXRef-DOCUMENT",
    "name": f"ECHO-Mind-PortraitCore-v{VERSION}-source-SBOM",
    "documentNamespace": f"https://example.invalid/echo-mind/portrait-core/v{VERSION}/sbom",
    "creationInfo": {
        "created": TS,
        "creators": ["Tool: scripts/generate_sbom.py"],
    },
    "packages": packages,
    "annotations": [{
        "annotationType": "OTHER",
        "annotator": "Tool: scripts/generate_sbom.py",
        "annotationDate": TS,
        "comment": "Source declaration SBOM. License conclusions and resolved transitive dependencies require CI/Anchore review.",
    }],
}
(ROOT / "sbom.spdx.json").write_text(json.dumps(spdx, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"generated {len(packages)} declared packages")
