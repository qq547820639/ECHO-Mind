#!/usr/bin/env python3
"""
verify_wrist_signing.py —— Phone ↔ Wrist 签名身份验证（Phase 8.1）。

作用：读取 Android APK 证书指纹 与 Vela certificate.pem 指纹，输出 MATCH / MISMATCH。
- 绝不输出私钥 / 密码内容（只输出 SHA-256 指纹，指纹不是秘密）；
- APK / pem 尚未生成时清晰报告（exit 2 = 材料缺失，非 MISMATCH）。

依赖（任一可用即可，按序尝试）：
- Android APK：apksigner（Android SDK build-tools）或 keytool（JDK）；
- Vela pem：openssl。

用法：
    python3 scripts/verify_wrist_signing.py --apk path/app-debug.apk --vela-cert path/certificate.pem
    python3 scripts/verify_wrist_signing.py --apk path/app.apk    # 只有 APK
退出码：0=MATCH；1=MISMATCH/无法解析；2=材料缺失（未生成）。
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

MD5_DIGEST_LOWER = "md5"
SHA1_DIGEST_LOWER = "sha1"
SHA256_DIGEST_LOWER = "sha256"


def run(cmd, check=False):
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    except FileNotFoundError:
        return None, "tool-not-found: %s" % cmd[0]
    except subprocess.TimeoutExpired:
        return None, "timeout"
    if check and proc.returncode != 0:
        return None, "exit=%d: %s" % (proc.returncode, (proc.stderr or proc.stdout)[:200])
    return proc, None


def find_apksigner():
    """PATH → ANDROID_HOME/ANDROID_SDK_ROOT build-tools → 本地 local.properties sdk.dir。"""
    path = shutil.which("apksigner")
    if path:
        return path
    sdk_roots = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            sdk_roots.append(Path(os.environ[env]))
    sdk_roots.append(Path.home() / "Library" / "Android" / "sdk")
    # local.properties（仓库 android/local.properties 的 sdk.dir）
    local_props = Path(__file__).resolve().parent.parent / "android" / "local.properties"
    if local_props.is_file():
        for line in local_props.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("sdk.dir"):
                sdk_roots.append(Path(line.split("=", 1)[1].strip()))
                break
    for root in sdk_roots:
        bt = root / "build-tools"
        if bt.is_dir():
            versions = sorted((p.name for p in bt.iterdir() if p.is_dir()), reverse=True)
            for v in versions:
                cand = bt / v / "apksigner"
                if cand.is_file():
                    return str(cand)
    return None


def sha256_upper(fingerprint_text: str) -> str:
    """把 fingerprint 文本归一化为大写 SHA256 hex（去掉冒号/空格；兼容 SHA1 输入时保留原值）。"""
    cleaned = fingerprint_text.replace(":", "").replace(" ", "").strip().upper()
    return cleaned


def apk_sha256(apk: Path):
    """APK 签名证书 SHA-256 指纹（apksigner 优先；退化 keytool）。返回 (指纹, None) 或 (None, 原因)。"""
    apksigner = find_apksigner()
    if apksigner:
        proc, err = run([apksigner, "verify", "--print-certs", str(apk)])
        if proc is not None and proc.returncode == 0:
            out = proc.stdout + proc.stderr
            digest = None
            in_cert = False
            for line in out.splitlines():
                if "certificate DN" in line:
                    in_cert = True
                    continue
                if in_cert and "SHA-256 digest" in line:
                    digest = line.split(":", 1)[1].strip()
                    break
            if digest:
                return sha256_upper(digest), None
        elif err is not None:
            return None, err
    keytool = shutil.which("keytool")
    if keytool:
        proc, err = run([keytool, "-printcert", "-jarfile", str(apk)])
        if proc is not None and proc.returncode == 0:
            for line in proc.stdout.splitlines():
                if "SHA256:" in line:
                    return sha256_upper(line.split("SHA256:", 1)[1]), None
        elif err is not None:
            return None, err
    return None, "需要 apksigner 或 keytool 工具读取 APK 证书"


def pem_sha256(pem: Path):
    """certificate.pem 的 SHA-256 指纹（openssl）。"""
    openssl = shutil.which("openssl")
    if not openssl:
        return None, "需要 openssl 读取 certificate.pem"
    proc, err = run([openssl, "x509", "-in", str(pem), "-noout", "-fingerprint", "-sha256"])
    if proc is not None and proc.returncode == 0:
        for line in proc.stdout.splitlines():
            if "SHA256 Fingerprint=" in line:
                return sha256_upper(line.split("=", 1)[1]), None
        return None, "certificate.pem 解析失败（文件存在但无法提取指纹）"
    return None, err or ("exit=%d" % proc.returncode)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, help="Android APK 路径")
    parser.add_argument("--vela-cert", type=Path, help="Vela certificate.pem 路径")
    args = parser.parse_args()

    apk = args.apk
    pem = args.vela_cert

    apk_sha = pem_sha = None
    missing = []

    if apk is not None:
        if not apk.is_file():
            missing.append("APK 未生成：%s" % apk)
        else:
            apk_sha, err = apk_sha256(apk)
            if apk_sha is None:
                print("APK 指纹解析失败：%s" % err)
                return 1
            print("APK  SHA-256: %s" % apk_sha)
    else:
        missing.append("未提供 --apk")

    if pem is not None:
        if not pem.is_file():
            missing.append("certificate.pem 未生成：%s" % pem)
        else:
            pem_sha, err = pem_sha256(pem)
            if pem_sha is None:
                print("pem 指纹解析失败：%s" % err)
                return 1
            print("PEM  SHA-256: %s" % pem_sha)
    else:
        missing.append("未提供 --vela-cert")

    if missing:
        for m in missing:
            print("MISSING:", m)
        print("RESULT: 材料缺失（APK/RPK 尚未生成，不视为 MISMATCH）")
        return 2

    if apk_sha == pem_sha:
        print("RESULT: MATCH（同一签名身份，interconnect 身份要求满足）")
        return 0
    print("RESULT: MISMATCH（两端证书指纹不一致，interconnect 将拒绝身份关联）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
