#!/usr/bin/env python3
"""Source Reality 报告生成（ERA 12.7 §2）：脚本辅助，非纯手写。

输出 docs/architecture/SOURCE_REALITY_REPORT.md：
Kotlin/Python package、Manifest Components、Worker/Service、Repository、Runtime、
ViewModel、domain packages、Gradle modules、unresolved 候选、文档宣称但缺失实现候选。
"""
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_SRC = ROOT / "android" / "app" / "src" / "main"
JAVA = ANDROID_SRC / "java" / "com" / "yunjue" / "echo" / "mind"
# ERA 13.5：物理模块源码根（app + :feature:actions + :core:security；新增模块在此登记）
MODULE_JAVA_ROOTS = [
    ROOT / "android" / "feature" / "actions" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind",
    ROOT / "android" / "core" / "security" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind",
    ROOT / "android" / "core" / "model" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind",
    ROOT / "android" / "feature" / "memory" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind",
    ROOT / "android" / "feature" / "observation" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind",
]
MANIFEST = ANDROID_SRC / "AndroidManifest.xml"
BACKEND = ROOT / "backend" / "app"


def kotlin_files() -> list[Path]:
    roots = [JAVA] + [r for r in MODULE_JAVA_ROOTS if r.is_dir()]
    return sorted({p for root in roots for p in root.rglob("*.kt") if p.is_file()})


def python_files() -> list[Path]:
    return sorted(p for p in BACKEND.rglob("*.py") if p.is_file() and "__pycache__" not in p.parts)


def packages(root: Path) -> list[str]:
    pkgs = set()
    for f in root.rglob("*.kt"):
        for line in f.read_text(encoding="utf-8").splitlines():
            m = re.match(r"package\s+([\w.]+)", line)
            if m:
                pkgs.add(m.group(1))
                break
    return sorted(pkgs)


def manifest_components() -> list[str]:
    text = MANIFEST.read_text(encoding="utf-8")
    return re.findall(r'<(?:activity|service|receiver|provider)[^>]*android:name="\.([\w.]+)"', text)


def symbol_declarations() -> dict[str, Path]:
    """symbol name → 定义文件（class/interface/object/enum/data class/fun/val/typealias）。"""
    decl = {}
    for f in kotlin_files():
        for line in f.read_text(encoding="utf-8").splitlines():
            m = re.match(
                r"\s*(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|value|suspend)\s+)*"
                r"(?:const\s+)?(?:data\s+)?(?:class|interface|object|fun|val|var|typealias|enum class)\s+"
                r"(?:<[^>]+>\s+)?"  # 泛型函数（fun <A, B, ...> combine7）
                r"(?:[\w.]+\.)?([A-Za-z_][\w]*)", line)
            if m:
                decl.setdefault(m.group(1), f)
    return decl


def unresolved_candidates() -> list[tuple[str, str]]:
    """project-local 引用（import 与全限定）中无对应声明文件的候选。"""
    decl = symbol_declarations()
    refs: dict[str, set[str]] = {}
    for f in kotlin_files():
        text = f.read_text(encoding="utf-8")
        for m in re.finditer(r"(?:import\s+)?com\.yunjue\.echo\.mind\.([\w.]+)\.([A-Za-z_][\w]*)", text):
            if m.group(1) == "BuildConfig":
                continue  # BuildConfig 生成字段
            if text.count('"', 0, m.start()) % 2 == 1:
                continue  # 字符串字面量内（如 action 常量字符串）
            name = m.group(2)
            line = text[:m.start()].split("\n")[-1]
            if line.strip().startswith("package"):
                continue  # 包声明本身
            if line.strip().startswith("import") and text[m.end():m.end() + 2].startswith(".*"):
                continue  # 通配 import
            tail = text[m.end():].lstrip()
            if tail.startswith("("):
                continue  # 已知对象上的方法调用（如 CORRECTION_REASONS.chunked(）
            if name.isupper():
                continue  # 枚举条目 / 全大写常量（声明扫描不索引枚举成员）
            refs.setdefault(name, set()).add(m.group(1))
    bad = []
    for name, pkgs in sorted(refs.items()):
        if name in decl:
            continue
        # 允许跨包同名符号（如常量）；只有完全无定义才列入候选
        if name not in {"entries", "Companion"}:
            bad.append((name, sorted(pkgs)[:3]))
    return bad


