from types import SimpleNamespace

import pytest

from app.config import Settings
from app.errors import AgentError
from app.model import JsonModel


def settings(**extra):
    return Settings(enabled=True, deepseek_key="test-only-not-a-real-credential", **extra)


def mock_adapter(monkeypatch, content='{"ok":true}', finish="stop", failure=None, usage=None, name="mock"):
    captured = {}

    class Adapter:
        def __init__(self, **kwargs):
            captured.update(kwargs)

        def bind(self, **kwargs):
            captured["bind"] = kwargs
            return self

        def invoke(self, messages, **kwargs):
            if failure:
                raise failure
            return SimpleNamespace(content=content, response_metadata={"finish_reason": finish,
                "model_name": name, "reasoning_content": "must-not-be-saved"},
                usage_metadata=usage if usage is not None else {"total_tokens": 3})

    monkeypatch.setattr("app.model.ChatDeepSeek", Adapter)
    return captured


def test_json_nonthinking_zero_retry_and_no_hidden_trace(monkeypatch):
    captured = mock_adapter(monkeypatch)
    model = JsonModel(settings())
    value, trace = model.generate("return json", {})
    assert value == {"ok": True}
    assert "must-not-be-saved" not in str(trace)
    assert captured["max_retries"] == 0
    assert captured["bind"] == {"response_format": {"type": "json_object"}, "extra_body": {"thinking": {"type": "disabled"}}}
    assert model.calls == 1


@pytest.mark.parametrize("content,finish", [("", "stop"), ('{"a":1,"a":2}', "stop"), ('{"ok":true}', "length")])
def test_invalid_or_incomplete_json_rejected(monkeypatch, content, finish):
    mock_adapter(monkeypatch, content=content, finish=finish)
    with pytest.raises(AgentError) as error:
        JsonModel(settings()).generate("json", {})
    assert error.value.code == "MODEL_OUTPUT_INVALID"


def test_model_timeout_safe_error_no_retry(monkeypatch):
    mock_adapter(monkeypatch, failure=RuntimeError("private-prompt-and-key"))
    model = JsonModel(settings())
    with pytest.raises(AgentError) as error:
        model.generate("json", {})
    assert model.calls == 1 and error.value.retryable
    assert "private" not in str(error.value)
    assert error.value.trace["usage"] is None
    assert error.value.trace["modelReturned"] is None
    assert "private" not in str(error.value.trace)


def test_busy_and_input_budget_do_not_call_model(monkeypatch):
    mock_adapter(monkeypatch)
    model = JsonModel(settings(max_concurrent_models=1))
    model.slots.acquire()
    with pytest.raises(AgentError) as error:
        model.generate("json", {})
    assert error.value.code == "MODEL_BUSY" and model.calls == 0
    model.slots.release()
    with pytest.raises(AgentError) as error:
        model.generate("json", {"text": "x" * 25000})
    assert error.value.code == "MODEL_INPUT_TOO_LARGE" and model.calls == 0


@pytest.mark.parametrize("content,finish", [
    ('{"private":"password=synthetic-only"', "stop"),
    ('{"a":1,"a":2}', "stop"), ('{"a":NaN}', "stop"),
    ('{"ok":true}', "length"), ([{"private": "password=synthetic-only"}], "stop"),
])
def test_known_usage_survives_json_and_finish_rejection(monkeypatch, content, finish):
    mock_adapter(monkeypatch, content=content, finish=finish, usage={"total_tokens": 77})
    model = JsonModel(settings())
    with pytest.raises(AgentError) as error:
        model.generate("json", {})
    assert error.value.trace["usage"] == {"total_tokens": 77}
    assert error.value.trace["modelReturned"] == "mock"
    assert error.value.trace["elapsedMs"] >= 0
    assert "synthetic-only" not in str(error.value.trace) + str(error.value)
    assert model.calls == 1


def test_trace_whitelist_drops_arbitrary_metadata_and_invalid_counters(monkeypatch):
    mock_adapter(monkeypatch, name="password=synthetic-only", usage={
        "total_tokens": 77, "input_tokens": True, "output_tokens": -1,
        "private": "password=synthetic-only", "prompt": "synthetic-only",
        "input_token_details": {"cache_read": 12, "text": "synthetic-only", "private": "synthetic-only"},
        "output_token_details": {"reasoning": 0, "private": "synthetic-only"},
    })
    value, trace = JsonModel(settings()).generate("json", {})
    assert value == {"ok": True} and trace["modelReturned"] is None
    assert trace["usage"] == {"total_tokens": 77, "input_token_details": {"cache_read": 12},
                              "output_token_details": {"reasoning": 0}}
    assert "synthetic-only" not in str(trace)
