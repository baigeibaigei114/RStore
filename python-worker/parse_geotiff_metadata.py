import argparse
import json
import sys
from pathlib import Path

import rasterio
from math import cos, radians


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


def center_lat(bounds):
    return (bounds.bottom + bounds.top) / 2.0


def projected_unit_factor(crs):
    try:
        factor = crs.linear_units_factor
        if isinstance(factor, (list, tuple)) and len(factor) >= 2:
            return float(factor[1])
        if isinstance(factor, (int, float)):
            return float(factor)
    except Exception:
        return None
    return None


def estimate_resolution_meter(dataset):
    if not dataset.crs:
        return None

    x_res = abs(dataset.res[0])
    y_res = abs(dataset.res[1])
    if dataset.crs.is_projected:
        factor = projected_unit_factor(dataset.crs) or 1.0
        return max(x_res, y_res) * factor

    if dataset.crs.is_geographic:
        lat = center_lat(dataset.bounds)
        meters_per_degree_lon = 111320.0 * cos(radians(lat))
        meters_per_degree_lat = 110574.0
        return max(x_res * abs(meters_per_degree_lon), y_res * meters_per_degree_lat)

    return None


def parse_metadata(file_path):
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"GeoTIFF 文件不存在：{path}")
    if not path.is_file():
        raise ValueError(f"输入路径不是文件：{path}")

    with rasterio.open(path) as dataset:
        metadata = {
            "width": dataset.width,
            "height": dataset.height,
            "bandCount": dataset.count,
            "crs": dataset.crs.to_string() if dataset.crs else None,
            # 无坐标系时 bounds 不具备明确地理含义，调用方不应直接生成空间范围。
            "bounds": bounds_to_dict(dataset.bounds) if dataset.crs else None,
            "transform": transform_to_list(dataset.transform),
            "resolution": {
                "x": dataset.res[0],
                "y": dataset.res[1],
            },
            "resolutionUnit": "degree" if dataset.crs and dataset.crs.is_geographic else (
                dataset.crs.linear_units if dataset.crs and dataset.crs.is_projected else None
            ),
            "resolutionMeter": estimate_resolution_meter(dataset),
            "nodata": dataset.nodata,
        }
        return metadata


def success_response(data):
    return {
        "success": True,
        "data": data,
    }


def error_response(message):
    return {
        "success": False,
        "error": message,
    }


def main():
    parser = argparse.ArgumentParser(description="使用 rasterio 解析 GeoTIFF 元数据。")
    parser.add_argument("file", help="本地 GeoTIFF 文件路径。")
    args = parser.parse_args()

    try:
        metadata = parse_metadata(args.file)
        print(json.dumps(success_response(metadata), ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        print(json.dumps(error_response(f"GeoTIFF 元数据解析失败：{exc}"), ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
