import hmac
import json
import logging
from contextlib import asynccontextmanager
from uuid import UUID, uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.config import Settings
from app.embedding import BgeEmbedding
from app.errors import AgentError
from app.knowledge.store import PgKnowledgeStore
from app.model import JsonModel, strict_json
from app.schemas import AnswerRequest, DecisionRequest, ExplanationRequest
from app.service import AgentService

logger = logging.getLogger("ndvi_agent")


class InternalBoundary:
    def __init__(self, app, token):
        self.app = app
        self.token = token

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = dict(scope["headers"])
        request_id = headers.get(b"x-request-id", b"").decode("ascii", errors="ignore")
        try:
            UUID(request_id)
            request_id_valid = True
        except ValueError:
            request_id = str(uuid4())
            request_id_valid = False
        token = headers.get(b"x-internal-token", b"")
        scope.setdefault("state", {})["request_id"] = request_id

        async def error(status, code, message):
            response = JSONResponse({"requestId": request_id, "errorCode": code,
                                     "message": message, "retryable": False}, status_code=status)
            await response(scope, receive, send)

        if len(self.token) < 32 or len(token) > 1024 or not hmac.compare_digest(token, self.token.encode("utf-8")):
            return await error(401, "INTERNAL_AUTH_REQUIRED", "内部认证失败")
        if not request_id_valid:
            return await error(400, "REQUEST_ID_INVALID", "缺少合法请求标识")
        body = bytearray()
        while True:
            event = await receive()
            if event["type"] == "http.disconnect":
                return
            body.extend(event.get("body", b""))
            if len(body) > 65536:
                return await error(413, "BODY_TOO_LARGE", "内部请求超过64KiB")
            if not event.get("more_body", False):
                break
        if body:
            try:
                strict_json(body)
            except (ValueError, TypeError):
                return await error(400, "JSON_INVALID", "JSON不合法或存在重复键")
        sent = False

        async def replay():
            nonlocal sent
            if not sent:
                sent = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()
        await self.app(scope, replay, send)


def create_app(settings=None, service=None):
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app):
        if service is None:
            embedding = None
            if settings.model_path:
                try:
                    embedding = BgeEmbedding(settings)
                except Exception:
                    logger.warning("embedding startup failed; readiness unavailable")
            app.state.service = AgentService(embedding, PgKnowledgeStore(settings.database_url), JsonModel(settings))
        else:
            app.state.service = service
        yield

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(InternalBoundary, token=settings.internal_token)

    def result(request, data):
        body = {"requestId": request.state.request_id, **data}
        if len(json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")) > 65536:
            raise AgentError("RESPONSE_TOO_LARGE", "内部响应超过64KiB", 503)
        return body

    @app.exception_handler(AgentError)
    async def agent_error(request, error):
        body = {"requestId": request.state.request_id, "errorCode": error.code,
                "message": error.message, "retryable": error.retryable}
        if error.trace:
            body["trace"] = error.trace
        return JSONResponse(body, status_code=error.status)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        # Pydantic error input can contain secrets; never echo it.
        return JSONResponse({"requestId": request.state.request_id, "errorCode": "INPUT_INVALID",
                             "message": "请求字段不合法", "retryable": False}, status_code=400)

    @app.exception_handler(Exception)
    async def unexpected_error(request, error):
        logger.warning("request failed requestId=%s errorType=%s", request.state.request_id, type(error).__name__)
        return JSONResponse({"requestId": request.state.request_id, "errorCode": "SERVICE_UNAVAILABLE",
                             "message": "内部服务暂不可用", "retryable": True}, status_code=503)

    @app.get("/internal/v1/health")
    def health(request: Request):
        return result(request, {"alive": True})

    @app.get("/internal/v1/ready")
    def ready(request: Request):
        return result(request, request.app.state.service.ready())

    @app.post("/internal/v1/decisions")
    def decisions(body: DecisionRequest, request: Request):
        return result(request, request.app.state.service.decision(body))

    @app.post("/internal/v1/answers")
    def answers(body: AnswerRequest, request: Request):
        return result(request, request.app.state.service.answer(body))

    @app.post("/internal/v1/explanations")
    def explanations(body: ExplanationRequest, request: Request):
        return result(request, request.app.state.service.explanation(body))

    return app


app = create_app()
