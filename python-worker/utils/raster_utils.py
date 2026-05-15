from pathlib import Path

import numpy as np
import rasterio


def read_band(dataset: rasterio.DatasetReader, band_index: int) -> np.ndarray:
    if band_index < 1 or band_index > dataset.count:
        raise ValueError(f"波段编号 {band_index} 超出范围，当前影像共有 {dataset.count} 个波段")
    return dataset.read(band_index).astype("float32")


def normalized_difference(first: np.ndarray, second: np.ndarray, epsilon: float = 1e-6) -> np.ndarray:
    denominator = first + second + epsilon
    return ((first - second) / denominator).astype("float32")


def write_single_band_geotiff(reference: rasterio.DatasetReader, output_path: Path, data: np.ndarray, nodata: float = -9999.0) -> None:
    # 复用参考影像 profile，确保输出结果继承 CRS、transform、分辨率等空间定位信息。
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
    # 掩膜只表达是否变化，使用 uint8 可以显著减小结果文件体积。
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
