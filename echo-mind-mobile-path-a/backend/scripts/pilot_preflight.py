#!/usr/bin/env python3
"""试点/发布门 preflight 工具（对应 spec Task 7，v0.3.0 发布门）。

无参数运行保持原有用法：输出 JSON 到 stdout（保留 "ok"/"checks" 键与
0/2 退出码语义）。在此基础上扩展 v0.3.0 发布门检查：

自动检查项（PASS/FAIL + 证据）：
  A1 审计链完整性    复用 /v1/audit/verify（verify_audit_chain 哈希链校验）
  A2 角色矩阵        8 角色令牌可签发；vendor_support 读 /v1 数据被拒；
                     admin 读心理内容被拒；security_auditor 写操作被拒
  A3 SLA 配置        ack/takeover/org_lead SLA 为正值且 sla-scan 端点可用
  A4 宣称扫描        scripts/claim_scan.py（应用内文案/内容包/商店文案/合规材料）
  A5 内容包哈希      scripts/validate_content_packs.py，且 MANIFEST.generated.json
                     与提交内容一致（无漂移）
  A6 Alembic 回放    临时 SQLite 上 upgrade head 全量回放（0001→0004）

人工确认项（PENDING_MANUAL，M1–M8）：真实 8 台设备验证、KMS 启用、渗透测试、
临床/法务签署、机构值班表、培训考核、Alpha Go 与合同签署、Android 可重复构建。

总体结论规则：任一自动项 FAIL（含既有环境/密钥检查）→ NO-GO；
存在 PENDING_MANUAL → CONDITIONAL：待人工确认项完成；全部 PASS → GO。

两种运行模式：
  自测/离线（默认）：A1–A3 在进程内 TestClient（内存 SQLite 独立子进程）执行，
    其余项对仓库离线执行。python scripts/pilot_preflight.py [--out DIR]
  目标环境：--base-url http://host:8000 --jwt-secret <服务端密钥>
    [--bootstrap-key KEY]：A1–A3 改为对运行中的服务做 HTTP 检查
    （自动开通独立检查租户，不产生真实用户数据）。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = BACKEND_DIR.parent
sys.path.insert(0, str(BACKEND_DIR))

GATE_VERSION = "v0.3.0"
ALEMBIC_HEAD = "20260729_0004"  # 0001→0004 全量回放后的 head revision

AUTO_TITLES = {
    "audit_chain": "审计链完整性（哈希链校验）",
    "role_matrix": "8 角色权限矩阵（签发与越权拒绝）",
    "sla_config": "SLA 配置与 sla-scan 端点",
    "claim_scan": "宣称扫描（应用内/商店/合规材料）",
    "content_packs": "内容包与 MANIFEST 哈希一致",
    "alembic_replay": "Alembic 临时 SQLite 全量回放（0001→0004）",
}
AUTO_ORDER = ["audit_chain", "role_matrix", "sla_config", "claim_scan", "content_packs", "alembic_replay"]
ENV_CHECK_IDS = ("audit_chain", "role_matrix", "sla_config")

# 外部依赖项：无法由代码/沙箱替代，必须责任主体实际执行并签署证据
# （依据 pilot-pack/09_外部依赖任务清单.md 与 pilot-pack/00_试点就绪总控表.md）。
MANUAL_ITEMS = [
    ("device_matrix_8", "真实 8 台目标设备构建、安装与回归",
     "移动开发团队＋试点机构（pilot-pack/10_设备测试矩阵.csv）"),
    ("kms_enabled", "生产 KMS/HSM 启用并完成密钥轮换演练",
     "安全/云团队（密钥轮换记录）"),
    ("penetration_test", "外部渗透测试并关闭全部严重/高危漏洞",
     "独立安全机构（渗透报告与复测）"),
    ("clinical_legal_signoff", "临床/法务签署（内容四方签名、PIPIA 与隐私政策定稿）",
     "具资质临床专家＋法务/数据保护负责人"),
    ("org_duty_roster", "机构值班表与第二升级联系人到位",
     "试点机构（值班表与演练记录）"),
    ("training_assessment", "专业人员培训与考核通过",
     "试点机构＋临床负责人（pilot-pack/12_专业人员培训与考核.md）"),
    ("alpha_go_contract", "Alpha 内部测试 Go 决策与机构合同签署",
     "机构负责人＋产品/工程/临床/安全/法务"),
    ("android_reproducible_build", "Android 可重复构建（APK/AAB 签名、哈希、provenance）",
     "移动开发团队/CI（构建与签名记录）"),
]


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def short(text: str, limit: int = 300) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"


# ---------------------------------------------------------------------------
# 既有检查项（保持 v0.2 行为：环境/密钥/数据库五项）。
# ---------------------------------------------------------------------------
def legacy_checks() -> list[dict]:
    from sqlalchemy import text

    from app.config import get_settings
    from app.database import SessionLocal

    settings = get_settings()
    checks = []
    checks.append({
        "name": "environment",
        "ok": settings.environment in {"pilot", "production"},
        "value": settings.environment,
    })
    checks.append({"name": "jwt_secret", "ok": "dev-secret" not in settings.jwt_secret})
    checks.append({"name": "field_encryption_secret", "ok": "dev-field" not in settings.field_encryption_secret})
    checks.append({"name": "bootstrap_key", "ok": settings.bootstrap_key != "local-bootstrap-only"})
    try:
        with SessionLocal() as db:
            db.execute(text("SELECT 1"))
        checks.append({"name": "database", "ok": True})
    except Exception as exc:
        checks.append({"name": "database", "ok": False, "error": type(exc).__name__})
    return checks


# ---------------------------------------------------------------------------
# A1–A3 自测模式：独立子进程（内存 SQLite + 自测密钥，隔离真实环境配置）。
# 父进程设置环境变量后拉起本脚本 --selftest-worker，worker 把结果 JSON 打到 stdout。
# ---------------------------------------------------------------------------
SELFTEST_ENV = {
    "ENVIRONMENT": "local",
    "DATABASE_URL": "sqlite+pysqlite:///:memory:",
    "JWT_SECRET": "gate-selftest-secret-at-least-32-bytes",
}
GATE_TENANT = "t_gate"
GATE_USER = "u_gate"


def selftest_worker() -> int:
    """进程内 TestClient 执行角色矩阵/审计链/SLA 配置检查（结果 JSON 打 stdout）。"""
    from fastapi.testclient import TestClient

    from app.auth import ALLOWED_ROLES, create_access_token
    from app.config import get_settings
    from app.database import Base, SessionLocal, engine
    from app.main import app
    from app.models import Consent, Tenant, User

    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        db.add(Tenant(id=GATE_TENANT, name="发布门自测机构"))
        db.add(User(id=GATE_USER, tenant_id=GATE_TENANT, external_ref="gate-user"))
        db.add(Consent(
            id="c_gate", tenant_id=GATE_TENANT, user_id=GATE_USER,
            consent_type="psychological_data", version="gate-consent-v1",
            granted=True, evidence_hash=hashlib.sha256(b"gate-consent").hexdigest(),
        ))
        db.commit()
    client = TestClient(app)
    tokens = {role: create_access_token(f"gate_{role}", GATE_TENANT, role) for role in sorted(ALLOWED_ROLES)}

    def call(method: str, path: str, role: str, **kw):
        return client.request(method, path, headers={"Authorization": f"Bearer {tokens[role]}"}, **kw)

    return run_env_checks(call, tokens, get_settings(), source="自测 TestClient")


def run_env_checks(call, tokens: dict, settings, *, source: str) -> int:
    """A1–A3 公共检查逻辑：call 为带角色令牌的发请求封装，tokens 为 8 角色令牌。"""
    results: dict[str, dict] = {}

    # A2 角色矩阵：8 角色令牌签发 + 关键越权拒绝。
    issuance_ok = len(tokens) == 8 and all(tokens.values())
    consent_payload = {
        "user_id": GATE_USER, "consent_type": "psychological_data",
        "version": "gate-rbac-v1", "granted": True, "evidence_hash": "0" * 64,
    }
    probes = [
        ("vendor_support 读升级队列被拒(403)",
         call("GET", "/v1/escalations", "vendor_support").status_code == 403),
        ("vendor_support 读审计事件被拒(403)",
         call("GET", "/v1/audit/events", "vendor_support").status_code == 403),
        ("admin 读心理内容被拒(403)",
         call("GET", f"/v1/journals?user_id={GATE_USER}", "admin").status_code == 403),
        ("security_auditor 写操作被拒(403)",
         call("POST", "/v1/onboarding/consents", "security_auditor", json=consent_payload).status_code == 403),
        ("security_auditor 只读审计链校验允许(200)",
         call("GET", "/v1/audit/verify", "security_auditor").status_code == 200),
    ]
    failed = [name for name, ok in probes if not ok]
    results["role_matrix"] = {
        "status": "PASS" if issuance_ok and not failed else "FAIL",
        "evidence": (
            f"{source}：8 角色令牌签发={'OK' if issuance_ok else 'FAIL'}；"
            + ("越权探测 5/5 按预期拒绝/放行" if not failed else f"异常探测：{failed}")
        ),
    }

    # A1 审计链完整性：上述越权探测已写入审计事件，校验哈希链。
    verify = call("GET", "/v1/audit/verify", "auditor")
    body = verify.json() if verify.status_code == 200 else {}
    ok = verify.status_code == 200 and body.get("valid") is True and body.get("events", 0) > 0
    results["audit_chain"] = {
        "status": "PASS" if ok else "FAIL",
        "evidence": (
            f"{source}：/v1/audit/verify valid={body.get('valid')}，"
            f"事件数={body.get('events')}，head={str(body.get('head_hash'))[:12]}"
        ),
    }

    # A3 SLA 配置：三项 SLA 为正值且 sla-scan 端点可用。
    sla_values = {
        "ack": settings.ack_sla_seconds,
        "takeover": settings.takeover_sla_seconds,
        "org_lead": settings.org_lead_sla_seconds,
    }
    positive = all(v > 0 for v in sla_values.values())
    scan = call("POST", "/v1/escalations/sla-scan", "on_call")
    scan_keys = {"scanned", "notified_second_duty", "notified_org_lead", "chain_broken"}
    scan_ok = scan.status_code == 200 and scan_keys <= set(scan.json())
    results["sla_config"] = {
        "status": "PASS" if positive and scan_ok else "FAIL",
        "evidence": (
            f"{source}：ack={sla_values['ack']}s，takeover={sla_values['takeover']}s，"
            f"org_lead={sla_values['org_lead']}s（均为正={positive}）；"
            f"sla-scan HTTP {scan.status_code}（字段齐全={scan_ok}）"
        ),
    }
    print(json.dumps(results, ensure_ascii=False))
    return 0


def selftest_env_checks() -> dict:
    """拉起自测 worker 子进程并解析其 JSON 结果。"""
    env = dict(os.environ)
    env.update(SELFTEST_ENV)
    try:
        proc = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--selftest-worker"],
            capture_output=True, text=True, cwd=BACKEND_DIR, env=env, timeout=180,
        )
    except subprocess.TimeoutExpired:
        return _env_fail("自测子进程超时（>180s）")
    if proc.returncode != 0:
        return _env_fail(f"自测子进程退出码 {proc.returncode}：{short((proc.stderr or proc.stdout).strip())}")
    try:
        return json.loads(proc.stdout.strip().splitlines()[-1])
    except (json.JSONDecodeError, IndexError):
        return _env_fail(f"自测子进程输出无法解析：{short(proc.stdout.strip())}")


def _env_fail(evidence: str) -> dict:
    return {key: {"status": "FAIL", "evidence": evidence} for key in ENV_CHECK_IDS}


# ---------------------------------------------------------------------------
# A1–A3 HTTP 模式：对 --base-url 目标环境检查（--jwt-secret 铸造令牌）。
# ---------------------------------------------------------------------------
def mint_token(subject: str, tenant_id: str, role: str, secret: str,
               issuer: str, audience: str, *, minutes: int = 60) -> str:
    """按 app.auth.create_access_token 的声明结构铸造检查令牌。"""
    import jwt

    now = datetime.now(timezone.utc)
    payload = {
        "sub": subject, "tenant_id": tenant_id, "role": role, "step_up": False,
        "iss": issuer, "aud": audience, "iat": now, "exp": now.timestamp() + minutes * 60,
    }
    return jwt.encode(payload, secret, algorithm="HS256")


def http_env_checks(args: argparse.Namespace) -> dict:
    import httpx

    from app.auth import ALLOWED_ROLES

    if not args.jwt_secret:
        raise RuntimeError("HTTP 模式需要 --jwt-secret（服务端 JWT_SECRET）以铸造 8 角色检查令牌")
    client = httpx.Client(base_url=args.base_url.rstrip("/"), timeout=15.0)

    def bare(method: str, path: str, **kw):
        return client.request(method, path, **kw)

    health = bare("GET", "/health")
    if health.status_code != 200:
        raise RuntimeError(f"目标服务 /health 不可用（HTTP {health.status_code}）")

    def mint(sub: str, tenant: str, role: str) -> str:
        return mint_token(sub, tenant, role, args.jwt_secret, args.jwt_issuer, args.jwt_audience)

    # 开通独立检查租户与检查账号（不触碰真实用户数据）。
    created = bare("POST", "/v1/tenants", json={"name": f"gate-check-{uuid.uuid4().hex[:8]}"},
                   headers={"X-Bootstrap-Key": args.bootstrap_key})
    if created.status_code != 200:
        raise RuntimeError(
            f"创建检查租户失败 HTTP {created.status_code}：{short(created.text, 200)}"
            "（请确认 --bootstrap-key 与服务端一致）"
        )
    tenant_id = created.json()["id"]
    admin_token = mint("gate_admin", tenant_id, "admin")
    user_resp = bare("POST", "/v1/users", json={"external_ref": f"gate-user-{uuid.uuid4().hex[:8]}"},
                     headers={"Authorization": f"Bearer {admin_token}"})
    if user_resp.status_code != 200:
        raise RuntimeError(f"创建检查账号失败 HTTP {user_resp.status_code}：{short(user_resp.text, 200)}")
    user_id = user_resp.json()["id"]
    consent = bare("POST", "/v1/onboarding/consents",
                   json={"user_id": user_id, "consent_type": "psychological_data",
                         "version": "gate-consent-v1", "granted": True,
                         "evidence_hash": hashlib.sha256(b"gate-consent").hexdigest()},
                   headers={"Authorization": f"Bearer {mint(user_id, tenant_id, 'user')}"})
    if consent.status_code != 200:
        raise RuntimeError(f"记录检查同意失败 HTTP {consent.status_code}：{short(consent.text, 200)}")

    global GATE_USER
    GATE_USER = user_id  # run_env_checks 的探测目标账号
    tokens = {role: mint(f"gate_{role}", tenant_id, role) for role in sorted(ALLOWED_ROLES)}

    def call(method: str, path: str, role: str, **kw):
        return client.request(method, path, headers={"Authorization": f"Bearer {tokens[role]}"}, **kw)

    class _SlaView:
        """与自测模式同形的 settings 视图：ack/takeover 读服务端 metrics，org_lead 用参数。"""

        def __init__(self, ack: float, takeover: float, org_lead: int):
            self.ack_sla_seconds = ack
            self.takeover_sla_seconds = takeover
            self.org_lead_sla_seconds = org_lead

    metrics = call("GET", "/v1/escalations/metrics", "admin")
    if metrics.status_code != 200:
        raise RuntimeError(f"/v1/escalations/metrics 不可用（HTTP {metrics.status_code}）")
    body = metrics.json()
    sla_view = _SlaView(
        body.get("ack_sla_seconds", 0),
        body.get("takeover_sla_seconds", 0),
        args.org_lead_sla_seconds,
    )
    import io
    import contextlib

    buf = io.StringIO()  # run_env_checks 会把结果 JSON print 出来，这里截获
    with contextlib.redirect_stdout(buf):
        run_env_checks(call, tokens, sla_view,
                       source=f"HTTP {args.base_url}（org_lead 取自 --org-lead-sla-seconds）")
    return json.loads(buf.getvalue().strip().splitlines()[-1])


def env_checks(args: argparse.Namespace) -> dict:
    if not args.base_url:
        return selftest_env_checks()
    try:
        return http_env_checks(args)
    except Exception as exc:
        return _env_fail(f"HTTP 模式检查中止：{type(exc).__name__}: {short(str(exc))}")


# ---------------------------------------------------------------------------
# A4–A6 仓库离线检查项。
# ---------------------------------------------------------------------------
def check_claim_scan() -> dict:
    script = REPO_ROOT / "scripts" / "claim_scan.py"
    proc = subprocess.run([sys.executable, str(script)], capture_output=True, text=True,
                          cwd=REPO_ROOT, timeout=120)
    if proc.returncode == 0:
        return {"status": "PASS",
                "evidence": f"{proc.stdout.strip() or 'claim scan passed'}（应用内文案/内容包/商店文案/合规材料）"}
    hits = [line for line in proc.stderr.splitlines() if line.strip()]
    return {"status": "FAIL",
            "evidence": f"命中 {len(hits)} 处禁用宣称：{short('；'.join(hits[:5]))}"}


def check_content_packs() -> dict:
    manifest = REPO_ROOT / "content-packs" / "MANIFEST.generated.json"
    before = manifest.read_bytes() if manifest.exists() else b""
    proc = subprocess.run([sys.executable, str(REPO_ROOT / "scripts" / "validate_content_packs.py")],
                          capture_output=True, text=True, cwd=REPO_ROOT, timeout=120)
    if proc.returncode != 0:
        return {"status": "FAIL", "evidence": short(proc.stderr.strip() or "validate_content_packs 失败")}
    after = manifest.read_bytes()
    sha = hashlib.sha256(after).hexdigest()
    if before != after:
        return {"status": "FAIL",
                "evidence": (f"MANIFEST.generated.json 与内容包哈希漂移（校验后清单被重写，"
                             f"sha256={sha[:12]}…），请重新生成并提交清单")}
    return {"status": "PASS",
            "evidence": f"{proc.stdout.strip()}；manifest sha256={sha[:12]}… 与提交一致", "sha256": sha}


def check_alembic_replay() -> dict:
    with tempfile.TemporaryDirectory(prefix="echo-gate-alembic-") as tmp:
        env = dict(os.environ)
        env["DATABASE_URL"] = f"sqlite:///{Path(tmp) / 'gate.db'}"
        upgrade = subprocess.run([sys.executable, "-m", "alembic", "upgrade", "head"],
                                 capture_output=True, text=True, cwd=BACKEND_DIR, env=env, timeout=300)
        if upgrade.returncode != 0:
            return {"status": "FAIL",
                    "evidence": f"upgrade head 失败：{short((upgrade.stderr or upgrade.stdout).strip())}"}
        current = subprocess.run([sys.executable, "-m", "alembic", "current"],
                                 capture_output=True, text=True, cwd=BACKEND_DIR, env=env, timeout=60)
        rev = current.stdout.strip().split()[:1]
        revision = rev[0] if rev else ""
        ok = current.returncode == 0 and revision == ALEMBIC_HEAD
        evidence = (f"临时 SQLite 全量回放成功，current={revision} (head)"
                    if ok else f"回放后 revision 异常：{short(current.stdout.strip() or current.stderr.strip())}")
        return {"status": "PASS" if ok else "FAIL", "evidence": evidence, "revision": revision or None}


# ---------------------------------------------------------------------------
# 发布门记录（JSON + Markdown）。
# ---------------------------------------------------------------------------
def git_commit() -> str:
    try:
        proc = subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True, cwd=REPO_ROOT, timeout=15)
        return proc.stdout.strip() if proc.returncode == 0 else "unknown"
    except Exception:
        return "unknown"


def render_markdown(report: dict) -> str:
    lines = [
        f"# {report['gate_version']} 发布门 Go / No-Go 记录",
        "",
        f"- 报告编号：{report['report_id']}",
        f"- 生成时间：{report['generated_at']}",
        f"- 检查模式：{report['mode']}（{report['base_url']}）",
        f"- 总体结论：**{report['overall_conclusion']}**",
        "",
        "## 构建",
        "",
        f"- 后端 commit：{report['build']['backend_commit']}",
        f"- 内容包 manifest 哈希：{report['build']['content_manifest_sha256']}",
        f"- 数据库 revision：{report['build']['database_revision']}",
        "",
        "## 门禁清单",
        "",
        "### 自动检查项",
        "",
        "| 编号 | 项目 | 结论 | 证据 |",
        "| --- | --- | --- | --- |",
    ]
    for index, item in enumerate([i for i in report["items"] if i["kind"] == "automatic"], 1):
        lines.append(f"| A{index} | {item['title']} | {item['status']} | {short(item['evidence'], 80)} |")
    lines += [
        "",
        "### 人工确认项（外部依赖，待责任主体执行并签署证据）",
        "",
        "| 编号 | 项目 | 状态 | 责任方提示 |",
        "| --- | --- | --- | --- |",
    ]
    for index, item in enumerate([i for i in report["items"] if i["kind"] == "manual"], 1):
        lines.append(f"| M{index} | {item['title']} | {item['status']} | {item['responsible']} |")
    lines += [
        "",
        "## 既有环境/密钥检查（v0.2 保留项）",
        "",
        "| 检查 | 结果 |",
        "| --- | --- |",
    ]
    for check in report["legacy_checks"]:
        detail = check.get("value") or check.get("error") or ""
        lines.append(f"| {check['name']} | {'PASS' if check['ok'] else 'FAIL'} {detail} |")
    lines += [
        "",
        "## 决策规则",
        "",
        "- 任一自动检查项 FAIL（含既有环境/密钥检查）→ **NO-GO：停止部署**",
        "- 存在 PENDING_MANUAL → **CONDITIONAL：待人工确认项完成**（GO 需人工门）",
        "- 自动项全部 PASS 且无人工待办 → **GO：仅限批准机构、人数和周期**",
        "",
        "签字：产品 / 工程 / 临床 / 安全 / 法务合规 / 机构负责人。",
        "",
    ]
    return "\n".join(lines)


def write_reports(report: dict, out_dir: Path) -> tuple[Path, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    json_path = out_dir / f"release-gate-{GATE_VERSION}-{stamp}.json"
    md_path = out_dir / f"release-gate-{GATE_VERSION}-{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md_path.write_text(render_markdown(report), encoding="utf-8")
    return json_path, md_path


# ---------------------------------------------------------------------------
# 主流程。
# ---------------------------------------------------------------------------
def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=f"{GATE_VERSION} 发布门 preflight：既有环境检查 + 发布门自动/人工清单 + Go/No-Go 记录。",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--base-url", default=None,
                        help="目标环境地址（缺省为进程内 TestClient 自测/离线模式）")
    parser.add_argument("--jwt-secret", default=os.environ.get("JWT_SECRET"),
                        help="HTTP 模式：服务端 JWT_SECRET，用于铸造 8 角色检查令牌")
    parser.add_argument("--jwt-issuer", default="echo-mind-local")
    parser.add_argument("--jwt-audience", default="echo-mind-api")
    parser.add_argument("--bootstrap-key", default="local-bootstrap-only",
                        help="HTTP 模式：开通检查租户用的 X-Bootstrap-Key")
    parser.add_argument("--org-lead-sla-seconds", type=int, default=600,
                        help="HTTP 模式：机构负责人层 SLA 秒数（服务端未暴露，需与服务端配置一致）")
    parser.add_argument("--out", default="release-gate-reports", help="发布门记录输出目录")
    parser.add_argument("--selftest-worker", action="store_true", help=argparse.SUPPRESS)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.selftest_worker:
        return selftest_worker()

    checks = legacy_checks()
    auto: dict[str, dict] = {}
    auto.update(env_checks(args))
    auto["claim_scan"] = check_claim_scan()
    auto["content_packs"] = check_content_packs()
    auto["alembic_replay"] = check_alembic_replay()

    items = [
        {"id": key, "title": AUTO_TITLES[key], "kind": "automatic",
         "status": auto[key]["status"], "evidence": auto[key]["evidence"]}
        for key in AUTO_ORDER
    ]
    items += [
        {"id": key, "title": title, "kind": "manual",
         "status": "PENDING_MANUAL", "responsible": responsible}
        for key, title, responsible in MANUAL_ITEMS
    ]

    legacy_failed = [c["name"] for c in checks if not c["ok"]]
    auto_failed = [i["id"] for i in items if i["kind"] == "automatic" and i["status"] == "FAIL"]
    pending_manual = [i["id"] for i in items if i["kind"] == "manual"]
    if auto_failed or legacy_failed:
        conclusion = "NO-GO：停止部署"
        detail = f"FAIL 项：{auto_failed or '-'}；既有环境/密钥检查未通过：{legacy_failed or '-'}"
    elif pending_manual:
        conclusion = "CONDITIONAL：待人工确认项完成"
        detail = f"自动项全部 PASS；{len(pending_manual)} 项 PENDING_MANUAL 待责任主体确认"
    else:
        conclusion = "GO：仅限批准机构、人数和周期"
        detail = "自动项全部 PASS 且无人工待办"

    report = {
        "schema_version": "release-gate.v1",
        "gate_version": GATE_VERSION,
        "report_id": f"gate-{uuid.uuid4().hex[:12]}",
        "generated_at": utc_now_iso(),
        "mode": "http" if args.base_url else "self-test",
        "base_url": args.base_url or "in-process://testclient",
        "build": {
            "backend_commit": git_commit(),
            "content_manifest_sha256": auto["content_packs"].get("sha256", "unknown"),
            "database_revision": auto["alembic_replay"].get("revision") or "unknown",
        },
        "legacy_checks": checks,
        "items": items,
        "summary": {
            "automatic_pass": sum(1 for i in items if i["kind"] == "automatic" and i["status"] == "PASS"),
            "automatic_fail": len(auto_failed),
            "pending_manual": len(pending_manual),
        },
        "overall_conclusion": conclusion,
        "conclusion_detail": detail,
    }
    json_path, md_path = write_reports(report, Path(args.out))

    # stdout JSON 保持 v0.2 结构（ok/checks 键不变），新增 gate 节。
    result = {
        "ok": not auto_failed and not legacy_failed,
        "checks": checks,
        "gate": {
            "version": GATE_VERSION,
            "mode": report["mode"],
            "items": items,
            "summary": report["summary"],
            "overall_conclusion": conclusion,
            "conclusion_detail": detail,
            "report_files": {"json": str(json_path), "markdown": str(md_path)},
        },
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    for item in [i for i in items if i["kind"] == "automatic"]:
        print(f"  {item['id']:16s} {item['status']:4s} {item['title']}", file=sys.stderr)
    print(f"总体结论：{conclusion}（{detail}）", file=sys.stderr)
    print(f"报告：{json_path} / {md_path}", file=sys.stderr)
    return 0 if result["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
