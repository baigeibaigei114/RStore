import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.service import AgentService
from tests.test_boundary import TOKEN, ModelDouble, headers


@pytest.mark.parametrize(("output", "status"), [
    ({"kind": "VALID_PIXELS"}, 200),
    ({"kind": "DELETE_TASK"}, 503),
    ({"kind": "MEAN", "value": 99}, 503),
    ({"kind": "SECTION"}, 503),
    ({"kind": "TOP_SECTION", "section": "NE"}, 503),
])
def test_result_question_returns_only_a_valid_answer_kind(output, status):
    model = ModelDouble(output)
    service = AgentService(None, None, model)
    question = "有多少像素参与统计？"
    with TestClient(create_app(Settings(internal_token=TOKEN), service)) as client:
        response = client.post("/internal/v1/result-answers", headers=headers(), json={"question": question})
    assert response.status_code == status
    assert model.last_payload == {"question": question}
    if status == 200:
        assert response.json()["kind"] == "VALID_PIXELS"


def test_follow_up_receives_only_the_previous_verified_topic():
    model = ModelDouble({"kind": "FOLLOW_UP"})
    service = AgentService(None, None, model)
    body = {"question": "它的分母是多少？", "previousKind": "VALID_RATIO"}
    with TestClient(create_app(Settings(internal_token=TOKEN), service)) as client:
        response = client.post("/internal/v1/result-answers", headers=headers(), json=body)
    assert response.status_code == 200
    assert response.json()["kind"] == "FOLLOW_UP"
    assert model.last_payload == body


def test_section_selection_returns_only_the_named_partition():
    model = ModelDouble({"kind": "SECTION", "section": "SE"})
    service = AgentService(None, None, model)
    body = {"question": "右下呢？", "previousSection": "NW"}
    with TestClient(create_app(Settings(internal_token=TOKEN), service)) as client:
        response = client.post("/internal/v1/result-answers", headers=headers(), json=body)
    assert response.status_code == 200
    assert response.json()["section"] == "SE"
    assert model.last_payload == body


def test_ndwi_classifier_receives_the_algorithm_without_source_data():
    model = ModelDouble({"kind": "WATER_AREA_UNAVAILABLE"})
    service = AgentService(None, None, model)
    body = {"question": "水体面积是多少？", "algorithm": "NDWI"}
    with TestClient(create_app(Settings(internal_token=TOKEN), service)) as client:
        response = client.post("/internal/v1/result-answers", headers=headers(), json=body)
    assert response.status_code == 200
    assert response.json()["kind"] == "WATER_AREA_UNAVAILABLE"
    assert model.last_payload == body


def test_change_classifier_only_selects_an_intent_from_the_current_question():
    model = ModelDouble({"kind": "CHANGE_RATIO"})
    service = AgentService(None, None, model)
    body = {"question": "变化比例是多少？", "algorithm": "CHANGE_DETECTION"}
    with TestClient(create_app(Settings(internal_token=TOKEN), service)) as client:
        response = client.post("/internal/v1/result-answers", headers=headers(), json=body)
    assert response.status_code == 200
    assert response.json()["kind"] == "CHANGE_RATIO"
    assert model.last_payload == body
