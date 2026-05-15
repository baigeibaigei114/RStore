import json
from typing import Any

import pika

from clients.callback_client import CallbackClient
from config import RabbitMqSettings


class RabbitMqConsumer:
    def __init__(self, settings: RabbitMqSettings, callback_client: CallbackClient, processors: dict[str, Any]):
        self._settings = settings
        self._callback_client = callback_client
        self._processors = processors

    def start(self) -> None:
        credentials = pika.PlainCredentials(self._settings.username, self._settings.password)
        parameters = pika.ConnectionParameters(
            host=self._settings.host,
            port=self._settings.port,
            credentials=credentials,
            heartbeat=60,
            blocked_connection_timeout=300,
        )

        connection = pika.BlockingConnection(parameters)
        channel = connection.channel()
        channel.basic_qos(prefetch_count=self._settings.prefetch_count)
        channel.basic_consume(queue=self._settings.queue, on_message_callback=self._handle_message)

        print(f"[worker] 开始消费队列 queue={self._settings.queue}", flush=True)
        channel.start_consuming()

    def _handle_message(self, channel, method, properties, body: bytes) -> None:
        message: dict[str, Any] | None = None
        try:
            message = json.loads(body.decode("utf-8"))
            task_id = int(message["taskId"])
            task_type = str(message["taskType"])
            processor = self._processors.get(task_type)
            if processor is None:
                raise ValueError(f"不支持的任务类型：{task_type}")

            claim_result = self._callback_client.claim_task(task_id)
            action = claim_result.get("action")
            if action == "ALREADY_FINISHED":
                channel.basic_ack(delivery_tag=method.delivery_tag)
                print(f"[worker] 任务已结束，跳过重复消息 task_id={task_id} status={claim_result.get('taskStatus')}", flush=True)
                return
            if action != "CLAIMED":
                self._requeue_or_reject(channel, method.delivery_tag, task_id, f"任务抢占结果为 {action}")
                return

            result = processor.process(message)
            # 只有结果文件已上传且 SUCCESS 回调成功后才 ack，尽量让消息确认与业务成功同向。
            self._callback_client.update_status(task_id, "SUCCESS", extra=result)
            channel.basic_ack(delivery_tag=method.delivery_tag)
            print(f"[worker] 任务处理完成 task_id={task_id} task_type={task_type}", flush=True)
        except Exception as exc:
            task_id = self._extract_task_id(message)
            if task_id is not None and self._settings.requeue_on_error:
                self._callback_client.safe_update_status(task_id, "RETRYING", message=str(exc))

            self._requeue_or_reject(channel, method.delivery_tag, task_id, str(exc))
            print(f"[worker-error] 任务处理失败 task_id={task_id} reason={exc}", flush=True)

    def _requeue_or_reject(self, channel, delivery_tag: int, task_id: int | None, reason: str) -> None:
        if self._settings.requeue_on_error:
            # Java 队列使用 delivery-limit 控制最大重试次数，重新入队后超过次数会进入 DLQ。
            channel.basic_nack(delivery_tag=delivery_tag, requeue=True)
        else:
            if task_id is not None:
                self._callback_client.safe_update_status(task_id, "FAILED", message=reason)
            # 不可恢复错误直接拒绝，交给 RabbitMQ 死信链路保留失败消息。
            channel.basic_reject(delivery_tag=delivery_tag, requeue=False)

    @staticmethod
    def _extract_task_id(message: dict[str, Any] | None) -> int | None:
        if not message:
            return None
        try:
            return int(message.get("taskId"))
        except (TypeError, ValueError):
            return None
