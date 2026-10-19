"""G2 review regressions. Every provider/retrieval value below is synthetic."""
from copy import deepcopy
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import Settings
from app.errors import AgentError
from app.main import create_app
from app.schemas import DecisionRequest, GeneratedExplanation
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


def explanation_body():
    return {"schemaVersion": 1, "operationId": str(uuid4()), "taskType": "NDVI",
            "resultMetadata": {"schemaVersion": 2, "algorithm": "NDVI", "scope": "monitoring_region",
                "bandMapping": {"redBand": 3, "nirBand": 4}, "coverageRatio": 1.0,
                "statistics": {"totalPixelCount": 10, "validPixelCount": 8, "invalidPixelCount": 2,
                    "validPixelRatio": 0.8, "min": -0.3, "max": 0.5, "mean": 0.0},
                "regionSnapshot": {"schemaVersion": 1, "regionId": 1, "name": "合成区域", "version": 2,
                    "crs": "EPSG:4326", "pixelRule": "center_covered",
                    "geometry": {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 0]]]}}},
            "publicImageSummary": {"sensor": "Sentinel-2", "captureTime": "2024-11-29T10:30:00+08:00"},
            "promptVersion": "ndvi-explanation-v1"}


def insufficient():
    return {"summary": "知识库中没有足够信息", "claims": [], "limitations": [], "insufficientEvidence": True}


def supported():
    text = "有效比例不是植被覆盖率"
    return {"answer": text, "claims": [{"text": text, "citationIds": ["C1"],
            "evidenceQuotes": [{"citationId": "C1", "quote": text}]}], "insufficientEvidence": False}


def decision_body():
    return {"schemaVersion": 1, "operationId": str(uuid4()), "goal": "分析区域NDVI", "remainingCalls": 3,
            "observations": [], "selectionContext": {
                "imageCandidates": [{"id": "31", "name": "合成影像", "sensor": "Sentinel-2",
                                     "captureTime": "2024-11-29T10:30:00Z"}],
                "regionCandidates": [{"id": "1", "name": "合成区域", "version": 2}]}}


def proposal():
    return {"kind": "PROPOSE_NDVI", "imageId": "i1", "monitoringRegionId": "r1",
            "params": {"redBand": 3, "nirBand": 4}}


@pytest.mark.parametrize("limitation", ["普通自由限制", "均值为0.99", "证明农田健康", "D:/private/result.tif"])
def test_insufficient_limitations_rejected_by_schema_function_and_http(limitation):
    output = insufficient() | {"limitations": [limitation]}
    with pytest.raises(ValidationError):
        GeneratedExplanation.model_validate(output)
    # Exercise the function invariant independently of schema validation.
    with pytest.raises(AgentError):
        AgentService.validate_grounding(GeneratedExplanation.model_construct(**output), CITATIONS, True)
    model = Model(output)
    with TestClient(create_app(Settings(internal_token=TOKEN), RetrievalService(None, None, model))) as client:
        response = client.post("/internal/v1/explanations", headers=headers(), json=explanation_body())
    assert response.status_code == 503
    assert response.json()["errorCode"] == "MODEL_OUTPUT_INVALID"
    assert response.json()["trace"]["usage"] == {"total_tokens": 77}
    assert limitation not in response.text
    assert "taskId" not in response.json() and "action" not in response.json()
    assert model.calls == 1


@pytest.mark.parametrize("enough", [False, True])
def test_empty_limitations_valid_for_both_explanation_branches(enough):
    output = insufficient()
    if enough:
        answer = supported()
        output = {"summary": answer["answer"], "claims": answer["claims"],
                  "limitations": [], "insufficientEvidence": False}
    AgentService.validate_grounding(GeneratedExplanation.model_validate(output), CITATIONS, True)
    model = Model(output)
    with TestClient(create_app(Settings(internal_token=TOKEN), RetrievalService(None, None, model))) as client:
        response = client.post("/internal/v1/explanations", headers=headers(), json=explanation_body())
    assert response.status_code == 200 and response.json()["limitations"] == []
    assert response.json()["insufficientEvidence"] is not enough
    assert model.calls == 1


