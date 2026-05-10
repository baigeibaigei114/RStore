import os
from dataclasses import dataclass, field
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    return int(value) if value else default


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.lower() in {"1", "true", "yes", "y"}


@dataclass(frozen=True)
class RabbitMqSettings:
    host: str = os.getenv("RABBITMQ_HOST", "localhost")
    port: int = _env_int("RABBITMQ_PORT", 5672)
    username: str = os.getenv("RABBITMQ_USERNAME", "guest")
    password: str = os.getenv("RABBITMQ_PASSWORD", "guest")
    queue: str = os.getenv("RABBITMQ_TASK_QUEUE", "rs.task.queue")
    prefetch_count: int = _env_int("RABBITMQ_PREFETCH_COUNT", 1)
    requeue_on_error: bool = _env_bool("RABBITMQ_REQUEUE_ON_ERROR", True)


@dataclass(frozen=True)
class MinioSettings:
    endpoint: str = os.getenv("MINIO_ENDPOINT", "localhost:9000")
    access_key: str = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
    secret_key: str = os.getenv("MINIO_SECRET_KEY", "minioadmin")
    secure: bool = _env_bool("MINIO_SECURE", False)


@dataclass(frozen=True)
class CallbackSettings:
    base_url: str = os.getenv("CALLBACK_BASE_URL", "http://localhost:8080")
    timeout_seconds: int = _env_int("CALLBACK_TIMEOUT_SECONDS", 10)
    enabled: bool = _env_bool("CALLBACK_ENABLED", True)


@dataclass(frozen=True)
class WorkerSettings:
    temp_dir: Path = Path(os.getenv("WORKER_TEMP_DIR", "tmp")).resolve()


@dataclass(frozen=True)
class Settings:
    rabbitmq: RabbitMqSettings = field(default_factory=RabbitMqSettings)
    minio: MinioSettings = field(default_factory=MinioSettings)
    callback: CallbackSettings = field(default_factory=CallbackSettings)
    worker: WorkerSettings = field(default_factory=WorkerSettings)


def load_settings() -> Settings:
    return Settings()
