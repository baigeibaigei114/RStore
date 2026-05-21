"""
配置模块 -- 集中管理 Python Worker 所需的环境变量与配置类。

职责：
  - 从环境变量读取 RabbitMQ、MinIO、回调接口等外部依赖的连接参数。
  - 提供不可变（frozen）的 dataclass 配置对象，供各模块注入使用。
  - 相当于 Java Spring 中 @ConfigurationProperties 的功能。

设计要点：
  - 所有配置类均标记为 frozen=True，确保配置在使用过程中不被意外修改。
  - 配置的默认值与生产环境对齐，开发环境可通过环境变量覆盖。
  - dataclass 是 Python 3.7+ 内置的类装饰器（等价于 Java 的 @Data + @NoArgsConstructor），
    可自动生成 __init__、__repr__、__eq__ 等方法。
"""

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    """读取环境变量并转换为 int，变量不存在时返回默认值。

    Python 的 os.getenv 在没有值时返回 None，不会像 Java System.getenv 那样抛异常，
    因此这里手动判断 None 后回退默认值。
    """
    value = os.getenv(name)
    return int(value) if value else default


def _env_bool(name: str, default: bool) -> bool:
    """读取环境变量并转换为 bool，支持 "1"/"true"/"yes"/"y"（不区分大小写）。

    之所以没有直接使用 bool(value)，是因为 Python 中 bool("false") 的结果是 True（非空字符串恒为真），
    所以需要显式做字符串匹配。
    """
    value = os.getenv(name)
    if value is None:
        return default
    return value.lower() in {"1", "true", "yes", "y"}


def _env_first(names: tuple[str, ...], default: str) -> str:
    """依次尝试多个环境变量名，返回第一个有值的；全部为空时返回默认值。

    适用场景：同一个配置在不同部署环境可能有不同的环境变量名（如旧版命名与新版命名并存），
    按优先级顺序依次尝试可保证向后兼容。
    """
    for name in names:
        value = os.getenv(name)
        if value:
            return value
    return default


@dataclass(frozen=True)
class RabbitMqSettings:
    """RabbitMQ 连接与消费配置。

    对应 Java 服务端 RabbitMQ 的连接参数，Worker 端作为消费者使用。
    prefetch_count 控制 QOS 预取数量，相当于 Java 端 channel.basicQos() 的参数。
    """

    host: str = os.getenv("RABBITMQ_HOST", "localhost")
    port: int = _env_int("RABBITMQ_PORT", 5672)
    username: str = os.getenv("RABBITMQ_USERNAME", "guest")
    password: str = os.getenv("RABBITMQ_PASSWORD", "guest")
    queue: str = _env_first(
        ("RABBITMQ_QUEUE", "RABBITMQ_TASK_QUEUE"), "rs.task.queue"
    )
    prefetch_count: int = _env_int("RABBITMQ_PREFETCH_COUNT", 1)
    requeue_on_error: bool = _env_bool("RABBITMQ_REQUEUE_ON_ERROR", True)


@dataclass(frozen=True)
class MinioSettings:
    """MinIO（S3 兼容对象存储）连接配置。

    与 Java 端 MinioClient 的构建参数一一对应。
    secure=False 表示使用 HTTP 而非 HTTPS，开发环境默认关闭 TLS。
    """

    endpoint: str = os.getenv("MINIO_ENDPOINT", "localhost:9000")
    access_key: str = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
    secret_key: str = os.getenv("MINIO_SECRET_KEY", "minioadmin")
    secure: bool = _env_bool("MINIO_SECURE", False)


@dataclass(frozen=True)
class CallbackSettings:
    """Java 后端回调接口配置。

    Worker 在处理任务过程中需要向后端（Spring Boot 应用）报告任务状态（成功/失败/重试），
    这些回调通过 HTTP POST 请求发送。worker_token 用于请求头鉴权。
    """

    base_url: str = _env_first(
        ("BACKEND_BASE_URL", "CALLBACK_BASE_URL"), "http://localhost:8080"
    )
    timeout_seconds: int = _env_int("CALLBACK_TIMEOUT_SECONDS", 10)
    enabled: bool = _env_bool("CALLBACK_ENABLED", True)
    worker_token: str = os.getenv("WORKER_TOKEN", "")


@dataclass(frozen=True)
class WorkerSettings:
    """Worker 自身运行配置。"""

    temp_dir: Path = Path(os.getenv("WORKER_TEMP_DIR", "tmp")).resolve()


@dataclass(frozen=True)
class Settings:
    """顶层配置聚合。

    Java 中通常使用 @ConfigurationProperties(prefix="xxx") 加嵌套类来实现，
    这里用 dataclass 组合的方式达到相似效果。
    field(default_factory=...) 是必需的一一因为 dataclass 的不可变(frozen)特性，
    如果不使用 default_factory，所有实例会共享同一个可变默认值。
    """

    rabbitmq: RabbitMqSettings = field(default_factory=RabbitMqSettings)
    minio: MinioSettings = field(default_factory=MinioSettings)
    callback: CallbackSettings = field(default_factory=CallbackSettings)
    worker: WorkerSettings = field(default_factory=WorkerSettings)


def load_settings() -> Settings:
    """加载全部配置。

    因为所有配置类的字段都直接从环境变量初始化，所以这里直接返回一个空参 Settings()
    即可触发各个嵌套 dataclass 的默认值构造逻辑。
    相当于 Java 中 Spring 自动装配的 @Bean 方法。
    """
    return Settings()
