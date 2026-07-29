#!/usr/bin/env python3
"""Static checks for ECHO Mind Android release hardening (spec Task 8).

Runs without an Android SDK: verifies configuration files only.
Prints PASS/FAIL per item and exits non-zero on any failure.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ANDROID = REPO_ROOT / "android"
APP = ANDROID / "app"

MAIN_MANIFEST = APP / "src/main/AndroidManifest.xml"
DEBUG_MANIFEST = APP / "src/debug/AndroidManifest.xml"
MAIN_NSC = APP / "src/main/res/xml/network_security_config.xml"
DEBUG_NSC = APP / "src/debug/res/xml/network_security_config.xml"
PROGUARD = APP / "proguard-rules.pro"
APP_GRADLE = APP / "build.gradle.kts"
VERSIONS_TOML = ANDROID / "gradle/libs.versions.toml"
GITIGNORE = REPO_ROOT / ".gitignore"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def cleartext_permitted(path: Path) -> str | None:
    """Return base-config cleartextTrafficPermitted value, or None if missing."""
    if not path.is_file():
        return None
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return None
    base = root.find("base-config")
    if base is None:
        return None
    return base.get("cleartextTrafficPermitted")


# 1. Release must not enable cleartext HTTP.
main_nsc_value = cleartext_permitted(MAIN_NSC)
gradle_text = read(APP_GRADLE)
release_block = re.search(r"release\s*\{(.*?)\n\s*\}", gradle_text, re.DOTALL)
release_gradle_cleartext_false = bool(
    release_block and re.search(r'usesCleartextTraffic"\]\s*=\s*"false"', release_block.group(1))
)
check(
    "release_cleartext_disabled",
    main_nsc_value == "false" and release_gradle_cleartext_false,
    f"main network_security_config base-config cleartextTrafficPermitted={main_nsc_value!r}; "
    f"gradle release placeholder false={release_gradle_cleartext_false}",
)

# 2. Main manifest must reference networkSecurityConfig.
main_manifest = read(MAIN_MANIFEST)
check(
    "main_manifest_references_network_security_config",
    "android:networkSecurityConfig" in main_manifest,
    "android:networkSecurityConfig present in src/main/AndroidManifest.xml"
    if "android:networkSecurityConfig" in main_manifest
    else "missing android:networkSecurityConfig in src/main/AndroidManifest.xml",
)

# 3. Debug and release network configs must be separated.
debug_manifest = read(DEBUG_MANIFEST)
debug_nsc_value = cleartext_permitted(DEBUG_NSC)
check(
    "debug_release_network_config_separated",
    (
        "android:networkSecurityConfig" in debug_manifest
        and 'usesCleartextTraffic="true"' in debug_manifest.replace(" ", "")
        and debug_nsc_value == "true"
        and main_nsc_value == "false"
    ),
    f"debug manifest override={'android:networkSecurityConfig' in debug_manifest}; "
    f"debug cleartext={debug_nsc_value!r}; release cleartext={main_nsc_value!r}",
)

# 4. proguard-rules.pro exists and is non-empty.
proguard_text = read(PROGUARD)
proguard_meaningful = any(
    line.strip() and not line.strip().startswith("#") for line in proguard_text.splitlines()
)
check(
    "proguard_rules_present",
    PROGUARD.is_file() and proguard_meaningful,
    f"{PROGUARD.relative_to(REPO_ROOT)} exists with {len(proguard_text.splitlines())} lines"
    if PROGUARD.is_file()
    else "proguard-rules.pro missing",
)

# 5. build.gradle.kts release minify config is sane.
minify_ok = bool(release_block and re.search(r"isMinifyEnabled\s*=\s*true", release_block.group(1)))
proguard_files_ok = bool(release_block and "proguardFiles(" in release_block.group(1))
check(
    "release_minify_config",
    minify_ok and proguard_files_ok,
    f"isMinifyEnabled={minify_ok}; proguardFiles={proguard_files_ok}",
)

# 6. Room schemaLocation configured via ksp arg.
schema_arg_ok = bool(re.search(r'arg\(\s*"room\.schemaLocation"', gradle_text))
gitignore = read(GITIGNORE)
schemas_ignored = any(
    line.strip() and not line.strip().startswith("#") and "schemas" in line
    for line in gitignore.splitlines()
)
check(
    "room_schema_location_configured",
    schema_arg_ok and not schemas_ignored,
    f"ksp room.schemaLocation arg={schema_arg_ok}; schemas dir not git-ignored={not schemas_ignored}",
)

# 7. Kotlin / AGP / KSP version combination present in libs.versions.toml.
toml_text = read(VERSIONS_TOML)
agp = re.search(r'^agp\s*=\s*"([^"]+)"', toml_text, re.MULTILINE)
kotlin = re.search(r'^kotlin\s*=\s*"([^"]+)"', toml_text, re.MULTILINE)
ksp = re.search(r'^ksp\s*=\s*"([^"]+)"', toml_text, re.MULTILINE)
check(
    "versions_catalog_agp_kotlin_ksp",
    bool(agp and kotlin and ksp),
    f"agp={agp.group(1) if agp else None}; kotlin={kotlin.group(1) if kotlin else None}; "
    f"ksp={ksp.group(1) if ksp else None}",
)

failed = 0
for name, ok, detail in results:
    status = "PASS" if ok else "FAIL"
    if not ok:
        failed += 1
    print(f"[{status}] {name}: {detail}")

print(f"\n{len(results) - failed}/{len(results)} checks passed.")
sys.exit(1 if failed else 0)
