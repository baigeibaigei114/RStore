from pathlib import Path

import numpy as np
import rasterio


def read_band(dataset: rasterio.DatasetReader, band_index: int) -> np.ndarray:
    if band_index < 1 or band_index > dataset.count:
        raise ValueError(f"band index {band_index} out of range, dataset has {dataset.count} bands")
    return dataset.read(band_index).astype("float32")


def normalized_difference(first: np.ndarray, second: np.ndarray, epsilon: float = 1e-6) -> np.ndarray:
    denominator = first + second + epsilon
    return ((first - second) / denominator).astype("float32")


def write_single_band_geotiff(reference: rasterio.DatasetReader, output_path: Path, data: np.ndarray, nodata: float = -9999.0) -> None:
    profile = reference.profile.copy()
    profile.update(
        driver="GTiff",
        count=1,
        dtype="float32",
        nodata=nodata,
        compress="deflate",
    )

    safe_data = np.where(np.isfinite(data), data, nodata).astype("float32")
    with rasterio.open(output_path, "w", **profile) as dst:
        dst.write(safe_data, 1)


def write_mask_geotiff(reference: rasterio.DatasetReader, output_path: Path, mask: np.ndarray) -> None:
    profile = reference.profile.copy()
    profile.update(
        driver="GTiff",
        count=1,
        dtype="uint8",
        nodata=0,
        compress="deflate",
    )

    with rasterio.open(output_path, "w", **profile) as dst:
        dst.write(mask.astype("uint8"), 1)
