import argparse
import json
import sys
from pathlib import Path

import rasterio
from rasterio.warp import transform_bounds


def transform_to_list(transform):
    return [
        transform.a,
        transform.b,
        transform.c,
        transform.d,
        transform.e,
        transform.f,
    ]


def bounds_to_dict(bounds):
    return {
        "left": bounds.left,
        "bottom": bounds.bottom,
        "right": bounds.right,
        "top": bounds.top,
    }


def bounds_tuple_to_dict(bounds):
    left, bottom, right, top = bounds
    return {
        "left": left,
        "bottom": bottom,
        "right": right,
        "top": top,
    }


def parse_metadata(file_path):
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"GeoTIFF file does not exist: {path}")
    if not path.is_file():
        raise ValueError(f"Input path is not a file: {path}")

    with rasterio.open(path) as dataset:
        raw_bounds = dataset.bounds
        # transform_bounds 使用 densify_pts 可减少投影坐标系转 WGS84 时边界曲线带来的包络误差。
        wgs84_bounds = (
            transform_bounds(dataset.crs, "EPSG:4326", *raw_bounds, densify_pts=21)
            if dataset.crs
            else None
        )
        return {
            "width": dataset.width,
            "height": dataset.height,
            "bandCount": dataset.count,
            "crs": dataset.crs.to_string() if dataset.crs else None,
            "originalBounds": bounds_to_dict(raw_bounds) if dataset.crs else None,
            # 后端的 footprint 字段固定为 geometry(Polygon, 4326)，这里统一输出 WGS84 范围。
            "bounds": bounds_tuple_to_dict(wgs84_bounds) if wgs84_bounds else None,
            "boundsCrs": "EPSG:4326" if wgs84_bounds else None,
            "transform": transform_to_list(dataset.transform),
            "resolution": {
                "x": dataset.res[0],
                "y": dataset.res[1],
            },
            "nodata": dataset.nodata,
        }


def response(success, data=None, error=None):
    return {
        "success": success,
        "data": data,
        "error": error,
    }


def main():
    parser = argparse.ArgumentParser(description="Parse GeoTIFF metadata with rasterio.")
    parser.add_argument("file", help="Local GeoTIFF file path.")
    args = parser.parse_args()

    try:
        print(json.dumps(response(True, data=parse_metadata(args.file)), ensure_ascii=False))
        return 0
    except Exception as exc:
        print(json.dumps(response(False, error=f"Failed to parse GeoTIFF metadata: {exc}"), ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
