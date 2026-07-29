from dataclasses import dataclass
import re

RULE_PACK_VERSION = "safety-rules-2026.07.2"

# Risk bypass rules only; not diagnosis. Production release requires clinical/safety/legal/quality sign-off.
# Patterns intentionally avoid storing method detail and focus on intent, plan, immediacy, danger and loss of safety.
RED_PATTERNS = [
    r"我.{0,5}(想死|不想活|结束生命|不想醒来)",
    r"(准备|已经准备|都准备好).{0,12}(工具|遗书|告别|后事)",
    r"(今晚|今天|现在|马上|待会).{0,12}(结束|去死|伤害自己|不活)",
    r"(想|准备|打算).{0,8}(伤害|杀死).{0,6}(别人|他人|某人|家人)",
    r"(无法|不能|不敢)保证.{0,5}(自己)?安全",
    r"(站在高处|正在驾驶|身边有危险物).{0,10}(控制不住|想结束|不安全)",
    r"(有计划|已经计划|具体计划).{0,12}(自杀|自伤|伤害自己|伤害他人)",
]
YELLOW_PATTERNS = [
    r"没有希望|彻底绝望|活着没意义|消失就好了",
    r"我是.{0,6}负担|拖累所有人|想和大家告别|突然告别",
    r"有人命令我|声音让我去做|被监视|他们.{0,2}要害我",
    r"不用睡|连续几天不睡|我无所不能|精力无限",
    r"只有你理解我|你不能离开我|不要告诉任何人",
    r"(停药|加药|减药|改剂量).{0,8}(可以吗|怎么做|帮我|吗)|帮我.{0,5}(停药|加药|减药|改剂量)",
]
EXIT_PATTERNS = [r"^退出[。.!！]?$", r"^停止[。.!！]?$", r"不想聊了", r"结束对话", r"别再问了"]
NEGATION_GUARDS = [r"没有.{0,3}(想死|自伤|伤害自己)", r"不想.{0,3}伤害自己", r"不会.{0,3}伤害自己"]


@dataclass(frozen=True)
class SafetyResult:
    severity: str
    labels: list[str]
    matched_rule_ids: list[str]
    freeze_generation: bool
    script_key: str | None


def _normalize(text: str) -> str:
    return re.sub(r"[\s，,。.!！?？、]", "", text.lower())


def _match(text: str, patterns: list[str], prefix: str) -> list[str]:
    normalized = _normalize(text)
    return [f"{prefix}-{index:03d}" for index, pattern in enumerate(patterns, 1)
            if re.search(pattern, normalized, flags=re.IGNORECASE)]


_RULE_PACKS = {
    "RED": RED_PATTERNS,
    "YELLOW": YELLOW_PATTERNS,
    "EXIT": EXIT_PATTERNS,
    "GUARD": NEGATION_GUARDS,
}


def resolve_rule_ids(rule_ids: list[str]) -> list[dict]:
    """Map stored rule identifiers (e.g. "RED-003") back to their pack patterns.

    Keeps rule-hit evidence traceable: a reviewer can see exactly which pattern
    in which rule pack fired, instead of an opaque id. Synthetic ids that do not
    map to a pack pattern (e.g. "YELLOW-NEGATED-RISK") resolve to pattern=None.
    """
    resolved = []
    for rule_id in rule_ids:
        prefix, _, suffix = rule_id.rpartition("-")
        patterns = _RULE_PACKS.get(prefix)
        if patterns and suffix.isdigit() and 1 <= int(suffix) <= len(patterns):
            resolved.append({"rule_id": rule_id, "pattern": patterns[int(suffix) - 1]})
        else:
            resolved.append({"rule_id": rule_id, "pattern": None})
    return resolved


def evaluate_text(text: str) -> SafetyResult:
    exit_ids = _match(text, EXIT_PATTERNS, "EXIT")
    if exit_ids:
        return SafetyResult("exit", ["user_exit"], exit_ids, True, "end_session")
    red_ids = _match(text, RED_PATTERNS, "RED")
    guard_ids = _match(text, NEGATION_GUARDS, "GUARD")
    if red_ids and not guard_ids:
        return SafetyResult("red", ["immediate_safety_risk"], red_ids, True, "l2_stabilization")
    yellow_ids = _match(text, YELLOW_PATTERNS, "YELLOW")
    if guard_ids:
        yellow_ids += ["YELLOW-NEGATED-RISK"]
    if yellow_ids:
        labels = ["needs_human_review"]
        if any(x in text for x in ("只有你", "不能离开", "不要告诉")):
            labels.append("dependency_risk")
        return SafetyResult("yellow", labels, yellow_ids, False, "yellow_check")
    return SafetyResult("none", [], [], False, None)
