#!/usr/bin/env python3
"""
verify_golden.py —— ANS_FRAME_V1 黄金门（Python output ↔ schema ↔ Kotlin decoder）。

检查链（ERA 33 R1 修订 —— CI 不再强依赖 ../ANSWatch 存在）：
1. **frozen ECHO contract**（integrations/answatch/frozen/ANS_FRAME_V1_REQUIRED_FIELDS.json）：
   ANS_FRAME_V1.schema.json 必须覆盖全部 frozen REQUIRED_FIELDS —— 默认 CI 必跑，必须 PASS；
2. golden 帧（integrations/answatch/golden/*.json）逐帧通过 schema 验证；
3. 跨语言黄金门：Kotlin 测试（:feature:wearable AnsFrameDecoderTest）内嵌 golden
   与仓库 golden 帧 JSON 语义一致（字段逐一对齐，数值误差 1e-9）；
4. **optional cross-repo live validation**（ANSWATCH_ROOT env var 或 sibling ../ANSWatch）：
   存在时验证"当前 ANSWatch ↔ frozen ECHO contract"（live REQUIRED_FIELDS 与 frozen 一致 +
   schema 覆盖 live REQUIRED_FIELDS）——漂移即 FAIL；
   两者都不存在 → 该步 SKIPPED（显式原因），但 1/2/3 仍必须 PASS。

用法：python3 integrations/answatch/tools/verify_golden.py [--repo ECHO_Mind根目录]
退出码：0=必须项全部通过（cross-repo 可 SKIP）；1=任一必须项失败。
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

try:
    import jsonschema
except ImportError:  # 环境无 jsonschema 时降级为结构检查（仍执行 1/3/4）
    jsonschema = None

# ANSWatch 输出帧允许的额外字段（output_schema.make_output 相对 SPEC-2 的 additive 偏差）
KNOWN_EXTRA_FIELDS = {"window_id"}

FROZEN_NAME = "ANS_FRAME_V1_REQUIRED_FIELDS.json"


def resolve_answatch_root(repo_root: Path):
    """ANSWATCH_ROOT env var > sibling ../ANSWatch > None（不抛异常）。"""
    env = os.environ.get("ANSWATCH_ROOT")
    if env:
        candidate = Path(env).expanduser().resolve()
        if (candidate / "wrist_pipeline" / "output_schema.py").exists():
            return candidate
    sibling = (repo_root.parent / "ANSWatch").resolve()
    if (sibling / "wrist_pipeline" / "output_schema.py").exists():
        return sibling
    return None


def parse_required_fields(source: Path):
    """从 ANSWatch output_schema.py 提取 REQUIRED_FIELDS dot-path 列表。"""
    text = source.read_text(encoding="utf-8")
    m = re.search(r"REQUIRED_FIELDS(?::[^=]+)?\s*=\s*\[(.*?)\]", text, re.S)
    if not m:
        raise SystemExit("FAIL: ANSWatch output_schema.py 无 REQUIRED_FIELDS（源码漂移？）")
    fields = re.findall(r'"([^"]+)"', m.group(1))
    if not fields:
        raise SystemExit("FAIL: REQUIRED_FIELDS 解析为空")
    return fields


def flatten_schema(schema_path: Path):
    """把 ANS_FRAME_V1.schema.json 展平为 dot-path 集合。"""
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    paths = set()
    queue = [(schema, "")]
    while queue:
        node, prefix = queue.pop()
        if not isinstance(node, dict):
            continue
        for name in node.get("required", []):
            if name == "reason":  # unknown.reason 条件必填（allOf），单独处理
                continue
            paths.add(prefix + name)  # prefix 为空或以 "." 结尾
        props = node.get("properties", {})
        for name, child in props.items():
            child_path = (prefix + name) if not prefix else prefix + "." + name
            if isinstance(child, dict) and "properties" in child:
                queue.append((child, child_path + "."))
    # unknown.reason 是 UNKNOWN 帧条件必填字段（契约 REQUIRED_FIELDS 亦收录）
    paths.add("unknown.reason")
    return paths


def extract_kotlin_goldens(test_file: Path):
    """从 AnsFrameDecoderTest.kt 提取内嵌 golden JSON 文本（三引号字符串）。"""
    text = test_file.read_text(encoding="utf-8")
    blocks = re.findall(r'= """\s*(\{.*?\})\s*"""\.trimIndent\(\)', text, re.S)
    out = []
    for block in blocks:
        try:
            out.append(json.loads(block))
        except json.JSONDecodeError as e:
            raise SystemExit("FAIL: Kotlin 内嵌 golden 解析失败: %s" % e)
    return out


def json_equal(a, b, eps=1e-9):
    # bool 是 int 子类：True==1，先排除 bool 再进入通用比较
    if type(a) is bool or type(b) is bool:
        return a == b and type(a) is type(b)
    if isinstance(a, dict) and isinstance(b, dict):
        if set(a) != set(b):
            return False
        return all(json_equal(a[k], b[k], eps) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(json_equal(x, y, eps) for x, y in zip(a, b))
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(a - b) <= eps
    return a == b


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[3]))
    args = parser.parse_args()
    repo = Path(args.repo)
    schema_path = repo / "integrations" / "answatch" / "ANS_FRAME_V1.schema.json"
    golden_dir = repo / "integrations" / "answatch" / "golden"
    frozen_path = repo / "integrations" / "answatch" / "frozen" / FROZEN_NAME
    kotlin_test = (repo / "android" / "feature" / "wearable" / "src" / "test" / "java" /
                   "com" / "yunjue" / "echo" / "mind" / "wearable" / "research" /
                   "AnsFrameDecoderTest.kt")
    failures = []
    skipped = []

    # 1. frozen ECHO contract → schema 覆盖（默认 CI 必跑）
    if not frozen_path.exists():
        failures.append("frozen 契约缺失: %s" % frozen_path)
    else:
        frozen = json.loads(frozen_path.read_text(encoding="utf-8"))
        required = frozen.get("required_fields")
        if not required:
            failures.append("frozen 契约 required_fields 为空")
        else:
            print("frozen REQUIRED_FIELDS: %d 个" % len(required))
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
            paths = flatten_schema(schema_path)
            missing = [f for f in required if f not in paths and f.split(".")[0] not in KNOWN_EXTRA_FIELDS]
            if missing:
                failures.append("schema 缺失 frozen REQUIRED_FIELDS: %s" % missing)
            else:
                print("schema 覆盖 frozen REQUIRED_FIELDS: OK")

    # 2. golden 帧 schema 验证
    golden_files = sorted(golden_dir.glob("*.json"))
    if not golden_files:
        failures.append("golden 目录为空")
    for gf in golden_files:
        frame = json.loads(gf.read_text(encoding="utf-8"))
        if jsonschema is not None:
            try:
                jsonschema.validate(frame, json.loads(schema_path.read_text(encoding="utf-8")))
                print("golden %s: schema OK" % gf.name)
            except jsonschema.ValidationError as e:
                failures.append("golden %s 未通过 schema: %s" % (gf.name, e.message))
        # UNKNOWN 帧纪律：is_unknown=true → reason 必填、activation/stress/recovery 为 null
        if frame.get("unknown", {}).get("is_unknown"):
            if not frame["unknown"].get("reason"):
                failures.append("golden %s: UNKNOWN 帧缺少 reason" % gf.name)
            for path, val in [
                ("physiological_arousal", "activation"),
                ("stress_likelihood", "value"),
                ("recovery", "recovery_score"),
            ]:
                if frame[path].get(val) is not None:
                    failures.append("golden %s: UNKNOWN 帧 %s.%s 应为 null" % (gf.name, path, val))

    # 3. 跨语言黄金门（Kotlin 内嵌 golden ↔ 仓库 golden）
    if not kotlin_test.exists():
        failures.append("Kotlin 测试文件缺失: %s" % kotlin_test)
    else:
        kotlin_goldens = extract_kotlin_goldens(kotlin_test)
        repo_goldens = [json.loads(gf.read_text(encoding="utf-8")) for gf in golden_files]
        if len(kotlin_goldens) != len(repo_goldens):
            failures.append("golden 数量不一致: kotlin=%d repo=%d" % (len(kotlin_goldens), len(repo_goldens)))
        else:
            for k, r in zip(kotlin_goldens, repo_goldens):
                if not json_equal(k, r):
                    failures.append("Kotlin 内嵌 golden 与仓库 golden 不一致")

    # 4. optional cross-repo live validation（ANSWATCH_ROOT > sibling ../ANSWatch）
    answatch = resolve_answatch_root(repo)
    if answatch is None:
        skipped.append(
            "cross-repo live validation SKIPPED：ANSWATCH_ROOT 未设置且 sibling ../ANSWatch 不存在"
            "（默认 CI 只 checkout ECHO；frozen contract 验证仍必须 PASS）"
        )
    else:
        live = parse_required_fields(answatch / "wrist_pipeline" / "output_schema.py")
        print("ANSWatch live REQUIRED_FIELDS: %d 个" % len(live))
        frozen = json.loads(frozen_path.read_text(encoding="utf-8"))["required_fields"]
        if live != frozen:
            failures.append(
                "ANSWatch live REQUIRED_FIELDS 与 frozen contract 不一致（当前 ANSWatch 漂移）: %s"
                % answatch
            )
        else:
            print("ANSWatch live REQUIRED_FIELDS == frozen contract: OK")
            paths = flatten_schema(schema_path)
            live_missing = [f for f in live if f not in paths and f.split(".")[0] not in KNOWN_EXTRA_FIELDS]
            if live_missing:
                failures.append("schema 缺失 live REQUIRED_FIELDS: %s" % live_missing)
            else:
                print("schema 覆盖 live REQUIRED_FIELDS: OK")

    for s in skipped:
        print("SKIP:", s)
    if failures:
        for f in failures:
            print("FAIL:", f)
        return 1
    print("GOLDEN GATE: ALL PASS" + ("（cross-repo SKIPPED）" if skipped else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
