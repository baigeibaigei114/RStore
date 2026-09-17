"""NDVI v1：掩膜感知计算、结果统计及可重复读取的输出标记。"""

from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np
import rasterio

from utils.raster_utils import write_single_band_geotiff

DENOMINATOR_TOLERANCE = 1e-6
VERSION_TAG = "ndvi_baseline_version"


def _read_valid_band(dataset, band: int) -> np.ndarray:
    values = dataset.read(band, masked=True).astype("float64").filled(np.nan)
    # 显式 dataset mask 在 GDAL 中可能覆盖 nodata 掩膜，需要取两者交集。
    nodata = dataset.nodatavals[band - 1]
    if nodata is not None:
        values[values == nodata] = np.nan
    return values


def calculate_ndvi(dataset, red_band: int, nir_band: int) -> np.ndarray:
    if red_band == nir_band or not all(
        1 <= band <= dataset.count for band in (red_band, nir_band)
    ):
        raise ValueError("NDVI requires distinct, existing red and NIR bands")
    red = _read_valid_band(dataset, red_band)
    nir = _read_valid_band(dataset, nir_band)
    denominator = nir + red
    valid = (
        np.isfinite(red)
        & np.isfinite(nir)
        & (np.abs(denominator) > DENOMINATOR_TOLERANCE)
    )
    values = np.full(red.shape, np.nan, dtype="float64")
    with np.errstate(divide="ignore", invalid="ignore", over="ignore"):
        np.divide(nir - red, denominator, out=values, where=valid)
        values = values.astype("float32")
    values[~np.isfinite(values)] = np.nan
    if not np.isfinite(values).any():
        raise ValueError("NDVI has no valid pixels after masking")
    return values


def summarize_ndvi(values: np.ndarray, red_band: int, nir_band: int) -> dict:
    valid = values[np.isfinite(values)]
    if valid.size == 0:
        raise ValueError("NDVI has no valid pixels after masking")
    total = int(values.size)
    count = int(valid.size)
    return {
        "schemaVersion": 1,
        "algorithm": "NDVI",
        "scope": "cropped_raster",
        "bandMapping": {"redBand": red_band, "nirBand": nir_band},
        "statistics": {
            "totalPixelCount": total,
            "validPixelCount": count,
            "invalidPixelCount": total - count,
            "validPixelRatio": count / total,
            "min": float(valid.min()),
            "max": float(valid.max()),
            "mean": float(valid.mean(dtype="float64")),
        },
    }


def read_result_metadata(path: Path, red_band: int, nir_band: int) -> dict:
    with rasterio.open(path) as dataset:
        tags = dataset.tags()
        if (
            dataset.count != 1
            or tags.get(VERSION_TAG) != "1"
            or tags.get("redBand") != str(red_band)
            or tags.get("nirBand") != str(nir_band)
        ):
            raise ValueError("Existing NDVI output is not compatible with v1; create a new task")
        values = dataset.read(1, masked=True).astype("float32").filled(np.nan)
        return summarize_ndvi(values, red_band, nir_band)


def process_ndvi(storage, temp_dir: Path, message: dict, red_band: int, nir_band: int) -> dict:
    temp_dir.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix="ndvi_", dir=temp_dir) as directory:
        work_dir = Path(directory)
        output_path = work_dir / "ndvi.tif"
        bucket = message["outputBucket"]
        key = message["outputObjectKey"]
        reused = storage.object_exists(bucket, key)
        if reused:
            storage.download_file(bucket, key, output_path)
        else:
            input_path = work_dir / "input.tif"
            storage.download_file(message["inputBucket"], message["inputObjectKey"], input_path)
            with rasterio.open(input_path) as dataset:
                values = calculate_ndvi(dataset, red_band, nir_band)
                write_single_band_geotiff(dataset, output_path, values, nodata=float("nan"))
            with rasterio.open(output_path, "r+") as output:
                output.update_tags(
                    **{VERSION_TAG: "1", "redBand": str(red_band), "nirBand": str(nir_band)}
                )
        # 从真正交付的全分辨率栅格读取，使首次执行与重试口径一致。
        metadata = read_result_metadata(output_path, red_band, nir_band)
        if not reused:
            storage.upload_file(bucket, key, output_path)
        return {
            "outputBucket": bucket,
            "outputObjectKey": key,
            "redBand": red_band,
            "nirBand": nir_band,
            "resultMetadata": metadata,
        }
