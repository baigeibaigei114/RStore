"""NDVI 处理入口，输入应为已标准化的反射率影像。"""

from pathlib import Path

from clients.minio_client import MinioStorageClient
from utils.ndvi_baseline import process_ndvi


class NdviProcessor:
    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path):
        self._storage_client = storage_client
        self._temp_dir = temp_dir

    def process(self, message: dict) -> dict:
        params = message.get("params") or {}
        # 序号指文件中的实际位置，并非卫星原始波段编号。
        red_band = int(params.get("redBand", 3))
        nir_band = int(params.get("nirBand", 4))
        return process_ndvi(
            self._storage_client, self._temp_dir, message, red_band, nir_band
        )
