import pytest
from app.services.scoring import score_gad7, score_phq9


def test_phq9_score_and_urgent_item():
    result = score_phq9([1, 1, 1, 1, 1, 1, 1, 1, 1])
    assert result.score == 9
    assert result.urgent_item is True


def test_gad7_length_validation():
    with pytest.raises(ValueError):
        score_gad7([1, 2])
