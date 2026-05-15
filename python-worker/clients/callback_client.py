from typing import Any

import requests

from config import CallbackSettings


class CallbackError(RuntimeError):
    pass


class CallbackClient:
    def __init__(self, settings: CallbackSettings):
        self._settings = settings

    def update_status(self, task_id: int, status: str, message: str | None = None, extra: dict[str, Any] | None = None) -> None:
        if not self._settings.enabled:
            return

        payload: dict[str, Any] = {"status": status}
        if message:
            payload["message"] = message
            if status == "FAILED":
                payload["errorMessage"] = message
        if extra:
            payload.update(extra)

        url = f"{self._settings.base_url.rstrip('/')}/api/tasks/{task_id}/status"
        response = requests.post(url, json=payload, timeout=self._settings.timeout_seconds)
        self._parse_result(response)

    def claim_task(self, task_id: int) -> dict[str, Any]:
        if not self._settings.enabled:
            return {"claimed": True, "action": "CLAIMED"}

        url = f"{self._settings.base_url.rstrip('/')}/api/tasks/{task_id}/claim"
        response = requests.post(url, timeout=self._settings.timeout_seconds)
        return self._parse_result(response) or {}

    def safe_update_status(self, task_id: int, status: str, message: str | None = None, extra: dict[str, Any] | None = None) -> None:
        try:
            self.update_status(task_id, status, message, extra)
        except (requests.RequestException, CallbackError) as exc:
            # 非关键回调失败时只记录告警，避免遮蔽主处理流程中的原始异常。
            print(f"[callback-warning] 状态回调失败 task_id={task_id} status={status} reason={exc}", flush=True)

    def _parse_result(self, response: requests.Response) -> Any:
        response.raise_for_status()
        try:
            result = response.json()
        except ValueError as exc:
            raise CallbackError(f"回调响应不是 JSON：{response.text}") from exc

        if result.get("code") != 200:
            raise CallbackError(f"回调被后端拒绝：code={result.get('code')} message={result.get('message')}")
        return result.get("data")
