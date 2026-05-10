from typing import Any

import requests

from config import CallbackSettings


class CallbackClient:
    def __init__(self, settings: CallbackSettings):
        self._settings = settings

    def update_status(self, task_id: int, status: str, message: str | None = None, extra: dict[str, Any] | None = None) -> None:
        if not self._settings.enabled:
            return

        payload: dict[str, Any] = {"status": status}
        if message:
            payload["message"] = message
        if extra:
            payload.update(extra)

        url = f"{self._settings.base_url.rstrip('/')}/api/tasks/{task_id}/status"
        response = requests.post(url, json=payload, timeout=self._settings.timeout_seconds)
        response.raise_for_status()

    def safe_update_status(self, task_id: int, status: str, message: str | None = None, extra: dict[str, Any] | None = None) -> None:
        try:
            self.update_status(task_id, status, message, extra)
        except requests.RequestException as exc:
            # 当前 Java 端状态回调接口可能尚未实现，Worker 先记录告警而不阻断消息处理框架。
            print(f"[callback-warning] task_id={task_id} status={status} reason={exc}", flush=True)
