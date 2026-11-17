"""G2 review regressions. Every provider/retrieval value below is synthetic."""
from copy import deepcopy
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.service import AgentService

TOKEN = "test-only-internal-token-32-characters-long"
SENTINEL = "password=review-only-synthetic-sentinel"
TRACE = {"usage": {"total_tokens": 77}, "modelRequested": "mock", "modelReturned": "mock", "elapsedMs": 5}
CITATIONS = {"citations": [{"citationId": "C1", "excerpt": "有效比例不是植被覆盖率。"}]}


class Model:
    def __init__(self, output):
        self.output = output
        self.calls = 0
        self.payload = None

    def generate(self, system, payload):
        self.calls += 1
        self.payload = payload
        return self.output, deepcopy(TRACE)


class RetrievalService(AgentService):
    def retrieve(self, question):
        return deepcopy(CITATIONS)


def headers():
    return {"X-Internal-Token": TOKEN, "X-Request-Id": str(uuid4())}


def supported():
    text = "有效比例不是植被覆盖率"
    return {"answer": text, "claims": [{"text": text, "citationIds": ["C1"],
            "evidenceQuotes": [{"citationId": "C1", "quote": text}]}], "insufficientEvidence": False}


@pytest.mark.parametrize("case", ["answer_schema", "unknown_citation", "quote", "quote_set", "summary", "no_claims",
    "answer_metric", "insufficient_claim"])
def test_all_post_model_rejections_preserve_known_usage_without_echo(case):
    route, body, output = "answers", {"schemaVersion": 1, "question": "有效比例含义"}, supported()
    if case == "answer_schema":
        output["claims"] = SENTINEL
    elif case == "unknown_citation":
        output["claims"][0]["citationIds"] = ["C2"]
    elif case == "quote":
        output["claims"][0]["evidenceQuotes"][0]["quote"] = SENTINEL
    elif case == "quote_set":
        output["claims"][0]["citationIds"] = ["C1", "C2"]
    elif case == "summary":
        output["answer"] = SENTINEL
    elif case == "no_claims":
        output["claims"] = []
    elif case == "answer_metric":
        output["claims"][0]["metricRefs"] = ["statistics.mean"]
    else:
        output["insufficientEvidence"] = True
    model = Model(output)
    with TestClient(create_app(Settings(internal_token=TOKEN), RetrievalService(None, None, model))) as client:
        response = client.post(f"/internal/v1/{route}", headers=headers(), json=body)
    assert response.status_code == 503
    trace = response.json()["trace"]
    assert all(trace[key] == value for key, value in TRACE.items())
    assert SENTINEL not in response.text
    assert model.calls == 1
