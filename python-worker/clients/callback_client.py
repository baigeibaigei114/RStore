"""
回调 HTTP 客户端模块 -- 负责 Worker 与 Java 后端之间的任务状态同步。

职责：
  - 在任务处理前，向后端"抢占"任务，避免重复处理（幂等性）。
  - 在任务处理过程中，向后端报告处理进度和最终状态（SUCCESS / FAILED / RETRYING）。
  - 以安全的（异常被捕获并 log）或严格的（异常向上传播）两种方式发送状态。

设计要点：
  - 每个回调都携带 X-Worker-Token 请求头，用于后端鉴权。
  - enabled 开关用于本地开发或测试环境关闭回调，避免因后端不可用阻塞 Worker。
  - safe_update_status 在非关键路径使用（如失败时的状态更新），避免回调异常遮蔽原始异常。
"""

from typing import Any

import requests

from config import CallbackSettings


class CallbackError(RuntimeError):
    """回调相关的自定义异常。

    与 Java 中自定义 RuntimeException 子类的用途相同，用于区分回调失败与其他异常。
    """

    pass


class CallbackClient:
    """Java 后端回调客户端，封装 HTTP POST 请求的细节。

    对应 Java 端 Controller 层 /api/tasks/{taskId}/status 和 /api/tasks/{taskId}/claim 两个接口。
    """

    def __init__(self, settings: CallbackSettings):
        self._settings = settings

    def _headers(self) -> dict[str, str]:
        """构造 HTTP 请求头，携带 Worker 鉴权 Token。

        返回的 dict 相当于 Java 中 HttpHeaders 对象，在 requests 库中直接传入即可。
        """
        return {"X-Worker-Token": self._settings.worker_token}

    def update_status(
        self,
        task_id: int,
        status: str,
        message: str | None = None,
        extra: dict[str, Any] | None = None,
    ) -> None:
        """向 Java 后端更新任务状态（核心回调方法）。

        Args:
            task_id: 任务 ID，与 Java 端 Task 实体一致。
            status: 状态值，通常为 "SUCCESS"/"FAILED"/"RETRYING"/"PROCESSING"。
            message: 可选的状态描述信息，失败时附加错误信息。
            extra: 额外的回调数据（如处理结果的 MinIO 对象路径），会合并到请求 body 中。

        Raises:
            CallbackError: 后端返回非 200 code 或响应不是合法 JSON 时抛出。
            requests.RequestException: 网络层错误（超时、连接拒绝等）。

        注意：Python 的 requests.post(json=payload) 会自动将 dict 序列化为 JSON
        并设置 Content-Type: application/json，等价于 Java 中 HttpEntity + RestTemplate。
        """
        if not self._settings.enabled:
            return

        # 构造请求体 --- dict 是 Python 最常用的"数据传输对象"，类似于 Java 中的 Map<String, Object>。
        payload: dict[str, Any] = {"status": status}
        if message:
            payload["message"] = message
            if status == "FAILED":
                payload["errorMessage"] = message
        if extra:
            # Python 的 dict.update() 等价于 Java Map.putAll()，
            # 将 extra 中的所有键值合并到 payload 中。
            payload.update(extra)

        # f-string 是 Python 的字符串插值语法（Python 3.6+ 引入），
        # 等价于 Java 中的 String.format("...%s...", var) 或 MessageFormat.format。
        # 与 Java 相比，f-string 更简洁，表达式直接嵌入花括号内。
        url = f"{self._settings.base_url.rstrip('/')}/api/tasks/{task_id}/status"
        response = requests.post(
            url,
            json=payload,
            headers=self._headers(),
            timeout=self._settings.timeout_seconds,
        )
        self._parse_result(response)

    def claim_task(self, task_id: int) -> dict[str, Any]:
        """向后端发起任务抢占请求，用于分布式环境下的幂等控制。

        多个 Worker 实例可能同时收到同一条 RabbitMQ 消息（镜像队列场景），
        只有抢占成功的 Worker 才应实际处理该任务。

        Args:
            task_id: 任务 ID。

        Returns:
            后端返回的 data 字典，通常包含 "action" 字段（"CLAIMED" / "ALREADY_FINISHED" 等）。

        Raises:
            CallbackError: 后端拒绝回调时抛出。
        """
        if not self._settings.enabled:
            return {"claimed": True, "action": "CLAIMED"}

        url = f"{self._settings.base_url.rstrip('/')}/api/tasks/{task_id}/claim"
        response = requests.post(
            url,
            headers=self._headers(),
            timeout=self._settings.timeout_seconds,
        )
        return self._parse_result(response) or {}

    def safe_update_status(
        self,
        task_id: int,
        status: str,
        message: str | None = None,
        extra: dict[str, Any] | None = None,
    ) -> None:
        """安全地更新任务状态（非关键路径使用），异常被捕获并记录日志。

        与 update_status 的区别：
          - 本方法内部 try-except 捕获所有异常，不会向外传播。
          - 适合在异常处理分支中调用（如任务失败时更新 FAILED 状态），
            避免回调失败导致原始异常信息被覆盖。

        Args:
            参数同 update_status。
        """
        try:
            self.update_status(task_id, status, message, extra)
        except (requests.RequestException, CallbackError) as exc:
            # 非关键回调失败时只记录告警，避免遮蔽主处理流程中的原始异常。
            print(
                f"[callback-warning] 状态回调失败 task_id={task_id} status={status} reason={exc}",
                flush=True,
            )

    def _parse_result(self, response: requests.Response) -> Any:
        """解析后端 HTTP 响应，统一校验 HTTP 状态码和业务 code。

        Args:
            response: requests 库的响应对象，相当于 Java 中的 ResponseEntity。

        Returns:
            后端返回的 data 字段内容。

        Raises:
            CallbackError: HTTP 状态码异常、响应不是 JSON、或业务 code != 200。

        Python 的 response.raise_for_status() 等价于 Java 中判断
        response.getStatusCode().isError() 然后抛 HttpClientErrorException。
        """
        # requests 的 raise_for_status()：如果 HTTP 状态码是 4xx 或 5xx，则抛出 HTTPError。
        # 等价于 Java 中 RestTemplate 的默认错误处理行为。
        response.raise_for_status()
        try:
            result = response.json()
        except ValueError as exc:
            raise CallbackError(
                f"回调响应不是 JSON：{response.text}"
            ) from exc

        # Java 后端统一返回 { "code": 200, "message": "success", "data": ... }
        # 这里校验业务 code 而非 HTTP 状态码，因为 HTTP 可能始终是 200（由后端统一包装）。
        if result.get("code") != 200:
            raise CallbackError(
                f"回调被后端拒绝：code={result.get('code')} message={result.get('message')}"
            )
        return result.get("data")
