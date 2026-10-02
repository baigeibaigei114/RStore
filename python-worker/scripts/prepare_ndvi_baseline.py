"""生成手算样本或从本地 Sentinel-2 波段准备标准化反射率裁剪。

真实模式必须显式提供产品元数据中的 scale/offset（reflectance=DN*scale+offset）。
SCL 使用最近邻对齐，保留类别 4/5/6/7；不把质量掩膜当植被分类真值。
"""

import argparse
import hashlib
import json
from datetime import datetime
from pathlib import Path

import numpy as np
import rasterio
from rasterio.enums import Resampling
from rasterio.vrt import WarpedVRT
from rasterio.windows import Window
from rasterio.transform import from_origin

NODATA = -9999.0
BAND_NAMES = ("B02", "B03", "B04", "B08")


def write_stack(path, data, crs, transform, provenance):
    path = Path(path)
    manifest = path.with_suffix(".json")
    if path.exists() or manifest.exists():
        raise FileExistsError(f"Refusing to overwrite {path} or its manifest")
    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(
        path, "w", driver="GTiff", height=data.shape[1], width=data.shape[2],
        count=4, dtype="float32", crs=crs, transform=transform,
        nodata=NODATA, compress="deflate",
    ) as output:
        output.write(np.where(np.isfinite(data), data, NODATA).astype("float32"))
        output.descriptions = BAND_NAMES
        output.update_tags(input_units="surface_reflectance", baseline_scope="cropped_raster")
    provenance.update({
        "schemaVersion": 1,
        "productType": "SYNTHETIC" if provenance.get("case") else "SENTINEL2_L2A",
        "preprocessingVersion": "s2-reflectance-v1",
        "inputUnits": "surface_reflectance",
        "bandOrder": list(BAND_NAMES), "scope": "cropped_raster",
        "crs": str(crs), "transform": list(transform)[:6],
        "width": data.shape[2], "height": data.shape[1],
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    })
    if "calibration" not in provenance and "scale" in provenance and "offset" in provenance:
        provenance["calibration"] = {
            band: {"scale": provenance["scale"], "offset": provenance["offset"]}
            for band in ("blue", "green", "red", "nir")
        }
    manifest.write_text(json.dumps(provenance, indent=2, allow_nan=False), encoding="utf-8")


def fixture(output, case="valid"):
    data = np.full((4, 2, 2), 0.2, dtype="float32")
    data[3] = [[0.6, 0.6], [0.2, NODATA]]
    if case == "all-nodata":
        data[:] = NODATA
    elif case == "zero-denominator":
        data[3] = -data[2]
    write_stack(output, data, "EPSG:32650", from_origin(390000, 3200000, 10, 10), {
        "source": "synthetic; not a satellite observation",
        "case": case,
        "expectedFailure": None if case == "valid" else "no valid pixels",
        "expectedNdvi": [[0.5, 0.5], [0.0, None]] if case == "valid" else None,
        "expectedStatistics": {"validPixelCount": 3, "invalidPixelCount": 1,
                               "min": 0, "max": 0.5, "mean": 1 / 3} if case == "valid" else None,
    })


def prepare_real(args):
    paths = [args.blue, args.green, args.red, args.nir]
    if not all(paths) or not args.scl or args.scale is None or args.offset is None or not args.source_id:
        raise ValueError("Real mode requires four bands, SCL, scale, offset and source-id")
    if not args.capture_time or datetime.fromisoformat(args.capture_time.replace("Z", "+00:00")).tzinfo is None:
        raise ValueError("capture-time with timezone is required")
    if not np.isfinite([args.scale, args.offset]).all() or args.scale <= 0:
        raise ValueError("scale must be positive and scale/offset must be finite")
    col, row, width, height = args.window
    if min(col, row) < 0 or min(width, height) <= 0 or max(width, height) > 2048:
        raise ValueError("Window must be inside raster and at most 2048 x 2048")
    window = Window(col, row, width, height)
    with rasterio.open(args.red) as reference:
        if col + width > reference.width or row + height > reference.height:
            raise ValueError("Window exceeds source raster")
        crs = reference.crs
        if crs is None:
            raise ValueError("Source CRS is required")
        transform = reference.window_transform(window)
        arrays = []
        for path in paths:
            with rasterio.open(path) as band:
                if (band.crs != crs or band.transform != reference.transform
                        or band.width != reference.width or band.height != reference.height):
                    raise ValueError("Spectral bands must share the same grid")
                raw = band.read(1, window=window, masked=True).astype("float32").filled(np.nan)
                # Sentinel-2 DN=0 是 NoData，即使源文件未声明也应排除。
                raw[raw == 0] = np.nan
                arrays.append(raw * args.scale + args.offset)
        with rasterio.open(args.scl) as source:
            with WarpedVRT(source, crs=crs, transform=transform, width=width,
                           height=height, resampling=Resampling.nearest) as aligned:
                quality = aligned.read(1, masked=True).filled(0)
    data = np.stack(arrays)
    valid = np.isfinite(data).all(axis=0) & np.isin(quality, [4, 5, 6, 7])
    if not valid.any():
        raise ValueError("Selected window has no valid pixels")
    data[:, ~valid] = NODATA
    write_stack(args.output, data, crs, transform, {
        "sourceId": args.source_id, "captureTime": args.capture_time,
        "sources": [str(Path(p).name) for p in paths],
        "window": args.window, "scale": args.scale, "offset": args.offset,
        "sclValidClasses": [4, 5, 6, 7], "sclResampling": "nearest",
        "inputUnits": "surface_reflectance",
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fixture", action="store_true")
    parser.add_argument("--fixture-case", choices=["valid", "all-nodata", "zero-denominator"],
                        default="valid")
    for name in ("blue", "green", "red", "nir", "scl", "source-id", "capture-time"):
        parser.add_argument("--" + name)
    parser.add_argument("--scale", type=float)
    parser.add_argument("--offset", type=float)
    parser.add_argument("--window", nargs=4, type=int, default=[0, 0, 500, 500],
                        metavar=("COL", "ROW", "WIDTH", "HEIGHT"))
    args = parser.parse_args()
    if args.fixture:
        fixture(args.output, args.fixture_case)
    else:
        prepare_real(args)
    print(f"Created {args.output}")


if __name__ == "__main__":
    main()
