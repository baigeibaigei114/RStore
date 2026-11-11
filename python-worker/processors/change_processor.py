"""Two-date absolute difference on the common valid part of an aligned grid."""

import math
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np
import rasterio

from clients.minio_client import MinioStorageClient
from utils.raster_utils import CHANGE_NODATA, read_valid_band, write_mask_geotiff
from utils.result_analysis import VERSION_TAG, index_result_tags, index_statistics, read_index_result


class ChangeDetectionProcessor:
    DEFAULT_BAND = 1
    DEFAULT_THRESHOLD = 0.2

    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path):
        self._storage_client = storage_client
        self._temp_dir = temp_dir

    def process(self, message: dict) -> dict:
        bucket, key = message["outputBucket"], message["outputObjectKey"]
        output = {"outputBucket": bucket, "outputObjectKey": key}
        reused = self._storage_client.object_exists(bucket, key)
        self._temp_dir.mkdir(parents=True, exist_ok=True)
        with TemporaryDirectory(prefix="change_", dir=self._temp_dir) as folder:
            work = Path(folder)
            result_path = work / "change.tif"
            if reused:
                self._storage_client.download_file(bucket, key, result_path)
                with rasterio.open(result_path) as result:
                    if VERSION_TAG not in result.tags():
                        return {**output, "skippedReason": "结果对象已存在"}

            params = message.get("params") or {}
            band = int(params.get("band", self.DEFAULT_BAND))
            threshold = float(params.get("threshold", self.DEFAULT_THRESHOLD))
            if band < 1 or not math.isfinite(threshold) or threshold < 0:
                raise ValueError("Change detection requires a positive band and finite nonnegative threshold")
            comparison = {"beforeImageId": params.get("beforeImageId"), "afterImageId": params.get("afterImageId"),
                          "band": band, "threshold": threshold}
            bound_images = (type(comparison["beforeImageId"]) is int and type(comparison["afterImageId"]) is int
                            and min(comparison["beforeImageId"], comparison["afterImageId"]) > 0
                            and comparison["beforeImageId"] != comparison["afterImageId"])

            if not reused:
                before_key = params.get("beforeObjectKey")
                after_key = params.get("afterObjectKey")
                before_bucket = params.get("beforeBucket") or message.get("inputBucket")
                after_bucket = params.get("afterBucket") or message.get("inputBucket")
                if not all((before_key, after_key, before_bucket, after_bucket)):
                    raise ValueError("Change detection requires two bound input rasters")
                before_path, after_path = work / "before.tif", work / "after.tif"
                self._storage_client.download_file(before_bucket, before_key, before_path)
                self._storage_client.download_file(after_bucket, after_key, after_path)
                with rasterio.open(before_path) as before_ds, rasterio.open(after_path) as after_ds:
                    self._validate_compatible(before_ds, after_ds)
                    if band > min(before_ds.count, after_ds.count):
                        raise ValueError("Change detection band is outside the source raster")
                    difference = read_valid_band(after_ds, band, dtype="float64")
                    before = read_valid_band(before_ds, band, dtype="float64")
                    valid = np.isfinite(difference) & np.isfinite(before)
                    with np.errstate(over="ignore", invalid="ignore"):
                        np.subtract(difference, before, out=difference)
                        np.abs(difference, out=difference)
                    del before
                    if not valid.any():
                        raise ValueError("Change detection has no common valid pixels")
                    mask = np.full(difference.shape, CHANGE_NODATA, dtype="uint8")
                    np.greater(difference, threshold, out=mask, where=valid)
                    write_mask_geotiff(after_ds, result_path, mask)
                    del difference, valid, mask
                    geographic_grid = after_ds.crs is not None and not after_ds.transform.is_degenerate
                if bound_images and geographic_grid:
                    with rasterio.open(result_path, "r+") as result:
                        result.update_tags(**index_result_tags("CHANGE_DETECTION", comparison))

            with rasterio.open(result_path) as result:
                if VERSION_TAG in result.tags():
                    if not bound_images:
                        raise ValueError("Change raster has no verifiable input pair")
                    values = read_index_result(result, "CHANGE_DETECTION", comparison)
                    stats = index_statistics(values)
                    changed = int(np.count_nonzero(values == 1))
                    valid_count = stats["validPixelCount"]
                    stats.update(changedPixelCount=changed, unchangedPixelCount=valid_count - changed,
                                 changeRatio=changed / valid_count)
                    output["resultMetadata"] = {
                        "schemaVersion": 1, "algorithm": "CHANGE_DETECTION", "scope": "cropped_raster",
                        "comparison": comparison, "statistics": stats,
                        "grid": {"width": result.width, "height": result.height, "alignment": "same_grid"},
                    }
            if not reused:
                self._storage_client.upload_file(bucket, key, result_path)
            return {**output, "band": band, "threshold": threshold}

    def _validate_compatible(self, before, after) -> None:
        if before.shape != after.shape:
            raise ValueError("前后两期 GeoTIFF 尺寸不一致")
        if before.crs != after.crs:
            raise ValueError("前后两期 GeoTIFF 坐标系不一致")
        if before.transform != after.transform:
            raise ValueError("前后两期 GeoTIFF 仿射变换不一致")