@pytest.mark.parametrize("context_name", ["selectionContext", "observations"])
@pytest.mark.parametrize("field", ["imageName", "sensor", "captureTime", "regionName"])
def test_all_candidate_text_rejected_before_provider_without_echo(context_name, field):
    body = decision_body()
    context = body["selectionContext"]
    if context_name == "observations":
        body["observations"] = [context]
        body["selectionContext"] = {}
    if field == "regionName":
        context["regionCandidates"][0]["name"] = SENTINEL
    else:
        context["imageCandidates"][0]["name" if field == "imageName" else field] = SENTINEL
    model = Model(proposal())
    with TestClient(create_app(Settings(internal_token=TOKEN), AgentService(None, None, model))) as client:
        response = client.post("/internal/v1/decisions", headers=headers(), json=body)
    assert response.status_code == 400
    assert SENTINEL not in response.text
    assert model.calls == 0 and model.payload is None


def test_sensitive_overwritten_observation_is_still_rejected():
    body = decision_body()
    body["observations"] = [deepcopy(body["selectionContext"])]
    body["selectionContext"]["imageCandidates"][0]["sensor"] = SENTINEL
    model = Model(proposal())
    with pytest.raises(AgentError):
        AgentService(None, None, model).decision(DecisionRequest.model_validate(body))
    assert model.calls == 0


@pytest.mark.parametrize("value", ["2024-11-29", "2024-11-29T10:30:00", "2024-02-30T10:30:00Z",
                                   "2024-11-29 10:30:00Z", "x" * 51])
@pytest.mark.parametrize("route", ["decisions", "explanations"])
def test_capture_time_bounded_iso_with_timezone_before_provider(value, route):
    body = decision_body() if route == "decisions" else explanation_body()
    image = body["selectionContext"]["imageCandidates"][0] if route == "decisions" else body["publicImageSummary"]
    image["captureTime"] = value
    model = Model(proposal())
    with TestClient(create_app(Settings(internal_token=TOKEN), RetrievalService(None, None, model))) as client:
        response = client.post(f"/internal/v1/{route}", headers=headers(), json=body)
    assert response.status_code == 400 and value not in response.text
    assert model.calls == 0


@pytest.mark.parametrize("observed", [False, True])
def test_candidate_allowlist_and_normal_handle_flow(observed):
    body = decision_body()
    if observed:
        body["observations"] = [body["selectionContext"]]
        body["selectionContext"] = {}
    model = Model(proposal())
    output = AgentService(None, None, model).decision(DecisionRequest.model_validate(body))
    assert output["action"]["imageId"] == "31" and output["action"]["monitoringRegionId"] == "1"
    assert model.payload["imageCandidates"] == [{"handle": "i1", "name": "合成影像", "sensor": "Sentinel-2",
                                                "captureTime": "2024-11-29T10:30:00Z", "admission": "PASSED"}]
    assert model.payload["regionCandidates"] == [{"handle": "r1", "name": "合成区域", "version": 2}]
    assert model.calls == 1


@pytest.mark.parametrize("case", ["unknown_handle", "multiple_candidates", "selection_mismatch", "decision_schema",
    "answer_schema", "explanation_schema", "unknown_citation", "quote", "quote_set", "summary", "no_claims",
    "answer_metric", "insufficient_claim", "explanation_number", "explanation_limit"])
def test_all_post_model_rejections_preserve_known_usage_without_echo(case):
    route, body, output = "answers", {"schemaVersion": 1, "question": "有效比例含义"}, supported()
    if case in {"unknown_handle", "multiple_candidates", "selection_mismatch", "decision_schema"}:
        route, body, output = "decisions", decision_body(), proposal()
        if case == "unknown_handle":
            output["imageId"] = "i9"
        elif case in {"multiple_candidates", "selection_mismatch"}:
            body["selectionContext"]["imageCandidates"].append({"id": "32", "name": "另一合成影像"})
            if case == "selection_mismatch":
                body["selectionContext"]["selectedImageId"] = "32"
        else:
            output["kind"] = SENTINEL
    elif case in {"explanation_schema", "explanation_number", "explanation_limit"}:
        route, body = "explanations", explanation_body()
        output = {"summary": output.pop("answer"), **output, "limitations": []}
        if case == "explanation_schema":
            output["claims"] = SENTINEL
        elif case == "explanation_number":
            output["summary"] = output["claims"][0]["text"] = "均值为0.99"
        else:
            output["limitations"] = [SENTINEL]
    elif case == "answer_schema":
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
