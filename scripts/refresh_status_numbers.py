#!/usr/bin/env python3
"""状态数字自动生成（ERA 32 §7）：docs/STATUS.md 的 Build Status 段。

- Android 单测数：android/*/build/test-results/testDebugUnitTest/*.xml 实测汇总（按 module）。
- backend pytest：.pytest_report/junit.xml 实测汇总。
- Kotlin/Python 文件数：复用 generate_source_reality 的自动发现（Production/Test/QA 分列）。

只写 docs/STATUS.md 的 `<!-- AUTO:BUILD_STATUS:BEGIN -->` 与 `<!-- AUTO:BUILD_STATUS:END -->`
标记之间；实测产物缺失时如实标注「未执行」，禁止伪造数字。README 不再手写测试计数。
"""
from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_source_reality import (  # noqa: E402
    gradle_modules,
    kotlin_files,
    python_files,
    qa_kotlin_files,
    test_kotlin_files,
)

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
STATUS = ROOT / "docs" / "STATUS.md"
BEGIN = "<!-- AUTO:BUILD_STATUS:BEGIN -->"
END = "<!-- AUTO:BUILD_STATUS:END -->"


def _head_short() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, text=True
        ).strip()
    except Exception:
        return "unknown"


def _declared_test_classes(test_roots: list[Path]) -> set[str]:
    """test 源码中声明的类/object 全限定名集合（按类声明而非文件名匹配，
    兼容一个 .kt 文件声明多个测试类的情况）。"""
    declared: set[str] = set()
    for root in test_roots:
        for f in root.rglob("*.kt"):
            text = f.read_text(encoding="utf-8", errors="ignore")
            pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
            pkg = pkg.group(1) if pkg else ""
            for cm in re.finditer(
                r"^(?:public|internal|private|open|sealed|data|abstract|final|enum|value|annotation)?"
                r"\s*(?:class|object|enum class)\s+([A-Za-z_][\w]*)",
                text, re.M,
            ):
                declared.add(f"{pkg}.{cm.group(1)}")
    return declared


def android_test_counts() -> dict[str, int]:
    """module → test 数（从 testDebugUnitTest 的 JUnit XML 实测汇总）。

    过滤 build 目录中的陈旧 XML：类名必须仍在该 module 的 test 源码中被声明
    （按类声明匹配，兼容一个文件多个测试类），避免测试删除/重命名后旧产物污染数字。
    """
    counts: dict[str, int] = {}
    for m in gradle_modules():
        mod = ANDROID / Path(*m.split(":"))
        test_roots = [
            d for d in (mod / "src" / "test" / "java", mod / "src" / "test" / "kotlin")
            if d.is_dir()
        ]
        results = mod / "build" / "test-results" / "testDebugUnitTest"
        if not results.is_dir():
            continue
        declared = _declared_test_classes(test_roots)
        total = 0
        for xml in results.glob("TEST-*.xml"):
            class_fqcn = xml.name[len("TEST-"):].removesuffix(".xml")
            if class_fqcn not in declared:
                continue  # 陈旧产物：对应测试类已不存在
            try:
                root = ET.parse(xml).getroot()
                total += int(root.attrib.get("tests", 0))
            except Exception:
                continue
        counts[m] = total
    return counts


def backend_pytest() -> tuple[int, int, int] | None:
    """(tests, failures+errors, skipped) 或 None（无实测产物）。

    兼容 pytest junitxml 的 <testsuites><testsuite>… 与单 <testsuite> 两种包裹。
    """
    junit = ROOT / ".pytest_report" / "junit.xml"
    if not junit.exists():
        return None
    try:
        root = ET.parse(junit).getroot()
        suites = root.findall("testsuite") or [root]
        tests = failures = skipped = 0
        for su in suites:
            tests += int(su.attrib.get("tests", 0))
            failures += int(su.attrib.get("failures", 0)) + int(su.attrib.get("errors", 0))
            skipped += int(su.attrib.get("skipped", 0))
        if tests == 0:
            return None
        return tests, failures, skipped
    except Exception:
        return None


def build_block() -> str:
    lines = [BEGIN, ""]
    lines.append(f"> 自动生成（`scripts/refresh_status_numbers.py`，git HEAD `{_head_short()}`，"
                 f"{datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}）；缺失实测产物处如实标注，禁止手写数字。")
    lines.append("")
    lines.append("| 面 | 实测结果 |")
    lines.append("|---|---|")

    counts = android_test_counts()
    if counts:
        total = sum(counts.values())
        per = " / ".join(f"{m} {n}" for m, n in sorted(counts.items()))
        lines.append(f"| Android 单测（testDebugUnitTest） | **{total} 全绿**（{per}） |")
    else:
        lines.append("| Android 单测 | 未执行（无 test-results 产物） |")

    py = backend_pytest()
    if py:
        tests, failures, skipped = py
        passed = tests - skipped - failures
        state = "全绿" if failures == 0 else f"{failures} 失败"
        lines.append(f"| backend pytest | **{passed} passed + {skipped} skipped**（{state}） |")
    else:
        lines.append("| backend pytest | 未执行（无 .pytest_report/junit.xml 产物） |")

    lines.append(f"| Production Kotlin | {len(kotlin_files())} |")
    lines.append(f"| Test Kotlin | {len(test_kotlin_files())} |")
    lines.append(f"| QA Kotlin（:feature:qa，非 Production Runtime） | {len(qa_kotlin_files())} |")
    lines.append(f"| Python | {len(python_files())} |")
    lines.append("")
    lines.append(END)
    return "\n".join(lines)


def main() -> None:
    text = STATUS.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        raise SystemExit(f"{STATUS} 缺少 {BEGIN}/{END} 标记")
    head, _, tail = text.partition(BEGIN)
    _, _, tail = tail.partition(END)
    STATUS.write_text((head + build_block() + tail).rstrip("\n") + "\n", encoding="utf-8")
    print(f"wrote {STATUS.relative_to(ROOT)} Build Status 段")


if __name__ == "__main__":
    main()
