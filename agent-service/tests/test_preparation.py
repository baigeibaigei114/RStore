from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.service import AgentService
from tests.test_boundary import TOKEN, ModelDouble, headers


def test_preparation_only_extracts_intent_and_never_receives_private_resources():
    intent = {"kind": "REGION_NDVI", "regionName": "样区", "startDate": "2024-11-01", "endDate": "2024-11-30"}
    model = ModelDouble(intent)
    with TestClient(create_app(Settings(internal_token=TOKEN), AgentService(None, None, model))) as client:
        response = client.post("/internal/v1/preparation", headers=headers(),
                               json={"schemaVersion": 1, "goal": "样区十一月植被情况"})
    assert response.status_code == 200
    assert response.json()["intent"] == intent
    assert model.calls == 1
    assert model.last_payload == {"goal": "样区十一月植被情况"}


@pytest.mark.parametrize("output", [
    {"kind": "SUBMIT_TASK", "taskId": "7"},
    {"kind": "REGION_NDVI", "imageId": "7"},
    {"kind": "REGION_NDVI", "startDate": "2024/11/01"},
])
def test_invalid_model_intent_keeps_safe_usage_trace(output):
    model = ModelDouble(output)
    with TestClient(create_app(Settings(internal_token=TOKEN), AgentService(None, None, model))) as client:
        response = client.post("/internal/v1/preparation", headers=headers(),
                               json={"schemaVersion": 1, "goal": "区域NDVI"})
    assert response.status_code == 503
    assert response.json()["trace"]["usage"] == {"total_tokens": 1}
    assert model.calls == 1


@pytest.mark.parametrize("endpoint", ["decisions", "explanations", "result-answers"])
def test_retired_internal_routes_do_not_call_provider(endpoint):
    model = ModelDouble()
    with TestClient(create_app(Settings(internal_token=TOKEN), AgentService(None, None, model))) as client:
        response = client.post(f"/internal/v1/{endpoint}", headers=headers(), json={"operationId": str(uuid4())})
    assert response.status_code == 404
    assert model.calls == 0
