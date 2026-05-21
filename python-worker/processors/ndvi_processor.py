"""
NDVI（归一化植被指数）处理器模块。

职责：
  - 从任务消息中提取 RED 和 NIR 波段编号。
  - 委托基类 SpectralIndexProcessor 执行归一化差值计算。
  - NDVI 公式：(NIR - RED) / (NIR + RED + EPSILON)，值域 [-1, 1]。
    正值通常表示植被覆盖，负值表示水体或裸地。

设计要点：
  - 采用委托模式而非继承：NdviProcessor 包含一个 SpectralIndexProcessor 实例，
    而不是继承它。这使得两个处理器之间的耦合更松散，方便单测 Mock。
  - 默认波段：RED=3, NIR=4（Landsat 8/9 的波段约定），用户可通过 params 覆盖。
"""

from pathlib import Path

from clients.minio_client import MinioStorageClient
from processors.spectral_index_processor import SpectralIndexProcessor


class NdviProcessor:
    """NDVI（归一化植被指数）处理器。

    NDVI 是最常用的遥感植被指数，利用植被在近红外高反射、
    红光低反射的光谱特性区分植被与非植被。
    """

    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path):
        """初始化 NDVI 处理器。

        Args:
            storage_client: MinIO 存储客户端（透传给 SpectralIndexProcessor）。
            temp_dir: 临时文件根目录（透传给 SpectralIndexProcessor）。
        """
        # 委托给基类处理器，指定指数名称为 "NDVI"。
        self._processor = SpectralIndexProcessor(
            storage_client, temp_dir, "NDVI"
        )

    def process(self, message: dict) -> dict:
        """执行 NDVI 计算。

        Args:
            message: 任务消息字典，其中 params 可选字段：
                - redBand (int): 红光波段编号，默认 3（Landsat 8 Band 4）。
                - nirBand (int): 近红外波段编号，默认 4（Landsat 8 Band 5）。

        Returns:
            结果字典（透传基类返回值，包含 outputBucket / outputObjectKey 及波段编号）。

        NDVI 公式详解：
          NDVI = (NIR - RED) / (NIR + RED + 1e-6)
          - 植被茂密区：NIR >> RED，NDVI 接近 +1
          - 裸地/建筑：NIR ≈ RED，NDVI 接近 0
          - 水体：NIR << RED，NDVI 为负值
        """
        # Python 的 dict.get("key") or {} 是一种"空值安全"的写法：
        # 如果 message.get("params") 返回 None（键不存在），则用空字典 {} 替代。
        # 这类似于 Java 中 Optional.ofNullable(map.get("params")).orElse(Map.of())。
        params = message.get("params") or {}
        red_band = int(params.get("redBand", 3))
        nir_band = int(params.get("nirBand", 4))
        # NDVI = (NIR - RED) / (NIR + RED + 1e-6)
        return self._processor.process_index(
            message,
            first_band=nir_band,
            second_band=red_band,
            band_names={"redBand": red_band, "nirBand": nir_band},
        )
