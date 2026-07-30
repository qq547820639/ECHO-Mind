#!/usr/bin/env python3
"""PostgreSQL 备份恢复演练工具（对应 spec Task 6）。

演练流程：
  1. pg_dump 备份源库（custom 格式，或使用 --backup-file 指定的既有备份）
  2. 备份文件校验：存在性、非空、pg_restore --list 可解析
  3. 恢复到隔离的临时数据库（同一实例上新建 echo_drill_* 库，演练结束即删）
  4. 校验关键表行数与源一致（风险队列 escalations / risk_signals 优先校验）
  5. 输出结构化报告：JSON 报告文件 + 终端摘要，
     对照 RTO ≤ 4 小时、RPO ≤ 24 小时给出达标结论。

仅依赖 Python 标准库与 PostgreSQL 客户端工具（pg_dump / pg_restore / psql），
便于在任意环境（开发机 / CI / 运维跳板机）直接运行。
无真实 PostgreSQL 或缺少客户端工具时会优雅报错并返回退出码 3。

退出码约定：0 = 演练通过；2 = 演练执行但存在未达标项；3 = 环境/参数错误。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

# 关键表清单：顺序即校验顺序，风险队列（escalations / risk_signals）固定最前，
# 与 backend/app/models.py 的 __tablename__ 保持一致。
PRIORITY_TABLES = ["escalations", "risk_signals"]
OTHER_TABLES = [
    "tenants",
    "users",
    "consents",
    "onboarding_screenings",
    "emergency_contacts",
    "checkins",
    "journal_entries",
    "questionnaire_results",
    "practice_completions",
    "data_subject_requests",
    "audit_events",
]

DEFAULT_RTO_HOURS = 4.0   # 恢复时间目标（spec：RTO ≤ 4 小时）
DEFAULT_RPO_HOURS = 24.0  # 恢复点目标（spec：RPO ≤ 24 小时）
COMMAND_TIMEOUT_SECONDS = 3600  # 单条 pg 命令的超时保护


class DrillError(Exception):
    """环境或参数类错误，主流程捕获后优雅报错并返回退出码 3。"""


def log(message: str) -> None:
    """带时间戳的进度输出（stderr，避免污染 stdout 的 --json 输出）。"""
    now = datetime.now(timezone.utc).strftime("%H:%M:%S")
    print(f"[{now}Z] {message}", file=sys.stderr)


def parse_dsn(dsn: str) -> dict:
    """解析 PostgreSQL DSN。

    兼容 SQLAlchemy 写法（如 postgresql+psycopg://，见 docker-compose.yml），
    返回连接要素字典；口令绝不进入命令行参数，只通过 PGPASSWORD 传递。
    """
    normalized = dsn.strip()
    # 去掉 SQLAlchemy 驱动后缀，例如 postgresql+psycopg:// -> postgresql://
    if "+psycopg" in normalized.split("://", 1)[0] or "+pg8000" in normalized.split("://", 1)[0]:
        normalized = "postgresql://" + normalized.split("://", 1)[1]
    parts = urlsplit(normalized)
    if parts.scheme not in {"postgresql", "postgres"}:
        raise DrillError(f"无法识别的 DSN 协议：{parts.scheme or '(缺失)'}，应为 postgresql:// 或 postgres://")
    if not parts.hostname:
        raise DrillError("DSN 缺少主机名，示例：postgresql://user:pass@host:5432/dbname")
    database = parts.path.lstrip("/")
    if not database:
        raise DrillError("DSN 缺少数据库名，示例：postgresql://user:pass@host:5432/dbname")
    return {
        "host": parts.hostname,
        "port": parts.port or 5432,
        "user": parts.username or "postgres",
        "password": parts.password or "",
        "database": database,
    }


def masked_dsn(source: dict) -> str:
    """生成脱敏 DSN 用于报告展示（不出口令）。"""
    return f"postgresql://{source['user']}@{source['host']}:{source['port']}/{source['database']}"


def require_tools() -> None:
    """检查 pg_dump / pg_restore / psql 是否可用，缺失时给出安装提示。"""
    missing = [tool for tool in ("pg_dump", "pg_restore", "psql") if shutil.which(tool) is None]
    if missing:
        raise DrillError(
            "缺少 PostgreSQL 客户端工具："
            + "、".join(missing)
            + "。请安装 postgresql-client（Debian/Ubuntu: apt-get install postgresql-client；"
            "Alpine: apk add postgresql-client；macOS: brew install libpq）后重试。"
        )


def pg_env(source: dict) -> dict:
    """构造子进程环境：口令走 PGPASSWORD，避免出现在进程参数列表中。"""
    env = dict(os.environ)
    env["PGPASSWORD"] = source["password"]
    env.setdefault("PGCONNECT_TIMEOUT", "10")  # 连接不可用时快速失败
    return env


def conn_args(source: dict, database: str | None = None) -> list[str]:
    """生成 pg 客户端公共连接参数（不含口令）。"""
    args = ["-h", source["host"], "-p", str(source["port"]), "-U", source["user"]]
    if database:
        args += ["-d", database]
    return args


def run_command(cmd: list[str], env: dict) -> subprocess.CompletedProcess:
    """执行外部命令并捕获输出；超时或非零退出抛 DrillError，stderr 原样带上。"""
    try:
        proc = subprocess.run(
            cmd,
            env=env,
            capture_output=True,
            text=True,
            timeout=COMMAND_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        raise DrillError(f"命令执行超时（>{COMMAND_TIMEOUT_SECONDS}s）：{cmd[0]}")
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()
        raise DrillError(f"命令执行失败：{cmd[0]}（退出码 {proc.returncode}）\n{detail}")
    return proc


def sha256_of(path: Path) -> str:
    """计算备份文件 SHA-256，用于报告留痕与事后比对。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stage_backup(source: dict, env: dict, work_dir: Path, backup_file: Path | None) -> dict:
    """环节 1：pg_dump 备份。若 --backup-file 指定既有备份则跳过导出直接使用。"""
    started = time.monotonic()
    if backup_file is not None:
        # 使用既有备份文件：新鲜度按文件修改时间计算，用于真实 RPO 核查
        if not backup_file.exists():
            raise DrillError(f"指定的备份文件不存在：{backup_file}")
        log(f"使用既有备份文件：{backup_file}")
        finished_at = datetime.fromtimestamp(backup_file.stat().st_mtime, tz=timezone.utc)
        return {
            "ok": True,
            "seconds": round(time.monotonic() - started, 3),
            "file": str(backup_file),
            "reused_existing": True,
            "finished_at": finished_at.isoformat(),
        }
    target = work_dir / f"pg-backup-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.dump"
    log(f"开始 pg_dump 备份 -> {target}")
    # 注意必须显式传源库名，否则 pg_dump 会默认连接与用户名同名的库
    run_command(["pg_dump", "--format=custom", f"--file={target}", *conn_args(source, source["database"])], env)
    return {
        "ok": True,
        "seconds": round(time.monotonic() - started, 3),
        "file": str(target),
        "reused_existing": False,
        "finished_at": datetime.now(timezone.utc).isoformat(),
    }


