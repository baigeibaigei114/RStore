from pathlib import Path

from minio import Minio
from minio.error import S3Error

from config import MinioSettings


class MinioStorageClient:
    def __init__(self, settings: MinioSettings):
        self._client = Minio(
            settings.endpoint,
            access_key=settings.access_key,
            secret_key=settings.secret_key,
            secure=settings.secure,
        )

    def download_file(self, bucket: str, object_key: str, target_path: Path) -> Path:
        target_path.parent.mkdir(parents=True, exist_ok=True)
        self._client.fget_object(bucket, object_key, str(target_path))
        return target_path

    def upload_file(self, bucket: str, object_key: str, source_path: Path, content_type: str = "image/tiff") -> None:
        # Worker 可能独立于 Java 服务启动，上传结果前主动确保输出 bucket 可用。
        if not self._client.bucket_exists(bucket):
            self._client.make_bucket(bucket)
        self._client.fput_object(bucket, object_key, str(source_path), content_type=content_type)

    def object_exists(self, bucket: str, object_key: str) -> bool:
        try:
            self._client.stat_object(bucket, object_key)
            return True
        except S3Error as exc:
            if exc.code in {"NoSuchKey", "NoSuchObject", "NoSuchBucket"}:
                return False
            raise
