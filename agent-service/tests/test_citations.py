import pytest

from app.errors import AgentError
from app.schemas import GeneratedAnswer, GeneratedExplanation, Statistics
from app.service import AgentService


def retrieval():
    return {"citations": [{"citationId": "C1", "excerpt": "有效比例不是植被覆盖率。"}]}


def answer():
    return {"answer": "有效比例不是植被覆盖率", "insufficientEvidence": False,
            "claims": [{"text": "有效比例不是植被覆盖率", "citationIds": ["C1"],
                        "evidenceQuotes": [{"citationId": "C1", "quote": "有效比例不是植被覆盖率"}]}]}


def test_valid_quote_resolves_to_returned_excerpt():
    AgentService.validate_grounding(GeneratedAnswer.model_validate(answer()), retrieval())


@pytest.mark.parametrize("change", ["unknown", "quote", "summary", "unsupported_claim", "insufficient"])
def test_invalid_reference_or_unbound_claim_rejected(change):
    output = answer()
    if change == "unknown":
        output["claims"][0]["citationIds"] = ["C99"]
    elif change == "quote":
        output["claims"][0]["evidenceQuotes"][0]["quote"] = "植被一定健康"
    elif change == "summary":
        output["answer"] += "，说明一定健康"
    elif change == "unsupported_claim":
        output["claims"][0]["citationIds"] = ["C1", "C2"]
    else:
        output["insufficientEvidence"] = True
    with pytest.raises(AgentError):
        AgentService.validate_grounding(GeneratedAnswer.model_validate(output), retrieval())


def test_zero_mean_is_valid_but_missing_or_bad_statistics_is_not():
    stats = {"totalPixelCount": 10, "validPixelCount": 8, "invalidPixelCount": 2,
             "validPixelRatio": 0.8, "min": -0.3, "mean": 0.0, "max": 0.5}
    assert Statistics.model_validate(stats).mean == 0
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        Statistics.model_validate(stats | {"mean": float("nan")})
    with pytest.raises(ValidationError):
        Statistics.model_validate(stats | {"validPixelCount": 0})


def test_explanation_numeric_literal_cannot_override_statistics():
    value = answer()
    text = "均值为0.99"
    claim = value["claims"][0] | {"text": text}
    output = {"summary": text, "claims": [claim], "limitations": [], "insufficientEvidence": False}
    with pytest.raises(AgentError):
        AgentService.validate_grounding(GeneratedExplanation.model_validate(output), retrieval(), explanation=True)
