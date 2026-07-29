from app.services.safety import evaluate_text


def test_red_rule_freezes_generation():
    result = evaluate_text("我已经准备好工具，今晚结束生命")
    assert result.severity == "red"
    assert result.freeze_generation is True
    assert result.script_key == "l2_stabilization"


def test_exit_is_deterministic():
    result = evaluate_text("退出")
    assert result.severity == "exit"
    assert result.freeze_generation is True


def test_normal_text():
    assert evaluate_text("今天有点累，想做一个呼吸练习").severity == "none"
