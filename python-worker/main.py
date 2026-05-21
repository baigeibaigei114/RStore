"""
Worker 入口模块 -- 应用启动入口，负责组装各依赖并启动 RabbitMQ 消息消费循环。

职责：
  - 初始化配置、MinIO 客户端、回调客户端。
  - 注册所有支持的处理器（NDVI / NDWI / 变化检测）到处理器字典。
  - 启动 RabbitMQ 消费者，进入消息循环。

设计要点：
  - 采用"手动依赖注入"模式，没有使用框架（如 Celery），以保持最小的外部依赖。
  - main() 函数作为组装器（类似于 Java Spring 的 @Configuration 类），
    将各组件显式串联起来，方便单元测试替换 Mock 实现。
  - 通过 if __name__ == "__main__" 守卫确保模块被导入时不会意外执行。
"""

from clients.callback_client import CallbackClient
from clients.minio_client import MinioStorageClient
from config import load_settings
from processors.change_processor import ChangeDetectionProcessor
from processors.ndvi_processor import NdviProcessor
from processors.ndwi_processor import NdwiProcessor
from workers.rabbitmq_consumer import RabbitMqConsumer


def main() -> None:
    """Worker 主入口：初始化所有依赖并启动 RabbitMQ 消费者。

    调用链路：
      1. 加载配置（通过环境变量）
      2. 创建临时目录（若目录尚不存在，mkdir(parents=True) 会递归创建）
      3. 初始化 MinIO 存储客户端和回调客户端
      4. 构建 任务类型 -> 处理器 的映射字典
      5. 创建消费者并启动阻塞式消费循环

    注意：main() 是同步阻塞的 -- consumer.start() 内部会一直运行，
    直到 RabbitMQ 连接关闭或进程收到 SIGINT/SIGTERM 信号。
    """
    settings = load_settings()
    # Python Path.mkdir(parents=True, exist_ok=True) 等价于 Java Files.createDirectories(path)
    # 其中 parents=True 对应递归创建父目录，exist_ok=True 对应目录已存在时不抛异常。
    settings.worker.temp_dir.mkdir(parents=True, exist_ok=True)

    storage_client = MinioStorageClient(settings.minio)
    callback_client = CallbackClient(settings.callback)

    # 处理器字典（策略模式）：键是消息中的 taskType，值是对应的处理器实例。
    # Java 中通常使用 Map<String, Processor> + @PostConstruct 注册 或 StrategyFactory。
    processors = {
        "NDVI": NdviProcessor(storage_client, settings.worker.temp_dir),
        "NDWI": NdwiProcessor(storage_client, settings.worker.temp_dir),
        "CHANGE_DETECTION": ChangeDetectionProcessor(
            storage_client, settings.worker.temp_dir
        ),
    }

    consumer = RabbitMqConsumer(settings.rabbitmq, callback_client, processors)
    consumer.start()


if __name__ == "__main__":
    # Python 的 if __name__ == "__main__" 等价于 Java 中 main 方法所在类的 public static void main。
    # 在 Python 中，当模块被直接运行时 __name__ 被设置为 "__main__"；
    # 而当被 import 时 __name__ 为模块名，因此该分支不会执行。
    main()
