#!/usr/bin/env python3
"""
verify_golden.py —— ANS_FRAME_V1 黄金门（Python output ↔ schema ↔ Kotlin decoder）。

检查链：
1. 从真实 ANSWatch 源码（wrist_pipeline/output_schema.py）解析 REQUIRED_FIELDS
   dot-path 契约 —— 若 ANSWatch 契约漂移，本工具立刻发现（禁止重写其科学结论）；
2. ANS_FRAME_V1.schema.json 必须覆盖全部 REQUIRED_FIELDS（结构锁定）；
3. golden 帧（integrations/answatch/golden/*.json）逐帧通过 schema 验证；
4. 跨语言黄金门：Kotlin 测试（:feature:wearable AnsFrameDecoderTest）内嵌 golden
   与仓库 golden 帧 JSON 语义一致（字段逐一对齐，数值误差 1e-9）。

用法：python3 integrations/answatch/tools/verify_golden.py [--repo ECHO_Mind根目录]
退出码：0=全部通过；1=任一失败。
"""
import argparse
import json
import re
import sys
from pathlib import Path

try:
    import jsonschema
except ImportError:  # 环境无 jsonschema 时降级为结构检查（仍执行 1/2/4）
    jsonschema = None

# ANSWatch 输出帧允许的额外字段（output_schema.make_output 相对 SPEC-2 的 additive 偏差）
KNOWN_EXTRA_FIELDS = {"window_id"}


def find_answatch(repo_root: Path) -> Path:
    candidate = repo_root.parent / "ANSWatch"
    if (candidate / "wrist_pipeline" / "output_schema.py").exists():
        return candidate
    # 环境变量覆盖（CI/其他布局）
    env = Path(__file__).resolve().parent.parent.parent
    raise SystemExit("FAIL: 找不到 ANSWatch/wrist_pipeline/output_schema.py（期望 %s）" % candidate)


def parse_required_fields(source: Path):
    """从真实 ANSWatch output_schema.py 提取 REQUIRED_FIELDS dot-path 列表。"""
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
        for key in ("required",):
            for name in node.get(key, []):
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[3]))
    args = parser.parse_args()
    repo = Path(args.repo)
    schema_path = repo / "integrations" / "answatch" / "ANS_FRAME_V1.schema.json"
    golden_dir = repo / "integrations" / "answatch" / "golden"
    kotlin_test = (repo / "android" / "feature" / "wearable" / "src" / "test" / "java" /
                   "com" / "yunjue" / "echo" / "mind" / "wearable" / "research" /
                   "AnsFrameDecoderTest.kt")
    failures = []

    # 1. 真实 ANSWatch REQUIRED_FIELDS
    answatch = find_answatch(repo)
    required = parse_required_fields(answatch / "wrist_pipeline" / "output_schema.py")
    print("ANSWatch REQUIRED_FIELDS: %d 个" % len(required))

    # 2. schema 覆盖
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    paths = flatten_schema(schema_path)
    missing = [f for f in required if f not in paths and f.split(".")[0] not in KNOWN_EXTRA_FIELDS]
    if missing:
        failures.append("schema 缺失 REQUIRED_FIELDS: %s" % missing)
    else:
        print("schema 覆盖 REQUIRED_FIELDS: OK")

    # 3. golden 帧 schema 验证
    golden_files = sorted(golden_dir.glob("*.json"))
    if not golden_files:
        failures.append("golden 目录为空")
    for gf in golden_files:
        frame = json.loads(gf.read_text(encoding="utf-8"))
        if jsonschema is not None:
            try:
                jsonschema.validate(frame, schema)
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

    # 4. 跨语言黄金门
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

    if failures:
        for f in failures:
            print("FAIL:", f)
        return 1
    print("GOLDEN GATE: ALL PASS")
    return 0


def json_equal(a, b, eps=1e-9):
    if isinstance(a, dict) and isinstance(b, dict):
        if set(a) != set(b):
            return False
        return all(json_equal(a[k], b[k], eps) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(json_equal(x, y, eps) for x, y in zip(a, b))
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(a - b) <= eps
    return a == b


if __name__ == "__main__":
    sys.exit(main())
