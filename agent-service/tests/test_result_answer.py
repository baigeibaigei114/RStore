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