def stage_validate(backup_path: Path) -> dict:
    """环节 2：备份文件校验——存在、非空、pg_restore --list 可解析。"""
    started = time.monotonic()
    checks = {
        "exists": backup_path.exists(),
        "non_empty": backup_path.exists() and backup_path.stat().st_size > 0,
    }
    if not checks["exists"]:
        raise DrillError(f"备份文件不存在：{backup_path}")
    checks["sha256"] = sha256_of(backup_path)
    checks["bytes"] = backup_path.stat().st_size
    # pg_restore --list 解析归档目录，能列出条目即说明 custom 格式完整可读
    proc = run_command(["pg_restore", "--list", str(backup_path)], dict(os.environ))
    toc_entries = [line for line in proc.stdout.splitlines() if line.strip() and not line.startswith(";")]
    checks["pg_restore_list_parseable"] = len(toc_entries) > 0
    checks["toc_entries"] = len(toc_entries)
    ok = all(v for k, v in checks.items() if k in {"exists", "non_empty", "pg_restore_list_parseable"})
    return {"ok": ok, "seconds": round(time.monotonic() - started, 3), "checks": checks}


def psql_scalar(env: dict, source: dict, database: str, sql: str) -> str:
    """在指定库上执行只读 SQL 并返回标量结果（-t -A 静默输出）。"""
    proc = run_command(
        ["psql", "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1", *conn_args(source, database), "-c", sql],
        env,
    )
    return proc.stdout.strip().splitlines()[-1].strip() if proc.stdout.strip() else ""


