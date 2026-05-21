"""
RabbitMQ 消费者模块 -- 负责从 RabbitMQ 队列消费任务消息，并路由到对应的处理器。

职责：
  - 建立与 RabbitMQ 的持久连接（支持心跳和阻塞连接超时）。
  - 通过 basic_consume 注册消息回调，进入消息循环。
  - 解析消息 JSON，提取 taskId 和 taskType，按 taskType 路由到不同处理器。
  - 实现消息确认（ACK/NACK）和重试策略（重新入队或拒绝/死信）。
  - 在任务处理前后通过 CallbackClient 与 Java 后端同步任务状态。

设计要点：
  - 使用 pika（Python RabbitMQ 客户端）的 BlockingConnection 模式，
    相当于 Java 中 com.rabbitmq:amqp-client 的 Channel + DefaultConsumer。
  - 消息确认采用"业务确认后才 ACK"的策略：只有抢占成功 + 处理成功 + 回调成功，才 basic_ack。
  - 失败重试策略与 Java 端对齐：requeue_on_error=True 时 nack(requeue=true)，
    由 Java 端队列的 delivery-limit + DLQ 机制控制最大重试次数。
"""

import json
from typing import Any

import pika

from clients.callback_client import CallbackClient
from config import RabbitMqSettings


class RabbitMqConsumer:
    """RabbitMQ 消息消费者，负责监听队列并路由任务到对应处理器。

    与 Spring Boot 中 @RabbitListener(queues = "rs.task.queue") 的功能等价。
    区别在于这里手动管理连接和消费行为，而非通过注解自动装配。

    核心流程：
      1. 连接 RabbitMQ -> 2. 声明 QOS -> 3. basicConsume -> 4. 等待消息
      5. 收到消息 -> 6. 抢占任务 -> 7. 处理器执行 -> 8. 更新状态 -> 9. ACK 确认
    """

    def __init__(
        self,
        settings: RabbitMqSettings,
        callback_client: CallbackClient,
        processors: dict[str, Any],
    ):
        """初始化消费者。

        Args:
            settings: RabbitMQ 连接和消费配置。
            callback_client: 用于与 Java 后端通信的回调客户端。
            processors: 任务类型到处理器实例的映射字典，
                        键是 taskType（如 "NDVI"），值是有 process(message) 方法的对象。
                        这其实就是策略模式（Strategy Pattern）的 Python 实现。
        """
        self._settings = settings
        self._callback_client = callback_client
        self._processors = processors

    def start(self) -> None:
        """建立 RabbitMQ 连接并启动消息消费循环（阻塞式）。

        此方法不会返回，直到连接断开或被外部中断（如 Ctrl+C）。
        等价于 Java 中 Channel.basicConsume() + 线程阻塞等待。
        """
        # pika.PlainCredentials 对应 Java 中 UsernamePasswordCredentials，
        # 用于 RabbitMQ 连接鉴权。
        credentials = pika.PlainCredentials(
            self._settings.username, self._settings.password
        )
        # ConnectionParameters 对应 Java 中 ConnectionFactory 的配置：
        # host/port -> factory.setHost()/setPort()
        # heartbeat -> factory.setRequestedHeartbeat()
        # blocked_connection_timeout -> 连接被阻塞时的超时保护。
        parameters = pika.ConnectionParameters(
            host=self._settings.host,
            port=self._settings.port,
            credentials=credentials,
            heartbeat=60,
            blocked_connection_timeout=300,
        )

        connection = pika.BlockingConnection(parameters)
        channel = connection.channel()

        # basic_qos(prefetch_count=N) 限制消费者同时处理的消息数不超过 N。
        # 等价于 Java 中 channel.basicQos(N)，用于实现"公平分发"(fair dispatch)，
        # 防止某个消费者堆积过多未确认消息。
        channel.basic_qos(prefetch_count=self._settings.prefetch_count)

        # basic_consume 注册回调函数（类似 Java 中 Consumer 接口的 handleDelivery 方法）。
        # on_message_callback 传入的 self._handle_message 是实例方法，
        # pika 会在收到消息时自动调用它。
        channel.basic_consume(
            queue=self._settings.queue,
            on_message_callback=self._handle_message,
        )

        print(
            f"[worker] 开始消费队列 queue={self._settings.queue}", flush=True
        )
        # start_consuming() 进入阻塞式事件循环，等价于 Java 中
        # channel.basicConsume() + CountDownLatch.await() 的组合。
        # 进程将在此处永久阻塞，直到连接关闭或收到 KeyboardInterrupt。
        channel.start_consuming()

    def _handle_message(self, channel, method, properties, body: bytes) -> None:
        """单条消息的处理回调。

        这是消息处理的核心入口，由 pika 在收到消息时自动调用。
        相当于 Java 中 Consumer.handleDelivery(String consumerTag, Envelope envelope,
        BasicProperties properties, byte[] body) 方法。

        Args:
            channel: 当前 Channel，用于 ACK/NACK 操作。
            method: 包含 delivery_tag 等信息（相当于 Java 的 Envelope）。
            properties: 消息属性（如 headers、priority）。
            body: 消息体（字节数组），通常是 UTF-8 编码的 JSON 字符串。

        处理流程：
          1. 反序列化 JSON 消息体
          2. 根据 taskType 查找对应的处理器
          3. 通过回调抢占任务（幂等控制）
          4. 执行业务处理
          5. 回调 SUCCESS 状态
          6. ACK 确认消息

        异常处理：
          - ValueError （业务校验失败） -> 不可重试，直接 FAILED + ACK（不重新入队）
          - 其他异常 -> 根据 requeue_on_error 配置决定重新入队还是拒绝
        """
        # message 类型注解为 dict[str, Any] | None：
        # 管道符 | 是 Python 3.10+ 的联合类型写法（PEP 604），
        # 等价于旧版 typing.Optional[dict[str, Any]]。
        message: dict[str, Any] | None = None
        try:
            # body 是 bytes 类型，先 decode 为 UTF-8 字符串，再用 json.loads 反序列化为 dict。
            # Python json.loads(s) 等价于 Java 中 ObjectMapper.readValue(s, Map.class)。
            message = json.loads(body.decode("utf-8"))

            task_id = int(message["taskId"])
            task_type = str(message["taskType"])

            # 从处理器字典中按 taskType 查找对应的处理器实例。
            # dict.get(key) 在 key 不存在时返回 None（不会像 Java 那样抛 NullPointerException）。
            processor = self._processors.get(task_type)
            if processor is None:
                raise ValueError(f"不支持的任务类型：{task_type}")

            # 抢占任务：防止多个 Worker 实例同时处理同一条消息。
            # 后端会通过数据库乐观锁或分布式锁保证只有一个 Worker 能抢占成功。
            claim_result = self._callback_client.claim_task(task_id)
            action = claim_result.get("action")

            if action == "ALREADY_FINISHED":
                # 任务已经被其他 Worker 处理完成，直接 ACK 丢弃当前消息。
                # 不重新入队，因为消息是重复的。
                channel.basic_ack(delivery_tag=method.delivery_tag)
                print(
                    f"[worker] 任务已结束，跳过重复消息 task_id={task_id} status={claim_result.get('taskStatus')}",
                    flush=True,
                )
                return

            if action != "CLAIMED":
                # 抢占失败（可能被其他 Worker 抢走），重新入队等待下次消费。
                # 注意：如果队列使用 delivery-limit 限制重试次数，多次重试后会进入 DLQ。
                self._requeue_or_reject(
                    channel, method.delivery_tag, task_id, f"任务抢占结果为 {action}"
                )
                return

            # 执行实际的业务处理。
            result = processor.process(message)

            # 只有结果文件已上传且 SUCCESS 回调成功后才 ack，尽量让消息确认与业务成功同向。
            # 这样设计可以保证：如果回调失败，消息会被重新入队并重试，
            # 避免了"处理成功但状态未回写"的不一致场景。
            self._callback_client.update_status(
                task_id, "SUCCESS", extra=result
            )
            channel.basic_ack(delivery_tag=method.delivery_tag)
            print(
                f"[worker] 任务处理完成 task_id={task_id} task_type={task_type}",
                flush=True,
            )

        except ValueError as exc:
            # ValueError 表示业务逻辑校验失败（如不支持的 taskType、缺少必要参数），
            # 属于不可恢复的错误，直接标记 FAILED 并拒绝消息（不重新入队）。
            task_id = self._extract_task_id(message)
            self._fail_non_retryable(
                channel, method.delivery_tag, task_id, str(exc)
            )
            print(
                f"[worker-error] 任务处理失败 task_id={task_id} reason={exc}",
                flush=True,
            )

        except Exception as exc:
            # 其他异常（网络错误、文件 IO 错误、第三方服务不可用等）属于可重试错误。
            task_id = self._extract_task_id(message)
            if task_id is not None and self._settings.requeue_on_error:
                # 异步更新状态为重试中，不阻塞消息的重新入队。
                # safe_update_status 保证回调异常不会覆盖原始异常。
                self._callback_client.safe_update_status(
                    task_id, "RETRYING", message=str(exc)
                )

            self._requeue_or_reject(
                channel, method.delivery_tag, task_id, str(exc)
            )
            print(
                f"[worker-error] 任务处理失败 task_id={task_id} reason={exc}",
                flush=True,
            )

    def _fail_non_retryable(
        self,
        channel,
        delivery_tag: int,
        task_id: int | None,
        reason: str,
    ) -> None:
        """处理不可重试的错误：标记 FAILED 并确认（拒绝但不重新入队）消息。

        Args:
            channel: pika Channel 对象。
            delivery_tag: 消息的投递标签，用于 ACK/NACK 操作。
            task_id: 任务 ID，为 None 时直接拒绝消息。
            reason: 错误原因描述。

        当 task_id 无法提取时（如消息体损坏），我们仍能通过 reject(requeue=False)
        将消息从队列中移除，避免"坏消息"阻塞队列。
        """
        if task_id is None:
            # 无法提取 taskId，直接拒绝消息且不重新入队：
            # 丢弃无法解析的消息，避免消费死循环。
            channel.basic_reject(
                delivery_tag=delivery_tag, requeue=False
            )
            return

        try:
            # 先尝试告知后端该任务失败。
            self._callback_client.update_status(
                task_id, "FAILED", message=reason
            )
            # 标记业务完败后仍 ACK 消息，而非 reject(requeue=false)，
            # 因为我们已经消费并处理了这个错误（更新了后端状态），不应再重新入队。
            channel.basic_ack(delivery_tag=delivery_tag)
        except Exception as callback_exc:
            # 如果连回调本身也失败，说明后端正不可用：
            # 此时根据配置决定是否重新入队以等待后端恢复。
            if self._settings.requeue_on_error:
                self._callback_client.safe_update_status(
                    task_id, "RETRYING", message=str(callback_exc)
                )
            self._requeue_or_reject(
                channel, delivery_tag, task_id, str(callback_exc)
            )

    def _requeue_or_reject(
        self,
        channel,
        delivery_tag: int,
        task_id: int | None,
        reason: str,
    ) -> None:
        """根据 requeue_on_error 配置决定重新入队还是拒绝消息。

        这是 Worker 重试策略的核心决策点：
          - requeue_on_error=True：nack(requeue=true) 将消息重新放回队列头部，
            等待其他消费者（或自身）再次消费。Java 队列使用 delivery-limit
            控制最大重试次数，超过次数后会自动进入死信队列（DLQ）。
          - requeue_on_error=False：直接拒绝消息，不重新入队（相当于丢弃或死信）。

        Args:
            channel: pika Channel 对象。
            delivery_tag: 消息的投递标签。
            task_id: 任务 ID，用于回调通知。
            reason: 错误原因。
        """
        if self._settings.requeue_on_error:
            # Java 队列使用 delivery-limit 控制最大重试次数，重新入队后超过次数会进入 DLQ。
            # nack(requeue=True) 等价于 Java 中 channel.basicNack(deliveryTag, false, true)。
            # 与 basicReject 的区别是 nack 支持批量拒绝（multiple 参数），这里只用单条。
            channel.basic_nack(
                delivery_tag=delivery_tag, requeue=True
            )
        else:
            if task_id is not None:
                self._callback_client.safe_update_status(
                    task_id, "FAILED", message=reason
                )
            # 不可恢复错误直接拒绝，交给 RabbitMQ 死信链路保留失败消息。
            # reject(requeue=false) 等价于 Java 中 channel.basicReject(deliveryTag, false)。
            channel.basic_reject(
                delivery_tag=delivery_tag, requeue=False
            )

    @staticmethod
    def _extract_task_id(
        message: dict[str, Any] | None,
    ) -> int | None:
        """安全地从消息字典中提取 taskId，提取失败返回 None。

        设计为静态方法（@staticmethod），因为该方法不依赖实例状态，
        类似于 Java 中的 static 工具方法。

        之所以不直接在异常处理中从 message 取 taskId，
        是因为异常可能来自 json.loads() 阶段，此时 message 为 None 或残缺。

        Args:
            message: 已解析的消息字典，可能为 None。

        Returns:
            整数 taskId，提取失败返回 None。
        """
        if not message:
            return None
        try:
            return int(message.get("taskId"))
        except (TypeError, ValueError):
            return None
