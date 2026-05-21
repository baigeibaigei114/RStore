"""
NDWI（归一化水体指数）处理器模块。

职责：
  - 从任务消息中提取 GREEN 和 NIR 波段编号。
  - 委托基类 SpectralIndexProcessor 执行归一化差值计算。
  - NDWI 公式：(GREEN - NIR) / (GREEN + NIR + EPSILON)，值域 [-1, 1]。
    正值通常表示水体，负值表示植被或裸地。

设计要点：
  - 与 NdviProcessor 结构对称，使用相同的委托模式。
  - 默认波段：GREEN=2, NIR=4（Landsat 8/9 的波段约定）。
  - NDWI 与 NDVI 使用相同的归一化差值公式，区别仅在于选取的波段组合。
"""

from pathlib import Path

from clients.minio_client import MinioStorageClient
from processors.spectral_index_processor import SpectralIndexProcessor


class NdwiProcessor:
    """NDWI（归一化水体指数）处理器。

    NDWI 利用水体在绿光波段反射率高于近红外波段的光谱特性，
    可以有效提取水体信息，常用于水域提取和水体变化监测。
    """

    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path):
        """初始化 NDWI 处理器。

        Args:
            storage_client: MinIO 存储客户端（透传给 SpectralIndexProcessor）。
            temp_dir: 临时文件根目录（透传给 SpectralIndexProcessor）。
        """
        # 委托给基类处理器，指定指数名称为 "NDWI"。
        self._processor = SpectralIndexProcessor(
            storage_client, temp_dir, "NDWI"
        )

    def process(self, message: dict) -> dict:
        """执行 NDWI 计算。

        Args:
            message: 任务消息字典，其中 params 可选字段：
                - greenBand (int): 绿光波段编号，默认 2（Landsat 8 Band 3）。
                - nirBand (int): 近红外波段编号，默认 4（Landsat 8 Band 5）。

        Returns:
            结果字典（透传基类返回值，包含 outputBucket / outputObjectKey 及波段编号）。

        NDWI 公式详解：
          NDWI = (GREEN - NIR) / (GREEN + NIR + 1e-6)
          - 水体：GREEN > NIR（水体在绿光反射较强），NDWI 为正值
          - 植被：NIR > GREEN（植被在近红外反射较强），NDWI 为负值
        """
        params = message.get("params") or {}
        green_band = int(params.get("greenBand", 2))
        nir_band = int(params.get("nirBand", 4))
        # NDWI = (GREEN - NIR) / (GREEN + NIR + 1e-6)
        return self._processor.process_index(
            message,
            # first_band 是被减数（GREEN），second_band 是减数（NIR），
            # 保持与 NDVI 一致的公式结构。
            first_band=green_band,
            second_band=nir_band,
            band_names={"greenBand": green_band, "nirBand": nir_band},
        )
