from pathlib import Path
import shutil
from uuid import uuid4

import numpy as np
import rasterio

from clients.minio_client import MinioStorageClient
from utils.raster_utils import read_band, write_mask_geotiff


class ChangeDetectionProcessor:
    DEFAULT_BAND = 1
    DEFAULT_THRESHOLD = 0.2

    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path):
        self._storage_client = storage_client
        self._temp_dir = temp_dir

    def process(self, message: dict) -> dict:
        if self._storage_client.object_exists(message["outputBucket"], message["outputObjectKey"]):
            return {
                "outputBucket": message["outputBucket"],
                "outputObjectKey": message["outputObjectKey"],
                "skippedReason": "结果对象已存在",
            }

        params = message.get("params") or {}
        before_object_key = params.get("beforeObjectKey")
        after_object_key = params.get("afterObjectKey")
        if not before_object_key or not after_object_key:
            raise ValueError("CHANGE_DETECTION 需要 params.beforeObjectKey 和 params.afterObjectKey")

        band = int(params.get("band", self.DEFAULT_BAND))
        threshold = float(params.get("threshold", self.DEFAULT_THRESHOLD))
        input_bucket = message.get("inputBucket")
        before_bucket = params.get("beforeBucket") or input_bucket
        after_bucket = params.get("afterBucket") or input_bucket
        if not before_bucket or not after_bucket:
            raise ValueError("CHANGE_DETECTION 需要 inputBucket 或 params.beforeBucket/params.afterBucket")

        work_dir = self._temp_dir / f"change_{message['taskId']}_{uuid4().hex}"
        work_dir.mkdir(parents=True, exist_ok=True)
        before_path = work_dir / "before.tif"
        after_path = work_dir / "after.tif"
        output_path = work_dir / "change_mask.tif"

        try:
            self._storage_client.download_file(before_bucket, before_object_key, before_path)
            self._storage_client.download_file(after_bucket, after_object_key, after_path)

            with rasterio.open(before_path) as before_dataset, rasterio.open(after_path) as after_dataset:
                self._validate_compatible(before_dataset, after_dataset)
                before = read_band(before_dataset, band)
                after = read_band(after_dataset, band)
                diff = np.abs(after - before)
                mask = np.where(diff > threshold, 1, 0).astype("uint8")
                # 变化结果以 after 影像作为空间参考，便于表达变化后的空间位置。
                write_mask_geotiff(after_dataset, output_path, mask)

            self._storage_client.upload_file(message["outputBucket"], message["outputObjectKey"], output_path)
            return {
                "outputBucket": message["outputBucket"],
                "outputObjectKey": message["outputObjectKey"],
                "beforeObjectKey": before_object_key,
                "afterObjectKey": after_object_key,
                "band": band,
                "threshold": threshold,
            }
        finally:
            shutil.rmtree(work_dir, ignore_errors=True)

    def _validate_compatible(self, before_dataset: rasterio.DatasetReader, after_dataset: rasterio.DatasetReader) -> None:
        if before_dataset.width != after_dataset.width or before_dataset.height != after_dataset.height:
            raise ValueError(
                "前后两期 GeoTIFF 尺寸不一致："
                f"before={before_dataset.width}x{before_dataset.height}, "
                f"after={after_dataset.width}x{after_dataset.height}"
            )
        if before_dataset.crs != after_dataset.crs:
            raise ValueError(f"前后两期 GeoTIFF 坐标系不一致：before={before_dataset.crs}, after={after_dataset.crs}")
        if before_dataset.transform != after_dataset.transform:
            raise ValueError("前后两期 GeoTIFF 仿射变换不一致")
