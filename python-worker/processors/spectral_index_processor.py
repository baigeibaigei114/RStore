"""Compute an index once and retain the validity mask in its GeoTIFF."""

from pathlib import Path
from tempfile import TemporaryDirectory

import rasterio

from clients.minio_client import MinioStorageClient
from utils.raster_utils import normalized_difference, read_valid_band, write_single_band_geotiff
from utils.result_analysis import VERSION_TAG, index_result_tags, index_statistics, read_index_result


class SpectralIndexProcessor:
    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path, index_name: str):
        self._storage_client = storage_client
        self._temp_dir = temp_dir
        self._index_name = index_name

    def process_index(self, message: dict, first_band: int, second_band: int, band_names: dict[str, int]) -> dict:
        if first_band == second_band or min(first_band, second_band) < 1:
            raise ValueError("Index requires distinct, positive band indices")
        self._temp_dir.mkdir(parents=True, exist_ok=True)
        bucket, key = message["outputBucket"], message["outputObjectKey"]
        output = {"outputBucket": bucket, "outputObjectKey": key, **band_names}
        reused = self._storage_client.object_exists(bucket, key)
        with TemporaryDirectory(prefix=self._index_name.lower() + "_", dir=self._temp_dir) as folder:
            input_path = Path(folder) / "input.tif"
            result_path = Path(folder) / "result.tif"
            if reused:
                self._storage_client.download_file(bucket, key, result_path)
            else:
                self._storage_client.download_file(message["inputBucket"], message["inputObjectKey"], input_path)
                with rasterio.open(input_path) as dataset:
                    if max(first_band, second_band) > dataset.count:
                        raise ValueError("Index band is outside the source raster")
                    values = normalized_difference(read_valid_band(dataset, first_band),
                                                   read_valid_band(dataset, second_band))
                    write_single_band_geotiff(dataset, result_path, values, nodata=float("nan"))
                with rasterio.open(result_path, "r+") as result:
                    result.update_tags(**index_result_tags(self._index_name, band_names))

            with rasterio.open(result_path) as result:
                if reused and VERSION_TAG not in result.tags():
                    return {**output, "skippedReason": "结果对象已存在"}
                statistics = index_statistics(read_index_result(result, self._index_name, band_names))
            if not reused:
                self._storage_client.upload_file(bucket, key, result_path)
            return {**output, "resultMetadata": {
                "schemaVersion": 1, "algorithm": self._index_name, "scope": "cropped_raster",
                "bandMapping": band_names, "statistics": statistics,
            }}
