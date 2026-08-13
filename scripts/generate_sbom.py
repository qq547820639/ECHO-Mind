#!/usr/bin/env python3
import json
import re
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
packages = []

# v0.7.1 修复：SBOM 名称/命名空间从版本事实源读取（原硬编码 v0.2.0 长期过期）。
VERSION = json.loads((ROOT / "scripts/version_source.json").read_text(encoding="utf-8"))[
    "release_version"
]

pyproject = (ROOT / "backend/pyproject.toml").read_text(encoding="utf-8")
for match in re.finditer(r'^\s*"([A-Za-z0-9_.-]+)([^\"]*)",?$', pyproject, re.MULTILINE):
    name, constraint = match.groups()
    packages.append({
        "SPDXID": f"SPDXRef-Python-{name}",
        "name": name,
        "versionInfo": constraint.strip() or "declared",
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

spdx = {
    "spdxVersion": "SPDX-2.3",
    "dataLicense": "CC0-1.0",
    "SPDXID": "SPDXRef-DOCUMENT",
    "name": f"ECHO-Mind-PortraitCore-v{VERSION}-source-SBOM",
    "documentNamespace": f"https://example.invalid/echo-mind/portrait-core/v{VERSION}/sbom",
    "creationInfo": {
        "created": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "creators": ["Tool: scripts/generate_sbom.py"],
    },
    "packages": packages,
    "annotations": [{
        "annotationType": "OTHER",
        "annotator": "Tool: scripts/generate_sbom.py",
        "annotationDate": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "comment": "Source declaration SBOM. License conclusions and resolved transitive dependencies require CI/Anchore review.",
    }],
}
(ROOT / "sbom.spdx.json").write_text(json.dumps(spdx, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"generated {len(packages)} declared packages")
