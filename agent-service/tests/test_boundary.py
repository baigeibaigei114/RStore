from dataclasses import replace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.errors import AgentError
from app.main import create_app
from app.model import strict_json
from app.schemas import PreparationRequest
from app.service import AgentService

TOKEN = "test-only-internal-token-32-characters-long"


class ModelDouble:
    def __init__(self, output=None):
        self.output = output
        self.calls = 0
        self.last_payload = None

    def configured(self):
        return True

    def generate(self, system, payload):
        self.calls += 1
        self.last_payload = payload
        return self.output, {"usage": {"total_tokens": 1}, "modelRequested": "mock", "modelReturned": "mock"}


def headers():
    return {"X-Internal-Token": TOKEN, "X-Request-Id": str(uuid4())}


def preparation_body():
    return {"schemaVersion": 1, "goal": "分析已保存区域的NDVI"}


def test_internal_auth_and_request_id_before_any_model():
    model = ModelDouble()
    service = AgentService(None, None, model)
    with TestClient(create_app(Settings(internal_token=TOKEN), service)) as client:
        assert client.get("/internal/v1/health").status_code == 401
        assert client.get("/internal/v1/health", headers={"X-Internal-Token": TOKEN}).status_code == 400
        assert client.get("/internal/v1/health", headers=headers()).status_code == 200
        assert client.get("/internal/v1/ready", headers=headers()).status_code == 503
    assert model.calls == 0


@pytest.mark.parametrize("extra", [{"ownerId": "other"}, {"userId": "other"}, {"url": "forbidden"}])
def test_unknown_or_identity_fields_fail_before_model(extra):
    model = ModelDouble()
    with TestClient(create_app(Settings(internal_token=TOKEN), AgentService(None, None, model))) as client:
        response = client.post("/internal/v1/preparation", headers=headers(), json=preparation_body() | extra)
        assert response.status_code == 400
        assert "other" not in response.text
    assert model.calls == 0


@pytest.mark.parametrize("body", ['{"question":"x","question":"y"}', '{"question":NaN}'])
def test_ambiguous_json_fails_closed(body):
    with TestClient(create_app(Settings(internal_token=TOKEN), AgentService(None, None, ModelDouble()))) as client:
        response = client.post("/internal/v1/answers", headers=headers(), content=body)
        assert response.status_code == 400
        assert response.json()["errorCode"] == "JSON_INVALID"


def test_body_limit():
    with TestClient(create_app(Settings(internal_token=TOKEN), AgentService(None, None, ModelDouble()))) as client:
        assert client.post("/internal/v1/answers", headers=headers(), content="x" * 65537).status_code == 413






def test_sensitive_goal_not_sent():
    model = ModelDouble()
    body = preparation_body() | {"goal": "password=never-send-this"}
    with pytest.raises(AgentError, match="凭据"):
        AgentService(None, None, model).preparation(PreparationRequest.model_validate(body))
    assert model.calls == 0


def test_strict_json_rejects_duplicate_and_nonfinite():
    with pytest.raises(ValueError):
        strict_json('{"a":1,"a":2}')
    with pytest.raises(ValueError):
        strict_json('{"a":Infinity}')


@pytest.mark.parametrize("version", [True, 1.0])
def test_protocol_literal_integers_do_not_coerce(version):
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        PreparationRequest.model_validate(preparation_body() | {"schemaVersion": version})


def test_unconfigured_model_does_not_call_provider():
    from app.model import JsonModel
    model = JsonModel(replace(Settings(), enabled=False))
    with pytest.raises(AgentError):
        model.generate("json", {})
    assert model.calls == 0
