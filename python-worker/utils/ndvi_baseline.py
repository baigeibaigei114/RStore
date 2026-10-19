"""NDVI v1：掩膜感知计算、结果统计及可重复读取的输出标记。"""

from pathlib import Path
import hashlib
import json
from tempfile import TemporaryDirectory

import numpy as np
import rasterio

from utils.raster_utils import write_single_band_geotiff
from utils.region_mask import region_mask, snapshot_digest

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


def summarize_ndvi(values: np.ndarray, red_band: int, nir_band: int, region=None, mask=None) -> dict:
    valid = values[np.isfinite(values) & mask] if region is not None else values[np.isfinite(values)]
    if valid.size == 0:
        raise ValueError("NDVI has no valid pixels after masking")
    total = int(mask.sum()) if region is not None else int(values.size)
    count = int(valid.size)
    metadata = {
        "schemaVersion": 2 if region is not None else 1,
        "algorithm": "NDVI",
        "scope": "monitoring_region" if region is not None else "cropped_raster",
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
    if region is not None:
        metadata.update(regionSnapshot=region, coverageRatio=1)
    return metadata


def read_result_metadata(path: Path, red_band: int, nir_band: int, region=None) -> dict:
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
        mask = None
        if region is not None:
            if tags.get("region_snapshot_sha256") != snapshot_digest(region):
                raise ValueError("Existing output does not match region snapshot")
            mask = region_mask(dataset, region)
            if np.isfinite(values[~mask]).any():
                raise ValueError("Existing regional output has values outside region")
        elif tags.get("region_snapshot_sha256"):
            raise ValueError("Regional output cannot be reused by a rectangular task")
        return summarize_ndvi(values, red_band, nir_band, region, mask)


def process_ndvi(storage, temp_dir: Path, message: dict, red_band: int, nir_band: int) -> dict:
    temp_dir.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix="ndvi_", dir=temp_dir) as directory:
        work_dir = Path(directory)
        output_path = work_dir / "ndvi.tif"
        bucket = message["outputBucket"]
        key = message["outputObjectKey"]
        snapshot = message.get("params", {}).get("inputSnapshot")
        region = message.get("params", {}).get("regionSnapshot")
        if region is not None and snapshot is None:
            raise ValueError("Regional NDVI requires admitted input snapshot")
        snapshot_hash = None
        if snapshot is not None:
            if (snapshot.get("status") != "PASSED"
                    or snapshot.get("validatorVersion") != "input-v1"
                    or snapshot.get("algorithmVersion") != "ndvi-v1"
                    or snapshot.get("denominatorTolerance") != DENOMINATOR_TOLERANCE
                    or (red_band, nir_band) != (3, 4)):
                raise ValueError("Invalid NDVI input snapshot")
            snapshot_hash = hashlib.sha256(
                json.dumps(snapshot, sort_keys=True, separators=(",", ":")).encode()
            ).hexdigest()
        reused = storage.object_exists(bucket, key)
        if reused:
            storage.download_file(bucket, key, output_path)
            if snapshot_hash:
                with rasterio.open(output_path) as output:
                    if output.tags().get("input_snapshot_sha256") != snapshot_hash:
                        raise ValueError("Existing output does not match input snapshot")
        else:
            input_path = work_dir / "input.tif"
            storage.download_file(message["inputBucket"], message["inputObjectKey"], input_path)
            if snapshot_hash:
                with input_path.open("rb") as source:
                    digest = hashlib.file_digest(source, "sha256").hexdigest()
                if digest != snapshot["manifest"].get("sha256", "").lower():
                    raise ValueError("Input SHA256 does not match admitted file")
            with rasterio.open(input_path) as dataset:
                if snapshot_hash and (
                    dataset.tags().get("input_units") != "surface_reflectance"
                    or dataset.count != 4
                    or dataset.descriptions != ("B02", "B03", "B04", "B08")
                    or dataset.crs is None
                    or max(dataset.width, dataset.height) > 2048
                ):
                    raise ValueError("Input raster does not satisfy admitted contract")
                values = calculate_ndvi(dataset, red_band, nir_band)
                if region is not None:
                    mask = region_mask(dataset, region)
                    values[~mask] = np.nan
                    if not np.isfinite(values).any():
                        raise ValueError("Region has no valid NDVI pixels after masking")
                write_single_band_geotiff(dataset, output_path, values, nodata=float("nan"))
            with rasterio.open(output_path, "r+") as output:
                output.update_tags(
                    **{VERSION_TAG: "1", "redBand": str(red_band), "nirBand": str(nir_band)}
                )
                if snapshot_hash:
                    output.update_tags(input_snapshot_sha256=snapshot_hash)
                if region is not None:
                    output.update_tags(region_snapshot_sha256=snapshot_digest(region))
        # 从真正交付的全分辨率栅格读取，使首次执行与重试口径一致。
        metadata = read_result_metadata(output_path, red_band, nir_band, region)
        if not reused:
            storage.upload_file(bucket, key, output_path)
        return {
            "outputBucket": bucket,
            "outputObjectKey": key,
            "redBand": red_band,
            "nirBand": nir_band,
            "resultMetadata": metadata,
        }