def stage_restore_and_verify(source: dict, env: dict, backup_path: Path, keep: bool) -> tuple[dict, dict]:
    """环节 3+4：建隔离临时库 -> pg_restore 恢复 -> 关键表行数比对（风险队列优先）。

    恢复与校验合计耗时即 RTO 的实际测量值；临时库默认演练后删除，--keep 时保留。
    """
    temp_db = f"echo_drill_{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:6]}"
    maintenance_db = "postgres"  # 建库/删库挂在维护库上，与源库隔离互不影响
    restore: dict = {"temp_database": temp_db}
    verify: dict = {"tables": [], "priority_tables": list(PRIORITY_TABLES)}

    recovery_started = time.monotonic()  # RTO 计时起点：从创建临时库开始
    log(f"创建隔离临时库：{temp_db}")
    psql_scalar(env, source, maintenance_db, f'CREATE DATABASE "{temp_db}"')
    try:
        log("开始 pg_restore 恢复到临时库")
        restore_started = time.monotonic()
        run_command(
            ["pg_restore", "--no-owner", "--no-privileges", *conn_args(source, temp_db), str(backup_path)],
            env,
        )
        restore["seconds"] = round(time.monotonic() - restore_started, 3)

        # 行数比对：风险队列表固定最先校验，证明“优先恢复且完整”
        verify_started = time.monotonic()
        for table in PRIORITY_TABLES + OTHER_TABLES:
            source_count = int(psql_scalar(env, source, source["database"], f"SELECT count(*) FROM public.{table}"))
            restored_count = int(psql_scalar(env, source, temp_db, f"SELECT count(*) FROM public.{table}"))
            verify["tables"].append(
                {
                    "table": table,
                    "priority": table in PRIORITY_TABLES,
                    "source_count": source_count,
                    "restored_count": restored_count,
                    "match": source_count == restored_count,
                }
            )
        verify["seconds"] = round(time.monotonic() - verify_started, 3)
        verify["ok"] = all(t["match"] for t in verify["tables"])
    finally:
        if keep:
            restore["dropped"] = False
            log(f"--keep 生效，保留临时库：{temp_db}")
        else:
            log(f"删除临时库：{temp_db}")
            psql_scalar(env, source, maintenance_db, f'DROP DATABASE IF EXISTS "{temp_db}"')
            restore["dropped"] = True

    restore["ok"] = True
    # 恢复总耗时：建库 + 恢复 + 校验，即灾难场景下的实际 RTO 测量值
    restore["recovery_seconds"] = round(time.monotonic() - recovery_started, 3)
    return restore, verify


def build_report(args: argparse.Namespace, source: dict, backup: dict, validate: dict,
                 restore: dict, verify: dict) -> dict:
    """汇总各环节结果，对照 RTO/RPO 目标给出达标结论。"""
    backup_finished = datetime.fromisoformat(backup["finished_at"])
    backup_age_seconds = max(0.0, (datetime.now(timezone.utc) - backup_finished).total_seconds())
    recovery_seconds = restore["recovery_seconds"]
    rto_met = recovery_seconds <= args.rto_hours * 3600
    rpo_met = backup_age_seconds <= args.rpo_hours * 3600
    priority_results = [t for t in verify["tables"] if t["priority"]]
    risk_queue_ok = bool(priority_results) and all(t["match"] for t in priority_results)
    overall_ok = all([backup["ok"], validate["ok"], restore["ok"], verify["ok"], rto_met, rpo_met, risk_queue_ok])
    return {
        "schema_version": "backup-restore-drill.v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": {"dsn": masked_dsn(source), "database": source["database"]},
        "parameters": {
            "work_dir": str(args.work_dir),
            "keep": args.keep,
            "rto_hours": args.rto_hours,
            "rpo_hours": args.rpo_hours,
        },
        "stages": {"backup": backup, "validate": validate, "restore": restore, "verify": verify},
        "objectives": {
            "rto": {
                "limit_hours": args.rto_hours,
                "recovery_seconds": recovery_seconds,
                "met": rto_met,
            },
            "rpo": {
                "limit_hours": args.rpo_hours,
                "backup_age_seconds": round(backup_age_seconds, 3),
                "met": rpo_met,
            },
            "risk_queue_priority": {
                "tables": PRIORITY_TABLES,
                "verified_first": True,  # 校验顺序固定风险队列最前
                "complete": risk_queue_ok,
                "met": risk_queue_ok,
            },
        },
        "ok": overall_ok,
    }


