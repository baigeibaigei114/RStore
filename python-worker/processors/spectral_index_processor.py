from pathlib import Path
import shutil
from uuid import uuid4

import rasterio

from clients.minio_client import MinioStorageClient
from utils.raster_utils import normalized_difference, read_band, write_single_band_geotiff


class SpectralIndexProcessor:
    EPSILON = 1e-6

    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path, index_name: str):
        self._storage_client = storage_client
        self._temp_dir = temp_dir
        self._index_name = index_name

    def process_index(self, message: dict, first_band: int, second_band: int, band_names: dict[str, int]) -> dict:
        work_dir = self._temp_dir / f"{self._index_name.lower()}_{message['taskId']}_{uuid4().hex}"
        work_dir.mkdir(parents=True, exist_ok=True)

        input_path = work_dir / "input.tif"
        output_path = work_dir / f"{self._index_name.lower()}.tif"
        try:
            # Worker 消息只携带对象路径，计算前需要先把 GeoTIFF 拉到本地临时目录。
            self._storage_client.download_file(message["inputBucket"], message["inputObjectKey"], input_path)

            with rasterio.open(input_path) as dataset:
                first = read_band(dataset, first_band)
                second = read_band(dataset, second_band)
                index_data = normalized_difference(first, second, epsilon=self.EPSILON)
                write_single_band_geotiff(dataset, output_path, index_data)

            self._storage_client.upload_file(message["outputBucket"], message["outputObjectKey"], output_path)
            return {
                "outputBucket": message["outputBucket"],
                "outputObjectKey": message["outputObjectKey"],
                **band_names,
            }
        finally:
            shutil.rmtree(work_dir, ignore_errors=True)
