"""读取固定公开 Sentinel-2 COG 的 500x500 窗口，不下载整景。

依赖现有 Worker 的 rasterio/numpy/requests。运行时参数 --output 指向未存在的 tif。
数据来自 Earth Search / Copernicus，保留 STAC 元数据便于核查 scale/offset。
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import requests
import rasterio
from rasterio.enums import Resampling
from rasterio.vrt import WarpedVRT
from rasterio.warp import transform as transform_coordinates
from rasterio.windows import Window

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python-worker"))
from scripts.prepare_ndvi_baseline import NODATA, write_stack

ITEM_ID = "S2B_50RLT_20241129_0_L2A"
ITEM_URL = f"https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a/items/{ITEM_ID}"


def calibration_for_cog(item, asset):
    band = asset["raster:bands"][0]
    applied = item["properties"].get("earthsearch:boa_offset_applied")
    if not isinstance(applied, bool):
        raise ValueError("Legacy Earth Search COG offset status is unknown")
    # 旧 sentinel-2-l2a COG 已执行偏移时，asset 仍可能保留原始 JP2 的 offset。
    # 依据提供方说明：https://github.com/Element84/earth-search/discussions/26
    return band["scale"], 0.0 if applied else band["offset"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if any(args.output.with_suffix(suffix).exists() for suffix in (".tif", ".json", ".stac.json")):
        raise FileExistsError("Sample or manifest already exists")
    response = requests.get(ITEM_URL, timeout=45)
    response.raise_for_status()
    item = response.json()
    assets = item["assets"]
    arrays = []
    calibration = {}
    with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR",
                      GDAL_HTTP_TIMEOUT="180", GDAL_HTTP_MAX_RETRY="2",
                      GDAL_HTTP_MULTIRANGE="SERIAL",
                      GDAL_HTTP_MERGE_CONSECUTIVE_RANGES="NO"):
        with rasterio.open(assets["red"]["href"]) as reference:
            xs, ys = transform_coordinates("EPSG:4326", reference.crs, [116.025], [28.925])
            row, col = reference.index(xs[0], ys[0])
            window = Window(col - 250, row - 250, 500, 500)
            if (window.col_off < 0 or window.row_off < 0
                    or window.col_off + 500 > reference.width
                    or window.row_off + 500 > reference.height):
                raise ValueError("Requested region falls outside selected scene")
            crs = reference.crs
            transform = reference.window_transform(window)
            for name in ("blue", "green", "red", "nir"):
                asset = assets[name]
                band_info = asset["raster:bands"][0]
                scale, offset = calibration_for_cog(item, asset)
                calibration[name] = {"scale": scale, "offset": offset,
                                     "catalogOffset": band_info["offset"]}
                print(f"Reading {name} window", flush=True)
                with rasterio.open(asset["href"]) as band:
                    if (band.crs != crs or band.transform != reference.transform
                            or band.shape != reference.shape):
                        raise ValueError("Unexpected spectral grid")
                    raw = band.read(1, window=window, masked=True).astype("float32").filled(np.nan)
                    raw[raw == 0] = np.nan
                    arrays.append(raw * scale + offset)
        print("Reading SCL window", flush=True)
        with rasterio.open(assets["scl"]["href"]) as source:
            with WarpedVRT(source, crs=crs, transform=transform, width=500, height=500,
                           resampling=Resampling.nearest) as aligned:
                quality = aligned.read(1, masked=True).filled(0)
    data = np.stack(arrays)
    valid = np.isfinite(data).all(axis=0) & np.isin(quality, [4, 5, 6, 7])
    if not valid.any():
        raise ValueError("No valid pixels in sample")
    data[:, ~valid] = NODATA
    write_stack(args.output, data, crs, transform, {
        "source": "Copernicus Sentinel-2 L2A via Element 84 Earth Search",
        "sourceId": ITEM_ID, "stacUrl": ITEM_URL,
        "captureTime": item["properties"]["datetime"],
        "sceneCloudCover": item["properties"]["eo:cloud_cover"],
        "calibration": calibration, "window": list(window.flatten()),
        "boaOffsetAlreadyApplied": item["properties"]["earthsearch:boa_offset_applied"],
        "sclValidClasses": [4, 5, 6, 7], "sclResampling": "nearest",
        "validInputPixels": int(valid.sum()), "inputUnits": "surface_reflectance",
        "expectedNdviRange": [-1, 1],
    })
    args.output.with_suffix(".stac.json").write_text(
        json.dumps(item, indent=2, allow_nan=False), encoding="utf-8")
    print(f"Created {args.output}; valid input pixels={valid.sum()}")


if __name__ == "__main__":
    main()
