"""
MinIO 对象存储客户端模块 -- 封装与 MinIO (S3 兼容) 的上传、下载和存在性检查。

职责：
  - 从 MinIO bucket 中下载 GeoTIFF 文件到本地临时目录。
  - 将处理后的结果文件上传回 MinIO。
  - 检查 MinIO 中指定的对象是否已存在（用于幂等跳过）。
  - 上传前自动创建目标 bucket（如果不存在）。

设计要点：
  - 底层使用 minio 官方 Python SDK（与 Java 端使用 io.minio:minio 是同一个协议的两个实现）。
  - fget_object / fput_object 操作的是本地文件系统路径，适合大文件的流式传输。
  - 对象存在性检查通过 stat_object 实现，而非 list_objects，因为 stat 更轻量（仅查询元数据）。
"""

from pathlib import Path

from minio import Minio
from minio.error import S3Error

from config import MinioSettings


class MinioStorageClient:
    """MinIO (S3 兼容) 存储客户端。

    与 Java 端 MinioClient 的功能对应，提供文件级别（而非流级别）的上传/下载操作。
    所有方法均以文件路径为参数，内部处理文件 I/O 和网络传输。
    """

    def __init__(self, settings: MinioSettings):
        """初始化 MinIO 客户端。

        Args:
            settings: MinIO 连接配置（endpoint、access_key、secret_key、secure）。
        """
        self._client = Minio(
            settings.endpoint,
            access_key=settings.access_key,
            secret_key=settings.secret_key,
            secure=settings.secure,
        )

    def download_file(
        self, bucket: str, object_key: str, target_path: Path
    ) -> Path:
        """从 MinIO 下载对象到本地文件。

        Args:
            bucket: 存储桶名称，如 "rs-input"。
            object_key: 对象的完整路径（如 "2024/landsat/LC08_001.tif"）。
            target_path: 本地目标路径。

        Returns:
            下载完成后的本地文件路径（与 target_path 相同，方便链式调用）。
        """
        # mkdir(parents=True, exist_ok=True) 等价于 Java 的 Files.createDirectories(path)：
        # parents=True 对应递归创建缺失的父目录，
        # exist_ok=True 对应 DirectoryExistsException 时静默忽略。
        target_path.parent.mkdir(parents=True, exist_ok=True)
        # fget_object 将 MinIO 对象直接写入本地文件，适合大文件，
        # 内部实现相当于 Java MinioClient.getObject() + FileOutputStream。
        self._client.fget_object(bucket, object_key, str(target_path))
        return target_path

    def upload_file(
        self,
        bucket: str,
        object_key: str,
        source_path: Path,
        content_type: str = "image/tiff",
    ) -> None:
        """上传本地文件到 MinIO。

        Args:
            bucket: 目标存储桶。
            object_key: 对象在 MinIO 中的路径。
            source_path: 本地源文件路径。
            content_type: MIME 类型，默认 image/tiff，影响浏览器下载行为。

        自动创建 bucket 的考量：
          通常 Java 后端在任务创建阶段会确保输出 bucket 存在，但 Worker
          可能独立运行在容器中，后端不可用时仍能独立工作，因此上传前自检。
        """
        # Worker 可能独立于 Java 服务启动，上传结果前主动确保输出 bucket 可用。
        if not self._client.bucket_exists(bucket):
            self._client.make_bucket(bucket)
        # fput_object 将本地文件上传，content_type 对应 HTTP Content-Type 头，
        # 等价于 Java MinioClient.putObject() + PutObjectArgs.contentType()。
        self._client.fput_object(
            bucket, object_key, str(source_path), content_type=content_type
        )

    def object_exists(self, bucket: str, object_key: str) -> bool:
        """检查 MinIO 中指定对象是否存在，用于幂等跳过。

        通过 stat_object 只获取对象元数据而非内容，比 list_objects 更高效。
        类比于 Java 中 MinioClient.statObject() 的存在性判断。

        Args:
            bucket: 存储桶名称。
            object_key: 对象路径。

        Returns:
            对象存在返回 True，不存在（NoSuchKey / NoSuchObject / NoSuchBucket）返回 False。

        Raises:
            S3Error: 当错误不是"对象不存在"时向上传播（如网络错误、鉴权失败）。
        """
        try:
            self._client.stat_object(bucket, object_key)
            return True
        except S3Error as exc:
            # Python 的 in 操作符可以快速判断元素是否在集合中，
            # {"NoSuchKey", ...} 在 Python 中是一个 set 字面量，
            # 等价于 Java 中 Set.of("NoSuchKey", "NoSuchObject", "NoSuchBucket").contains(code)。
            if exc.code in {"NoSuchKey", "NoSuchObject", "NoSuchBucket"}:
                return False
            raise
