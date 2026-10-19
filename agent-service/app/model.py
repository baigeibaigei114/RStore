import asyncio
import json
import re
import time
from threading import BoundedSemaphore

import httpx2
from langchain_deepseek import ChatDeepSeek
from langsmith import tracing_context
from pydantic import SecretStr

from app.config import Settings
from app.errors import AgentError


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def strict_json(text):
    def reject_constant(value):
        raise ValueError("Non-finite JSON number")
    return json.loads(text, object_pairs_hook=unique_object, parse_constant=reject_constant)


def check_sensitive(text: str):
    patterns = (r"sk-[\w-]{12,}", r"(?i)bearer\s+\S+", r"(?i)(api[_-]?key|password|token|secret)\s*[:=]\s*\S+",
                r"(?i)(https?://|postgres(?:ql)?://|s3://)", r"[A-Za-z]:[\\/]", r"(?i)\b(bucket|objectKey|ownerId|userId)\b")
    if any(re.search(pattern, text) for pattern in patterns):
        raise AgentError("SENSITIVE_INPUT", "输入包含凭据、内部地址或资源路径，请移除后重试")


def safe_model_name(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", value):
        return None
    try:
        check_sensitive(value)
    except AgentError:
        return None
    return value


def safe_usage(value):
    # Keep only documented token counters; provider metadata may contain arbitrary text.
    if not isinstance(value, dict):
        return None
    counters: dict[str, int | dict[str, int]] = {}
    for key in ("input_tokens", "output_tokens", "total_tokens"):
        number = value.get(key)
        if type(number) is int and 0 <= number <= 2**63 - 1:
            counters[key] = number
    for key, allowed in (("input_token_details", ("audio", "text", "cache_read", "cache_creation")),
                         ("output_token_details", ("audio", "text", "reasoning"))):
        details = value.get(key)
        if isinstance(details, dict):
            selected = {name: details[name] for name in allowed
                        if type(details.get(name)) is int and 0 <= details[name] <= 2**63 - 1}
            if selected:
                counters[key] = selected
    return counters or None


class JsonModel:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.slots = BoundedSemaphore(settings.max_concurrent_models)
        self.calls = 0

    def configured(self):
        return (self.settings.enabled and bool(self.settings.deepseek_key)
                and self.settings.deepseek_base_url.rstrip("/") in ("https://api.deepseek.com", "https://api.deepseek.com/v1"))

    def generate(self, system: str, payload: dict):
        if not self.configured():
            raise AgentError("MODEL_UNAVAILABLE", "模型服务未启用或未配置", 503, True)
        messages = [("system", system), ("human", json.dumps(payload, ensure_ascii=False, allow_nan=False))]
        if len(json.dumps(messages, ensure_ascii=False).encode("utf-8")) > self.settings.max_provider_input_bytes:
            raise AgentError("MODEL_INPUT_TOO_LARGE", "模型输入超过预算，请缩短需求")
        if not self.slots.acquire(blocking=False):
            raise AgentError("MODEL_BUSY", "模型并发已满，请稍后重试", 429, True)
        start = time.perf_counter()
        trace = {"modelRequested": safe_model_name(self.settings.deepseek_model),
                 "modelReturned": None, "usage": None, "elapsedMs": 0}
        async_client = httpx2.AsyncClient(trust_env=False, timeout=self.settings.timeout_seconds)
        try:
            # Explicit clients avoid inherited SOCKS proxy discovery and automatic retries.
            with httpx2.Client(trust_env=False, timeout=self.settings.timeout_seconds) as client:
                llm = ChatDeepSeek(model=self.settings.deepseek_model, api_key=SecretStr(self.settings.deepseek_key),
                                  base_url=self.settings.deepseek_base_url, max_tokens=self.settings.max_output_tokens,
                                  timeout=self.settings.timeout_seconds, max_retries=0, temperature=0,
                                  http_client=client, http_async_client=async_client)
                self.calls += 1
                with tracing_context(enabled=False):
                    response = llm.bind(response_format={"type": "json_object"},
                                        extra_body={"thinking": {"type": "disabled"}}).invoke(messages,
                                        config={"callbacks": []})
            # Extract safe accounting before any content/finish/JSON validation rejects it.
            trace.update(modelReturned=safe_model_name(response.response_metadata.get("model_name")),
                         usage=safe_usage(response.usage_metadata),
                         elapsedMs=round((time.perf_counter() - start) * 1000))
            if response.response_metadata.get("finish_reason") != "stop" or not isinstance(response.content, str):
                raise AgentError("MODEL_OUTPUT_INVALID", "模型输出未完整结束", 503, True)
            output = strict_json(response.content)
            return output, trace
        except AgentError as error:
            error.trace = trace
            raise
        except (ValueError, TypeError):
            raise AgentError("MODEL_OUTPUT_INVALID", "模型未返回合法JSON", 503, True, trace=trace) from None
        except Exception:
            # No provider body, exception repr, request headers or prompt is logged/returned.
            trace["elapsedMs"] = round((time.perf_counter() - start) * 1000)
            raise AgentError("MODEL_UNAVAILABLE", "模型请求失败或超时", 503, True, trace=trace) from None
        finally:
            asyncio.run(async_client.aclose())
            self.slots.release()
