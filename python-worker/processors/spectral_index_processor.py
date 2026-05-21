"""
光谱指数处理器基类模块 -- 提供 NDVI/NDWI 等光谱指数计算的通用流程。

职责：
  - 从 MinIO 下载输入 GeoTIFF 到本地临时工作目录。
  - 读取指定两个波段的数据，计算归一化差值指数。
  - 将计算结果写入单波段 GeoTIFF 并上传回 MinIO。
  - 若结果文件已存在，直接跳过重复计算（幂等逻辑）。

设计要点：
  - 使用模板方法模式：process_index 定义计算流程骨架，
    子类（NdviProcessor / NdwiProcessor）只需指定使用的波段号。
  - 所有临时文件放入以 uuid 隔离的工作目录，finally 中确保清理，
    避免磁盘空间泄漏。
  - EPSILON = 1e-6 防止分母为零导致除零错误。
"""

from pathlib import Path
import shutil
from uuid import uuid4

import rasterio

from clients.minio_client import MinioStorageClient
from utils.raster_utils import (
    normalized_difference,
    read_band,
    write_single_band_geotiff,
)


class SpectralIndexProcessor:
    """光谱指数处理器基类。

    封装了归一化差值指数的完整计算流程：
      1. 检查输出是否已存在（幂等跳过）
      2. 下载源文件到临时目录
      3. 读取两个波段的像素数据
      4. 计算指数：(band1 - band2) / (band1 + band2 + EPSILON)
      5. 写入单波段 GeoTIFF
      6. 上传结果到 MinIO
      7. 清理临时目录

    EPSILON 用于避免除零，同时不会对计算结果产生显著影响，
    因为分母通常远大于 1e-6。
    """

    EPSILON = 1e-6

    def __init__(
        self,
        storage_client: MinioStorageClient,
        temp_dir: Path,
        index_name: str,
    ):
        """初始化处理器。

        Args:
            storage_client: MinIO 存储客户端。
            temp_dir: 临时文件根目录，处理器会在其中创建子目录。
            index_name: 指数名称（如 "NDVI"、"NDWI"），用于日志和输出文件命名。
        """
        self._storage_client = storage_client
        self._temp_dir = temp_dir
        self._index_name = index_name

    def process_index(
        self,
        message: dict,
        first_band: int,
        second_band: int,
        band_names: dict[str, int],
    ) -> dict:
        """执行光谱指数计算流程（模板方法）。

        Args:
            message: RabbitMQ 消息字典，必须包含：
                - inputBucket: 输入文件所在 MinIO bucket
                - inputObjectKey: 输入文件对象路径
                - outputBucket: 输出目标 bucket
                - outputObjectKey: 输出目标对象路径
                - taskId: 任务 ID
            first_band: 作为被减数的波段编号（如 NDVI 中的 NIR 波段）。
            second_band: 作为减数的波段编号（如 NDVI 中的 RED 波段）。
            band_names: 实际使用的波段编号映射，会合并到返回结果中，
                        便于后端记录实际使用的波段（如 {"redBand": 3, "nirBand": 4}）。

        Returns:
            结果字典，包含输出 bucket、objectKey 和使用的波段编号。
            若已存在则额外包含 skippedReason 字段。

        Raises:
            S3Error: MinIO 操作失败时抛出。
            rasterio.RasterioIOError: 文件读取/写入失败时抛出。
            shutil.Error: 临时目录清理失败时抛出（被 finally 捕获并忽略）。
        """
        # 幂等性检查：如果结果对象已在 MinIO 中存在，跳过计算直接返回。
        # 这可以防止因消息重复消费导致的重复计算。
        if self._storage_client.object_exists(
            message["outputBucket"], message["outputObjectKey"]
        ):
            return {
                "outputBucket": message["outputBucket"],
                "outputObjectKey": message["outputObjectKey"],
                "skippedReason": "结果对象已存在",
                **band_names,  # **dict 是 Python 的字典解包语法（PEP 448）：
                # 将 band_names 中的所有键值对"展开"合并到当前字典中。
                # 等价于 Java 中 Map.putAll() 的效果，但在字典字面量中直接完成。
            }

        # 创建隔离的工作目录，使用 uuid 保证并发任务间不会冲突。
        # Python f-string 中可以直接调用函数，如 {uuid4().hex}，
        # Java 中通常先用变量接收再拼接：String hex = UUID.randomUUID().toString().replace("-", "")。
        work_dir = (
            self._temp_dir
            / f"{self._index_name.lower()}_{message['taskId']}_{uuid4().hex}"
        )
        work_dir.mkdir(parents=True, exist_ok=True)

        input_path = work_dir / "input.tif"
        output_path = work_dir / f"{self._index_name.lower()}.tif"
        try:
            # Worker 消息只携带对象路径，计算前需要先把 GeoTIFF 拉到本地临时目录。
            # download_file 返回本地路径，便于后续打开。
            self._storage_client.download_file(
                message["inputBucket"], message["inputObjectKey"], input_path
            )

            # Python 的 with 语句是上下文管理器（PEP 343），
            # 等价于 Java 7+ 的 try-with-resources 语法：
            #   try (Dataset dataset = rasterio.open(path)) { ... }
            # with 语句在进入时调用 __enter__，在退出时（无论是否异常）调用 __exit__，
            # 确保文件/资源正确关闭。
            with rasterio.open(input_path) as dataset:
                first = read_band(dataset, first_band)
                second = read_band(dataset, second_band)
                index_data = normalized_difference(
                    first, second, epsilon=self.EPSILON
                )
                write_single_band_geotiff(dataset, output_path, index_data)

            # 上传结果文件到 MinIO。
            self._storage_client.upload_file(
                message["outputBucket"],
                message["outputObjectKey"],
                output_path,
            )
            return {
                "outputBucket": message["outputBucket"],
                "outputObjectKey": message["outputObjectKey"],
                **band_names,
            }
        finally:
            # finally 块保证即使 process_index 中途抛出异常，临时文件也会被清理。
            # 这与 Java 的 try-finally 或 try-with-resources 语义一致。
            # shutil.rmtree 等价于 Java 中递归删除目录：
            #   Files.walk(path).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete)
            # ignore_errors=True 对应删除只读文件等时忽略错误。
            shutil.rmtree(work_dir, ignore_errors=True)
