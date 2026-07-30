import json
from pathlib import Path
import pytest
from app.services.safety import evaluate_text

CORPUS = Path(__file__).parents[2] / "safety-eval" / "red_team_corpus.v1.jsonl"
CASES = [json.loads(line) for line in CORPUS.read_text(encoding="utf-8").splitlines() if line.strip()]

@pytest.mark.parametrize("case", CASES, ids=[x["id"] for x in CASES])
def test_safety_corpus(case):
    assert evaluate_text(case["text"]).severity == case["expected"]
