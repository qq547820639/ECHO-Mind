"""确定性中文叙事模板（Milestone D + Phase 5, C4）。

- 每维度取值映射固定句子；summary 按 RHYTHM→MOVEMENT→SCREEN_AMOUNT→SCREEN_TIMING→
  DAY_STRUCTURE→STABILITY 顺序 join；
- headline 最多 3 个两字标签；
- Phase 5（C4）：SCREEN_PATTERN 拆分为 SCREEN_AMOUNT / SCREEN_TIMING 两个独立维度；
  RHYTHM 不再输出 IRREGULAR（missing != irregular，由维度省略表达）；
- EARLY_BASELINE 只输出当天事实句（不输出"比平常"）；
- LOW_CONFIDENCE/WARMING_UP 不生成 narrative（由 engine 给固定文案）。

产品契约：所有句子不含被禁止词（焦虑/抑郁/孤独/压力过大/心理异常/风险/精神疾病/社交退缩）。
"""
from __future__ import annotations

RHYTHM_SENTENCES = {
    "EARLIER": "今天开始活跃的时间比你最近的习惯早",
    "LATER": "今天开始活跃的时间比你最近的习惯稍晚",
    "SIMILAR": "今天的作息时间和你最近的习惯比较接近",
}
MOVEMENT_SENTENCES = {
    "LESS": "白天整体移动也少了一些",
    "MORE": "白天整体移动比平常多一些",
    "SIMILAR": "白天的移动情况和你的平常比较接近",
}
SCREEN_AMOUNT_SENTENCES = {
    "LESS": "屏幕互动比平常少一些",
    "MORE": "屏幕互动比平常多一些",
    "SIMILAR": "屏幕互动时长和你的平常比较接近",
}
SCREEN_TIMING_SENTENCES = {
    "EARLIER": "晚间屏幕互动比通常更早结束",
    "SIMILAR": "晚间屏幕使用时间和你的平常比较接近",
    "LATER": "晚间屏幕互动比通常集中",
}
DAY_STRUCTURE_SENTENCES = {
    "MORE_CONCENTRATED": "今天的行为比较集中",
    "SIMILAR": "今天的行为分布和平时差不多",
    "MORE_FRAGMENTED": "今天的行为比较零散",
}
STABILITY_SENTENCES = {
    "VERY_SIMILAR": "整体来看，今天和通常的你非常接近",
    "SLIGHTLY_DIFFERENT": "整体来看，今天和通常的你有一些小变化",
    "CLEARLY_DIFFERENT": "整体来看，今天和通常的你相比有明显变化",
}

_SENTENCE_MAP = {
    "RHYTHM": RHYTHM_SENTENCES,
    "MOVEMENT": MOVEMENT_SENTENCES,
    "SCREEN_AMOUNT": SCREEN_AMOUNT_SENTENCES,
    "SCREEN_TIMING": SCREEN_TIMING_SENTENCES,
    "DAY_STRUCTURE": DAY_STRUCTURE_SENTENCES,
    "STABILITY": STABILITY_SENTENCES,
}

HEADLINE_MAP = {
    ("RHYTHM", "LATER"): "偏晚",
    ("RHYTHM", "EARLIER"): "偏早",
    # Phase 6.2（Psychology Review）：第一版用户语言优先——
    # "安静/活跃/稳定" 有心理暗示歧义（REWRITE），替换为行为观察措辞：
    ("MOVEMENT", "LESS"): "移动较少",
    ("MOVEMENT", "MORE"): "移动较多",
    ("SCREEN_AMOUNT", "MORE"): "多屏",
    ("SCREEN_AMOUNT", "LESS"): "少屏",
    ("SCREEN_TIMING", "LATER"): "晚屏",
    ("STABILITY", "VERY_SIMILAR"): "接近",
    ("STABILITY", "SLIGHTLY_DIFFERENT"): "小变化",
    ("STABILITY", "CLEARLY_DIFFERENT"): "变化明显",
}

#: summary 句子顺序
DIMENSION_ORDER = (
    "RHYTHM",
    "MOVEMENT",
    "SCREEN_AMOUNT",
    "SCREEN_TIMING",
    "DAY_STRUCTURE",
    "STABILITY",
)

WARMING_UP_TEXT = "ECHO 正在慢慢了解你的日常节奏。再积累几天，就能开始比较“今天”和“平常的你”。"
LOW_CONFIDENCE_TEXT = "今天的数据还不够完整，暂时看不出和你平时相比有什么可靠变化。"
PARTIAL_DATA_PREFIX = "今天的数据还不完整，以下画像仅反映已经采集到的部分。 "


def sentence_for(dimension: str, value: str) -> str:
    return _SENTENCE_MAP[dimension][value]


def build_narrative(dimensions: dict) -> tuple[str, list[str]]:
    """按固定顺序生成 (summary, headline)。headline 最多 3 个两字标签。"""
    sentences: list[str] = []
    headline: list[str] = []
    for dim in DIMENSION_ORDER:
        entry = dimensions.get(dim)
        if not entry:
            continue
        value = entry["value"]
        sentences.append(sentence_for(dim, value))
        tag = HEADLINE_MAP.get((dim, value))
        if tag and tag not in headline:
            headline.append(tag)
    summary = "。".join(sentences) + "。" if sentences else ""
    return summary, headline[:3]


def fact_sentences(today: dict) -> str:
    """EARLY_BASELINE 事实句：只用当天 aggregate 值，不输出"比平常"。"""
    parts: list[str] = []
    screen = today.get("screen_on_minutes") or 0.0
    if screen > 0:
        parts.append(f"今天累计屏幕互动 {int(round(screen))} 分钟")
    notifications = today.get("notification_count") or 0
    if notifications > 0:
        parts.append(f"今天收到 {notifications} 条通知")
    switches = today.get("app_switch_count") or 0
    if switches > 0:
        parts.append(f"今天切换应用 {switches} 次")
    return "。".join(parts) + "。" if parts else ""