def print_summary(report: dict) -> None:
    """终端摘要：各环节耗时、行数比对明细、RPO/RTO 达标结论。"""
    def mark(ok: bool) -> str:
        return "✓" if ok else "✗"

    stages = report["stages"]
    print("\n================ PostgreSQL 备份恢复演练报告 ================")
    print(f"源库: {report['source']['dsn']}")
    backup = stages["backup"]
    print(f"[{mark(backup['ok'])}] 备份   {backup['seconds']:>8.2f}s  {backup['file']}")
    validate = stages["validate"]
    checks = validate["checks"]
    print(f"[{mark(validate['ok'])}] 校验   {validate['seconds']:>8.2f}s  "
          f"存在/非空/pg_restore --list 可解析（TOC {checks['toc_entries']} 项，"
          f"{checks['bytes']} 字节，sha256:{checks['sha256'][:12]}…）")
    restore = stages["restore"]
    dropped = "已清理" if restore.get("dropped") else "已保留(--keep)"
    print(f"[{mark(restore['ok'])}] 恢复   {restore['seconds']:>8.2f}s  临时库 {restore['temp_database']}（{dropped}）")
    verify = stages["verify"]
    matched = sum(1 for t in verify["tables"] if t["match"])
    print(f"[{mark(verify['ok'])}] 比对   {verify['seconds']:>8.2f}s  {matched}/{len(verify['tables'])} 表行数一致（风险队列优先）")
    for t in verify["tables"]:
        tag = "优先" if t["priority"] else "    "
        print(f"      [{tag}] {t['table']:<24} 源={t['source_count']:<8} 恢复={t['restored_count']:<8} {mark(t['match'])}")
    objectives = report["objectives"]
    print("目标对照:")
    rto = objectives["rto"]
    print(f"  [{mark(rto['met'])}] RTO ≤ {rto['limit_hours']}h：恢复+校验实际 {rto['recovery_seconds']:.2f}s"
          f"（{rto['recovery_seconds'] / 3600:.4f}h）")
    rpo = objectives["rpo"]
    print(f"  [{mark(rpo['met'])}] RPO ≤ {rpo['limit_hours']}h：备份新鲜度 {rpo['backup_age_seconds']:.1f}s"
          f"（{rpo['backup_age_seconds'] / 3600:.4f}h）")
    rq = objectives["risk_queue_priority"]
    print(f"  [{mark(rq['met'])}] 风险队列优先恢复：{'/'.join(rq['tables'])} 优先校验且行数完整一致")
    print(f"总体结论: {'通过' if report['ok'] else '未通过'}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="PostgreSQL 备份恢复演练：备份 -> 校验 -> 隔离恢复 -> 行数比对 -> RPO/RTO 报告",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--source-dsn", help="源库 DSN，如 postgresql://user:pass@host:5432/dbname（兼容 postgresql+psycopg://）")
    parser.add_argument("--work-dir", type=Path, default=Path("./backup-drill"), help="备份文件与报告输出目录")
    parser.add_argument("--keep", action="store_true", help="演练后保留隔离临时数据库（默认删除）")
    parser.add_argument("--backup-file", type=Path, help="使用既有 pg_dump custom 备份文件（跳过新备份，新鲜度按文件时间计算）")
    parser.add_argument("--rto-hours", type=float, default=DEFAULT_RTO_HOURS, help="RTO 目标小时数")
    parser.add_argument("--rpo-hours", type=float, default=DEFAULT_RPO_HOURS, help="RPO 目标小时数")
    parser.add_argument("--json", action="store_true", help="在 stdout 额外输出完整 JSON 报告")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if not args.source_dsn:
            raise DrillError("缺少 --source-dsn。示例：--source-dsn postgresql://echo:secret@db:5432/echo")
        source = parse_dsn(args.source_dsn)
        require_tools()
        args.work_dir.mkdir(parents=True, exist_ok=True)

        backup = stage_backup(source, pg_env(source), args.work_dir, args.backup_file)
        validate = stage_validate(Path(backup["file"]))
        restore, verify = stage_restore_and_verify(source, pg_env(source), Path(backup["file"]), args.keep)
    except DrillError as exc:
        print(f"演练无法执行：{exc}", file=sys.stderr)
        return 3
    except Exception as exc:  # 兜底：任何意外错误都要优雅呈现而非堆栈刷屏
        print(f"演练异常中止：{type(exc).__name__}: {exc}", file=sys.stderr)
        return 3

    report = build_report(args, source, backup, validate, restore, verify)
    report_path = args.work_dir / f"drill-report-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print_summary(report)
    print(f"\nJSON 报告: {report_path}")
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
