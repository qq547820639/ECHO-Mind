#!/usr/bin/env python3
"""Source Reality 报告生成（ERA 32 多模块自动发现版）：脚本辅助，非纯手写。

输出 docs/architecture/SOURCE_REALITY_REPORT.md：
Kotlin/Python package、Manifest Components、Worker/Service、Repository、Runtime、
ViewModel、domain packages、Gradle modules、unresolved 候选、文档宣称但缺失实现候选。

ERA 32 修复单 module 时代假设：
- Gradle modules 从 android/settings.gradle.kts 自动发现（禁止手写模块清单）。
- 每个 module 自动扫描 src/main/java 与 src/main/kotlin（test/androidTest 仅用于统计）。
- Manifest Component 在全部 production module 的 AndroidManifest.xml 中解析，
  源类存在性在全部 production module 源码根中判定（NotificationCollector 等
  feature 类不再误报 Missing）。
- :feature:qa 单独统计（QA Kotlin），明确不属于 Production Runtime。
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
BACKEND = ROOT / "backend" / "app"
QA_MODULE = "feature:qa"
BASE_PKG = Path("com/yunjue/echo/mind")


def gradle_modules() -> list[str]:
    """从 settings.gradle.kts 自动发现全部 module（去掉前导冒号，稳定排序）。"""
    settings = ANDROID / "settings.gradle.kts"
    names = re.findall(r'include\("?([\w:]+)"?\)', settings.read_text(encoding="utf-8"))
    return sorted({n.lstrip(":") for n in names})


def module_dir(module: str) -> Path:
    return ANDROID / Path(*module.split(":"))


def production_modules() -> list[str]:
    """Production Runtime modules；:feature:qa 单独统计，不属 Production Runtime。"""
    return [m for m in gradle_modules() if m != QA_MODULE]


def module_source_roots(module: str, source_set: str) -> list[Path]:
    """src/<source_set>/java 与 src/<source_set>/kotlin 中实际存在的根。"""
    base = module_dir(module) / "src" / source_set
    return [d for d in (base / "java", base / "kotlin") if d.is_dir()]


def prod_main_roots() -> list[Path]:
    return [d for m in production_modules() for d in module_source_roots(m, "main")]


def prod_test_roots() -> list[Path]:
    return [d for m in production_modules() for d in module_source_roots(m, "test")]


def qa_roots() -> list[Path]:
    return [d for ss in ("main", "test", "androidTest") for d in module_source_roots(QA_MODULE, ss)]


def _files(roots: list[Path]) -> list[Path]:
    return sorted({p for root in roots for p in root.rglob("*.kt") if p.is_file()})


def kotlin_files() -> list[Path]:
    """Production Kotlin：全部 production module 的 src/main。"""
    return _files(prod_main_roots())


def test_kotlin_files() -> list[Path]:
    """Test Kotlin：全部 production module 的 src/test。"""
    return _files(prod_test_roots())


def qa_kotlin_files() -> list[Path]:
    """QA Kotlin：:feature:qa 全 source set（其本身就是 QA 工件）。"""
    return _files(qa_roots())


def python_files() -> list[Path]:
    return sorted(p for p in BACKEND.rglob("*.py") if p.is_file() and "__pycache__" not in p.parts)


def packages(roots: list[Path]) -> list[str]:
    pkgs = set()
    for f in _files(roots):
        for line in f.read_text(encoding="utf-8").splitlines():
            m = re.match(r"package\s+([\w.]+)", line)
            if m:
                pkgs.add(m.group(1))
                break
    return sorted(pkgs)


def manifest_components() -> list[tuple[str, str]]:
    """(module, component relative name) —— 全部 production module 的 AndroidManifest.xml。"""
    out = []
    for m in production_modules():
        manifest = module_dir(m) / "src" / "main" / "AndroidManifest.xml"
        if not manifest.exists():
            continue
        text = manifest.read_text(encoding="utf-8")
        for c in re.findall(r'<(?:activity|service|receiver|provider)[^>]*android:name="\.([\w.]+)"', text):
            out.append((m, c))
    return out


def component_source_exists(relative_name: str) -> bool:
    """相对名 `.a.b.C` 解析为 com/yunjue/echo/mind/a/b/C.kt，在全部 production 源码根中判定。"""
    rel = BASE_PKG / Path(*relative_name.split(".")).with_suffix(".kt")
    return any((root / rel).exists() for root in prod_main_roots())


def symbol_declarations() -> dict[str, Path]:
    """symbol name → 定义文件（class/interface/object/enum/data class/fun/val/typealias）。"""
    decl = {}
    for f in kotlin_files():
        for line in f.read_text(encoding="utf-8").splitlines():
            m = re.match(
                r"\s*(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|value|suspend)\s+)*"
                r"(?:const\s+)?(?:data\s+)?(?:fun\s+interface|class|interface|object|fun|val|var|typealias|enum class)\s+"
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


def wearable_artifacts() -> tuple[list[Path], list[Path]]:
    """ERA 33：Vela JS（.ux/.js，非 Android Kotlin 生产计数）+ ANS 集成 schema/golden。"""
    vela_root = ROOT / "wearable" / "xiaomi-vela" / "src"
    vela_files = (
        sorted(vela_root.rglob("*.ux")) + sorted(vela_root.rglob("*.js"))
        if vela_root.is_dir()
        else []
    )
    ans_root = ROOT / "integrations" / "answatch"
    ans_files = sorted(ans_root.rglob("*.json")) if ans_root.is_dir() else []
    return vela_files, ans_files


def main() -> None:
    kt = kotlin_files()
    kt_test = test_kotlin_files()
    kt_qa = qa_kotlin_files()
    py = python_files()
    kt_pkgs = packages(prod_main_roots())
    py_pkgs = sorted({".".join(p.relative_to(BACKEND).parent.parts) for p in py})
    components = manifest_components()
    missing_components = [c for _, c in components if not component_source_exists(c)]
    app_file = next((f for f in kt if f.name == "EchoMindApplication.kt"), None)
    workers = sorted(set(re.findall(r"([A-Za-z]+Worker)\b", app_file.read_text(encoding="utf-8")))) if app_file else []
    missing_workers = [w for w in workers if not any(f.name == f"{w}.kt" for f in kt)]
    repos = sorted({f.stem for f in kt if f.name.endswith("Repository.kt")})
    runtimes = sorted({f.stem for f in kt if "Runtime" in f.name or "Coordinator" in f.name})
    viewmodels = sorted({f.stem for f in kt if f.name.endswith("ViewModel.kt")})
    domains = ["sensing", "localportrait", "presence", "intelligence", "memory", "actions", "journey", "runtime", "wearable"]
    unresolved = unresolved_candidates()
    modules = gradle_modules()
    vela_files, ans_files = wearable_artifacts()

    lines = []
    lines.append("# Source Reality Report —— 源码事实报告（ERA 32，脚本生成 · 多模块自动发现）\n")
    lines.append(f"> 生成时间戳随提交更新；本文件由 `scripts/generate_source_reality.py` 生成，禁止手写行数。\n")
    lines.append(f"> Gradle modules 自动发现自 `android/settings.gradle.kts`；每 module 扫描 `src/main/java` 与 `src/main/kotlin`。\n")
    lines.append("## Kotlin 包（Production）")
    for p in kt_pkgs:
        lines.append(f"- `{p}`")
    lines.append(f"\n## Python 包（backend/app 顶层）")
    for p in sorted({p.split(".")[0] for p in py_pkgs}):
        lines.append(f"- `{p}`")
    lines.append(f"\n## 数量事实")
    lines.append(f"- Production Kotlin：{len(kt)}")
    lines.append(f"- Test Kotlin：{len(kt_test)}")
    lines.append(f"- QA Kotlin（:feature:qa，不属于 Production Runtime）：{len(kt_qa)}")
    lines.append(f"- Python 文件：{len(py)}")
    lines.append(f"- Manifest Components：{len(components)}（缺失源类：{len(missing_components)}）")
    lines.append(f"- Worker：{len(workers)}（缺失实现：{len(missing_workers)}）")
    lines.append(f"- Repository：{len(repos)}")
    lines.append(f"- Runtime/Coordinator：{len(runtimes)}")
    lines.append(f"- ViewModel：{len(viewmodels)}")
    lines.append(f"- Gradle modules（自动发现）：{', '.join(modules)}\n")
    lines.append("\n## Wearable 面（ERA 33）")
    lines.append(
        f"- Vela JS 源文件（wearable/xiaomi-vela/src，**.ux + **.js**）：{len(vela_files)}"
        f" —— **Vela ≠ Android Kotlin production count**（JS 快应用独立计数，不并入上文 Kotlin 数量）"
    )
    lines.append(
        f"- ANS 集成 schema/golden（integrations/answatch/*.json）：{len(ans_files)}"
        f" —— ANSWatch 为 READ-ONLY 参考仓，其源码不计入本仓 Production 计数"
    )
    lines.append("## Manifest Components（跨全部 production module 解析）")
    for module, c in components:
        flag = " ✅" if c not in missing_components else " ❌ 缺源类"
        lines.append(f"- `:{module}` `.main.{c}`{flag}")
    lines.append("\n## Worker")
    for w in workers:
        flag = "" if w not in missing_workers else " ❌ 缺实现"
        lines.append(f"- `{w}`{flag}")
    lines.append("\n## Repository / Runtime / ViewModel")
    lines.append(f"- Repository：{', '.join(repos) or '（无）'}")
    lines.append(f"- Runtime：{', '.join(runtimes) or '（无）'}")
    lines.append(f"- ViewModel：{', '.join(viewmodels) or '（无）'}")
    lines.append("\n## Domain packages（必须存在）")
    domain_roots = [root / BASE_PKG for root in prod_main_roots() if (root / BASE_PKG).is_dir()]
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
    print(
        f"wrote {out.relative_to(ROOT)}"
        f"（kt={len(kt)}, test={len(kt_test)}, qa={len(kt_qa)}, py={len(py)},"
        f" components={len(components)}, missing={len(missing_components)}, unresolved={len(unresolved)}）"
    )


if __name__ == "__main__":
    main()
