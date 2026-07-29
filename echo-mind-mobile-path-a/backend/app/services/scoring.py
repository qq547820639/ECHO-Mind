from dataclasses import dataclass


@dataclass(frozen=True)
class ScoreResult:
    score: int
    interpretation: str
    urgent_item: bool = False


def _validate(answers: list[int], expected: int) -> None:
    if len(answers) != expected:
        raise ValueError(f"expected {expected} answers")
    if any(value not in (0, 1, 2, 3) for value in answers):
        raise ValueError("answers must be 0..3")


def score_phq9(answers: list[int]) -> ScoreResult:
    _validate(answers, 9)
    score = sum(answers)
    if score <= 4:
        label = "当前筛查分数较低"
    elif score <= 9:
        label = "存在一些相关困扰，建议持续观察"
    elif score <= 14:
        label = "建议联系专业人员进一步了解"
    else:
        label = "建议尽快联系专业人员进一步了解"
    return ScoreResult(score, label, urgent_item=answers[8] > 0)


def score_gad7(answers: list[int]) -> ScoreResult:
    _validate(answers, 7)
    score = sum(answers)
    if score <= 4:
        label = "当前筛查分数较低"
    elif score <= 9:
        label = "存在一些相关困扰，建议持续观察"
    elif score <= 14:
        label = "建议联系专业人员进一步了解"
    else:
        label = "建议尽快联系专业人员进一步了解"
    return ScoreResult(score, label)
