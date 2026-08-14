#!/usr/bin/env python3
"""本地依赖安全审计（ERA 18 收尾：与 security-ci 同构的本地复跑路径）。

审计面：
  1. backend（锁定依赖）：uv export（backend/uv.lock → requirements-txt）
     → pip-audit（OSV 漏洞库；uvx 按需运行，无需全局安装）；
  2. 全仓（Gradle lockfiles + Python 依赖）：osv-scanner（若本地已安装；
     未安装 → 如实标记 NOT RUN，CI security-ci 强制执行）。

退出码：
  0 = 全部通过（或 osv-scanner 未安装的如实豁免）；
  1 = 发现漏洞（pip-audit/osv-scanner 命中）；
  2 = 审计执行错误（工具缺失/网络失败——如实报告，不静默通过）。
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(cmd: list[str], stdin: str | None = None, timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, input=stdin, capture_output=True, text=True, timeout=timeout, cwd=ROOT)


def audit_backend() -> dict:
    """uv.lock → requirements-txt → pip-audit（OSV）。"""
    if shutil.which("uv") is None:
        return {"status": "error", "detail": "uv 不可用（backend 锁定审计需要 uv export）"}
    reqs = ROOT / "backend" / ".audit-requirements.txt"
    try:
        export = run(["uv", "export", "--project", "backend", "--extra", "dev", "--format", "requirements-txt", "-o", str(reqs)])
        if export.returncode != 0:
            return {"status": "error", "detail": f"uv export 失败：{export.stderr.strip()[:200]}"}
        # uv export 首行含 `-e .`（项目自引用）——pip-audit 只审计第三方依赖，剥离该行
        lines = [ln for ln in reqs.read_text(encoding="utf-8").splitlines() if not ln.startswith("-e ")]
        reqs.write_text("\n".join(lines) + "\n", encoding="utf-8")
        audit = run(["uvx", "pip-audit", "-r", str(reqs)])
        return {
            "status": "ok" if audit.returncode == 0 else "vulnerable",
            "pip_audit_rc": audit.returncode,
            "summary": (audit.stdout.strip() or audit.stderr.strip()).splitlines()[-1] if audit.stdout.strip() or audit.stderr.strip() else "",
        }
    finally:
        reqs.unlink(missing_ok=True)


def audit_repo() -> dict:
    """全仓 osv-scanner（本地可选；CI 强制）。"""
    binary = shutil.which("osv-scanner")
    if binary is None:
        return {
            "status": "not_run",
            "detail": "osv-scanner 未安装（本地豁免；security-ci 强制执行）。安装：go install github.com/google/osv-scanner/v2/cmd/osv-scanner@latest 或下载官方 release",
        }
    result = run([binary, "--recursive", "."], timeout=600)
    return {
        "status": "ok" if result.returncode == 0 else "vulnerable",
        "osv_rc": result.returncode,
        "summary": (result.stdout.strip() or result.stderr.strip()).splitlines()[-1] if result.stdout.strip() or result.stderr.strip() else "",
    }


def main() -> int:
    backend = audit_backend()
    repo = audit_repo()
    report = {"backend_pip_audit": backend, "osv_scanner": repo}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if backend["status"] == "error" or repo["status"] == "error":
        return 2
    if backend["status"] == "vulnerable" or repo["status"] == "vulnerable":
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
