#!/usr/bin/env python3
"""真实 Android 依赖图生成（ERA 13.2 §48）。

分析 android/app/src 的 package 声明与 `com.yunjue.echo.mind.*` import，
输出包级依赖图 + 跨领域边 + 循环检测到 docs/architecture/ANDROID_DEPENDENCY_GRAPH.md。

用法：
  python3 scripts/generate_dependency_graph.py
CI：source-integrity drift gate（git diff --exit-code）强制与源码同步。
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# ERA 13.5：app + 物理模块源码根（新增模块在此登记）
SRC_ROOTS = [
    ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind",
    ROOT / "android" / "feature" / "actions" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind",
    ROOT / "android" / "core" / "security" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind",
    ROOT / "android" / "core" / "model" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind",
]

#: 领域聚合映射：子包 → 顶层领域（依赖图按领域展示）。
DOMAIN_OF = {
    "sensing": "observation", "localportrait": "observation", "model": "observation",  # Ground Truth 含其模型
    "presence": "presence", "intelligence": "intelligence", "memory": "memory",
    "actions": "actions", "journey": "journey", "me": "me",
    "data": "data", "security": "security", "runtime": "runtime",
    "di": "di", "ports": "ports", "ui": "ui",
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
        return "root"  # 根级顶层函数/类（AppPreferences/openDatabase 等）
    return "ui" if parts[0] == "ui" else parts[0]


def main() -> int:
    edges: dict[str, set[str]] = defaultdict(set)
    files_by_domain: dict[str, int] = defaultdict(int)

    all_files = sorted({f for root in SRC_ROOTS if root.is_dir() for f in root.rglob("*.kt")})
    for f in all_files:
        text = f.read_text(encoding="utf-8", errors="replace")
        m = re.search(r"^package\s+com\.yunjue\.echo\.mind\.([\w.]+)", text, re.MULTILINE)
        if not m:
            continue
        src_domain = domain_of(m.group(1))
        files_by_domain[src_domain] += 1
        for imp in re.findall(r"import\s+com\.yunjue\.echo\.mind\.([\w.]+)", text):
            dst = domain_of(imp)
            if dst != src_domain:
                edges[src_domain].add(dst)

    # 循环检测（DFS；ports 是契约层——引用领域词表（EchoMemory 等）不构成实现循环，跳过）
    def has_cycle() -> list:
        visiting, done = set(), set()
        cycle = []

        def dfs(node, path):
            if node in visiting:
                cycle.extend(path[path.index(node):] + [node])
                return True
            if node in done:
                return False
            visiting.add(node)
            for nxt in sorted(edges.get(node, ())):
                if nxt == "ports":
                    continue
                if dfs(nxt, path + [node]):
                    return True
            visiting.remove(node)
            done.add(node)
            return False

        for node in sorted(edges):
            if node == "ports":
                continue
            if dfs(node, []):
                return cycle
        return []

    lines = []
    lines.append("# Android Dependency Graph —— 真实依赖图（ERA 13.2 §48，脚本生成）\n")
    lines.append("> 状态：CURRENT · 本文件由 `scripts/generate_dependency_graph.py` 生成，禁止手写。\n")
    lines.append("> 每轮结构变更后重新生成；CI source-integrity drift gate 强制同步。\n")
    lines.append("## 1. 领域包图（com.yunjue.echo.mind.*，顶层领域聚合）\n")
    lines.append("```text")
    for src in sorted(edges):
        lines.append(f"{src} ──► {', '.join(sorted(edges[src]))}")
    lines.append("```\n")
    lines.append("## 2. 领域文件数（实测）\n")
    lines.append("| 领域 | Kotlin 文件数 |")
    lines.append("|---|---|")
    for d in sorted(files_by_domain):
        lines.append(f"| {d} | {files_by_domain[d]} |")
    lines.append("\n## 3. 跨领域边清单\n")
    for src in sorted(edges):
        for dst in sorted(edges[src]):
            lines.append(f"- {src} → {dst}")
    lines.append("\n## 4. 循环\n")
    cycle = has_cycle()
    if cycle:
        lines.append(f"- ⚠️ 检测到循环：{' → '.join(cycle)}")
    else:
        lines.append("- 无已知包级循环。")
    lines.append("\n## 5. 边界规则（ArchitectureBoundaryTest + CI 强制）\n")
    lines.append("- observation → intelligence 禁止（Ground Truth 独立）")
    lines.append("- presence 渲染层 → Room 禁止")
    lines.append("- memory → concrete Provider 禁止")
    lines.append("- intelligence → app UI / data 实现禁止（ERA 13.2 §37：只依赖 ports）")
    lines.append("- journey/me 应用层 → ui 禁止\n")

    out = ROOT / "docs" / "architecture" / "ANDROID_DEPENDENCY_GRAPH.md"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out.relative_to(ROOT)}（{len(edges)} domains, {sum(len(v) for v in edges.values())} edges, cycle={bool(cycle)}）")
    return 1 if cycle else 0


if __name__ == "__main__":
    raise SystemExit(main())
