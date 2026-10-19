"""Real evaluation failures remain rejected; supported qualitative method answers require no task metrics."""
import pytest

from app.errors import AgentError
from app.schemas import GeneratedAnswer
from app.service import AgentService


def test_method_named_coverage_is_allowed_as_text_but_not_as_task_metric_ref():
    quote = "部分覆盖、区域内没有像素中心均明确失败，coverageRatio表示范围覆盖。"
    retrieval = {"citations": [{"citationId": "C1", "excerpt": quote}]}
    claim = {"text": quote, "citationIds": ["C1"], "evidenceQuotes": [{"citationId": "C1", "quote": quote}], "metricRefs": []}
    value = {"answer": quote, "claims": [claim], "insufficientEvidence": False}
    AgentService.validate_grounding(GeneratedAnswer.model_validate(value), retrieval)
    claim["metricRefs"] = ["coverageRatio"]
    with pytest.raises(AgentError, match="知识问答无任务数值引用"):
        AgentService.validate_grounding(GeneratedAnswer.model_validate(value), retrieval)


def test_extra_punctuation_is_rejected_instead_of_silently_rewriting_provider_output():
    text = "单期均值不能独立判断农田健康"
    value = {"answer": text + "。", "claims": [{"text": text, "citationIds": ["C1"],
             "evidenceQuotes": [{"citationId": "C1", "quote": text}]}], "insufficientEvidence": False}
    with pytest.raises(AgentError, match="回答摘要含未绑定引用的主张"):
        AgentService.validate_grounding(GeneratedAnswer.model_validate(value), {"citations": [{"citationId": "C1", "excerpt": text}]})
