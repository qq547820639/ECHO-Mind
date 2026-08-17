#!/usr/bin/env python3
"""真实 Android 依赖图生成（ERA 13.2 §48；V3 Simplification §E 重写为双图）。

生成两张图，均含循环检测：

1. Gradle Module Dependency Graph
   从 android/settings.gradle.kts（include 清单）+ 各模块 build.gradle.kts
   （project(":x:y") 依赖）自动解析。模块图必须无环。

2. Package Domain Dependency Graph
   扫描生产 main 源码（settings 自动发现）的 package 声明与
   com.yunjue.echo.mind.* import，按显式领域清单聚合。
   领域图必须无环，且满足 BOUNDARY_RULES 边界规则。

用法：
  python3 scripts/generate_dependency_graph.py
CI：source-integrity drift gate（git diff --exit-code）强制与源码同步；
    检测到环或边界违规时本脚本以退出码 1 失败。
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# 模块源码根复用 generate_source_reality 的自动发现（settings.gradle.kts → src/main/java|kotlin），
# 单一事实源，禁止手写模块清单。
from generate_source_reality import ANDROID, gradle_modules  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
QA_MODULE = "feature:qa"

#: 领域聚合映射（V3 §E 显式清单）：子包前缀 → 领域。
#: 显式识别：actions / memory / observation / presence / presencevisual / intelligence /
#: journey / wearable / security / model / ports / visual / app 内的 ui|data|runtime|di。
DOMAIN_OF = {
    "sensing": "observation", "localportrait": "observation",
    "presence": "presence", "presencevisual": "presencevisual",
    "intelligence": "intelligence", "affective": "intelligence",
    "memory": "memory", "actions": "actions", "journey": "journey",
    "wearable": "wearable", "security": "security",
    "model": "model", "ports": "ports", "visual": "visual",
    "ui": "ui", "me": "ui",
    "data": "data", "runtime": "runtime", "di": "di",
}

#: 领域边界规则（源域 → 禁止目标域；ArchitectureBoundaryTest 同步强制）。
BOUNDARY_RULES: dict[str, set[str]] = {
    "observation": {"intelligence", "data", "ui", "root", "di", "runtime"},
    "presence": {"ui", "data", "root", "di"},
    "memory": {"intelligence", "ui", "data", "root", "di"},
    "intelligence": {"ui", "data", "root", "di"},
    # journey 领域含 :app 应用层仓库（合法使用 data/root）；仅禁 ui（ArchitectureBoundaryTest 同步）
    "journey": {"ui"},
    "actions": {"data", "ui", "root", "di", "intelligence", "memory",
                "presence", "observation", "journey", "visual", "presencevisual"},
    # core:visual 是纯确定性视觉数学：不得 import app/data/observation/presence 实现
    "visual": {"ui", "data", "root", "di", "runtime", "observation", "presence",
               "intelligence", "memory", "journey", "presencevisual"},
    # presencevisual 是 Android 渲染 adapter：不得 import DB/provider/app 实现
    "presencevisual": {"data", "ui", "root", "di", "intelligence", "memory",
                       "observation", "journey"},
    # wearable 只依赖 core 边界（model/ports/visual/security）
    "wearable": {"data", "ui", "root", "di", "intelligence", "actions",
                 "memory", "journey", "presence", "presencevisual"},
}


def domain_of(pkg: str) -> str:
    parts = pkg.split(".")
    for i in range(len(parts), 0, -1):
        key = ".".join(parts[:i])
        if key in DOMAIN_OF:
            return DOMAIN_OF[key]
    if not parts:
        return "root"
    if len(parts) == 1:
        return "root"  # 根级顶层类（AppPreferences/AppContainer/EchoMindApplication 等）
    return parts[0]


def find_cycle(edges: dict[str, set[str]]) -> list[str]:
    """DFS 环检测；返回环路径（空列表 = 无环）。"""
    visiting: set[str] = set()
    done: set[str] = set()

    def dfs(node: str, path: list[str]) -> list[str]:
        if node in visiting:
            return path[path.index(node):] + [node]
        if node in done:
            return []
        visiting.add(node)
        for nxt in sorted(edges.get(node, ())):
            found = dfs(nxt, path + [node])
            if found:
                return found
        visiting.remove(node)
        done.add(node)
        return []

    for node in sorted(edges):
        found = dfs(node, [])
        if found:
            return found
    return []


def gradle_module_graph() -> tuple[dict[str, set[str]], list[str]]:
    """从 settings.gradle.kts + 各模块 build.gradle.kts 解析模块依赖图。"""
    modules = gradle_modules()
    known = set(modules)
    edges: dict[str, set[str]] = defaultdict(set)
    for module in modules:
        build_file = ANDROID.joinpath(*module.split(":")) / "build.gradle.kts"
        if not build_file.is_file():
            continue
        text = build_file.read_text(encoding="utf-8", errors="replace")
        for dep in re.findall(r'project\("(:[\w:]+)"\)', text):
            dep_name = dep.lstrip(":")
            if dep_name in known and dep_name != module:
                edges[module].add(dep_name)
    return edges, modules


def package_domain_graph() -> tuple[dict[str, set[str]], dict[str, int], dict[str, set[str]]]:
    """扫描生产 main 源码 → 领域边 / 文件数 / 具体跨域 import 证据。"""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from generate_source_reality import prod_main_roots  # noqa: PLC0415
    edges: dict[str, set[str]] = defaultdict(set)
    files_by_domain: dict[str, int] = defaultdict(int)
    evidence: dict[str, set[str]] = defaultdict(set)
    all_files = sorted({f for root in prod_main_roots() if root.is_dir() for f in root.rglob("*.kt")})
    for f in all_files:
        text = f.read_text(encoding="utf-8", errors="replace")
        m = re.search(r"^package\s+com\.yunjue\.echo\.mind(?:\.([\w.]+))?", text, re.MULTILINE)
        if not m:
            continue
        src_domain = domain_of(m.group(1) or "")
        files_by_domain[src_domain] += 1
        for imp in re.findall(r"import\s+com\.yunjue\.echo\.mind(?:\.([\w.]+))?", text):
            dst = domain_of(imp or "")
            if dst != src_domain:
                edges[src_domain].add(dst)
                evidence[f"{src_domain}→{dst}"].add(imp)
    return edges, files_by_domain, evidence


def main() -> int:
    module_edges, modules = gradle_module_graph()
    domain_edges, files_by_domain, evidence = package_domain_graph()

    module_cycle = find_cycle(module_edges)
    domain_cycle = find_cycle(domain_edges)
    violations: list[str] = []
    for src in sorted(domain_edges):
        for dst in sorted(domain_edges[src]):
            if dst in BOUNDARY_RULES.get(src, set()):
                imports = sorted(evidence.get(f"{src}→{dst}", set()))[:4]
                violations.append(f"{src} → {dst}（imports: {', '.join(imports)}）")

    lines: list[str] = []
    lines.append("# Android Dependency Graph —— 真实依赖图（ERA 13.2 §48；V3 §E 双图，脚本生成）\n")
    lines.append("> 状态：CURRENT · 本文件由 `scripts/generate_dependency_graph.py` 生成，禁止手写。\n")
    lines.append("> 每轮结构变更后重新生成；CI source-integrity drift gate 强制同步。\n")

    lines.append("## 1. Gradle Module Dependency Graph（settings.gradle.kts + build.gradle.kts 自动解析）\n")
    lines.append("```text")
    for module in sorted(modules):
        deps = sorted(module_edges.get(module, set()))
        tag = "（QA，不入生产图）" if module == QA_MODULE else ""
        lines.append(f"{module}{tag} ──► {', '.join(deps) if deps else '（无项目依赖）'}")
    lines.append("```\n")
    lines.append(f"模块图循环：{'⚠️ ' + ' → '.join(module_cycle) if module_cycle else '无（必须无环）'}\n")

    lines.append("## 2. Package Domain Dependency Graph（com.yunjue.echo.mind.*，显式领域聚合）\n")
    lines.append("```text")
    for src in sorted(domain_edges):
        lines.append(f"{src} ──► {', '.join(sorted(domain_edges[src]))}")
    lines.append("```\n")

    lines.append("## 3. 领域文件数（实测）\n")
    lines.append("| 领域 | Kotlin 文件数 |")
    lines.append("|---|---|")
    for d in sorted(files_by_domain):
        lines.append(f"| {d} | {files_by_domain[d]} |")

    lines.append("\n## 4. 跨领域边清单\n")
    for src in sorted(domain_edges):
        for dst in sorted(domain_edges[src]):
            lines.append(f"- {src} → {dst}")

    lines.append("\n## 5. 循环\n")
    lines.append(f"- 模块图：{'⚠️ 检测到循环：' + ' → '.join(module_cycle) if module_cycle else '无。'}")
    lines.append(f"- 领域图：{'⚠️ 检测到循环：' + ' → '.join(domain_cycle) if domain_cycle else '无。'}")

    lines.append("\n## 6. 边界规则（ArchitectureBoundaryTest + 本脚本双重强制）\n")
    lines.append("- Gradle 模块图必须无环。")
    lines.append("- 领域图必须无环，并满足：")
    for src, forbidden in BOUNDARY_RULES.items():
        lines.append(f"  - {src} 不得依赖 {sorted(forbidden)}")
    if violations:
        lines.append("\n⚠️ 边界违规（阻断）：")
        for v in violations:
            lines.append(f"- {v}")
    else:
        lines.append("\n边界违规：无。\n")

    out = ROOT / "docs" / "architecture" / "ANDROID_DEPENDENCY_GRAPH.md"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(
        f"wrote {out.relative_to(ROOT)}"
        f"（modules={len(modules)}, domains={len(files_by_domain)}, "
        f"edges={sum(len(v) for v in domain_edges.values())}, "
        f"module_cycle={bool(module_cycle)}, domain_cycle={bool(domain_cycle)}, "
        f"violations={len(violations)}）"
    )
    failed = bool(module_cycle) or bool(domain_cycle) or bool(violations)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