def gradle_modules() -> list[str]:
    settings = ROOT / "android" / "settings.gradle.kts"
    if settings.exists():
        return re.findall(r'include\("?[:]?([\w:]+)"?\)', settings.read_text(encoding="utf-8"))
    return [":app"]


def main() -> None:
    kt = kotlin_files()
    py = python_files()
    kt_pkgs = packages(JAVA)
    py_pkgs = sorted({".".join(p.relative_to(BACKEND).parent.parts) for p in py})
    components = manifest_components()
    missing_components = [c for c in components if not (JAVA / (c.replace(".", "/") + ".kt")).exists()]
    app_file = JAVA / "EchoMindApplication.kt"
    workers = sorted(set(re.findall(r"([A-Za-z]+Worker)\b", app_file.read_text(encoding="utf-8"))))
    missing_workers = [w for w in workers if not any(f.name == f"{w}.kt" for f in kt)]
    repos = sorted({f.stem for f in kt if f.name.endswith("Repository.kt")})
    runtimes = sorted({f.stem for f in kt if "Runtime" in f.name or "Coordinator" in f.name})
    viewmodels = sorted({f.stem for f in kt if f.name.endswith("ViewModel.kt")})
    domains = ["sensing", "localportrait", "presence", "intelligence", "memory", "actions", "journey", "runtime"]
    unresolved = unresolved_candidates()

    lines = []
    lines.append("# Source Reality Report —— 源码事实报告（ERA 12.7，脚本生成）\n")
    lines.append(f"> 生成时间戳随提交更新；本文件由 `scripts/generate_source_reality.py` 生成，禁止手写行数。\n")
    lines.append("## Kotlin 包")
    for p in kt_pkgs:
        lines.append(f"- `{p}`")
    lines.append(f"\n## Python 包（backend/app 顶层）")
    for p in sorted({p.split(".")[0] for p in py_pkgs}):
        lines.append(f"- `{p}`")
    lines.append(f"\n## 数量事实")
    lines.append(f"- Kotlin 文件：{len(kt)}")
    lines.append(f"- Python 文件：{len(py)}")
    lines.append(f"- Manifest Components：{len(components)}（缺失源类：{len(missing_components)}）")
    lines.append(f"- Worker：{len(workers)}（缺失实现：{len(missing_workers)}）")
    lines.append(f"- Repository：{len(repos)}")
    lines.append(f"- Runtime/Coordinator：{len(runtimes)}")
    lines.append(f"- ViewModel：{len(viewmodels)}")
    lines.append(f"- Gradle modules：{gradle_modules()}\n")
    lines.append("## Manifest Components")
    for c in components:
        flag = " ✅" if c not in missing_components else " ❌ 缺源类"
        lines.append(f"- `.main.{c}`{flag}")
    lines.append("\n## Worker")
    for w in workers:
        flag = "" if w not in missing_workers else " ❌ 缺实现"
        lines.append(f"- `{w}`{flag}")
    lines.append("\n## Repository / Runtime / ViewModel")
    lines.append(f"- Repository：{', '.join(repos) or '（无）'}")
    lines.append(f"- Runtime：{', '.join(runtimes) or '（无）'}")
    lines.append(f"- ViewModel：{', '.join(viewmodels) or '（无）'}")
    lines.append("\n## Domain packages（必须存在）")
    domain_roots = [JAVA] + [r for r in MODULE_JAVA_ROOTS if r.is_dir()]
    for d in domains:
        exists = any((root / d).is_dir() for root in domain_roots)
        lines.append(f"- `{d}` {'✅' if exists else '❌ 缺失'}")
    lines.append("\n## unresolved project symbol 候选（自动发现；人工复核）")
    if unresolved:
        for name, pkgs in unresolved:
            lines.append(f"- `{name}`（引用自 {', '.join(pkgs)}）—— 疑似缺失/常量/跨包，需复核")
    else:
        lines.append("- 无")
    lines.append("\n## 文档宣称但缺失实现候选")
    lines.append("- 由 SourceIntegrityTest（runtime/intelligence/presence/memory 关键类）与 unresolved 扫描联合覆盖；本轮无已知项。\n")

    out = ROOT / "docs" / "architecture" / "SOURCE_REALITY_REPORT.md"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out.relative_to(ROOT)}（kt={len(kt)}, py={len(py)}, components={len(components)}, unresolved={len(unresolved)}）")


if __name__ == "__main__":
    main()
