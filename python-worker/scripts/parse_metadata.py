import argparse
import json
import sys
from pathlib import Path

import rasterio
from rasterio.warp import transform_bounds
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


def bounds_tuple_to_dict(bounds):
    left, bottom, right, top = bounds
    return {
        "left": left,
        "bottom": bottom,
        "right": right,
        "top": top,
    }


def center_lat(bounds):
    if not bounds:
        return 0.0
    return (bounds[1] + bounds[3]) / 2.0


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


def estimate_resolution_meter(dataset, wgs84_bounds):
    if not dataset.crs:
        return None

    x_res = abs(dataset.res[0])
    y_res = abs(dataset.res[1])
    if dataset.crs.is_projected:
        factor = projected_unit_factor(dataset.crs) or 1.0
        return max(x_res, y_res) * factor

    if dataset.crs.is_geographic:
        lat = center_lat(wgs84_bounds)
        meters_per_degree_lon = 111320.0 * cos(radians(lat))
        meters_per_degree_lat = 110574.0
        x_meter = x_res * abs(meters_per_degree_lon)
        y_meter = y_res * meters_per_degree_lat
        return max(x_meter, y_meter)

    return None


def parse_metadata(file_path):
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"GeoTIFF 文件不存在：{path}")
    if not path.is_file():
        raise ValueError(f"输入路径不是文件：{path}")

    with rasterio.open(path) as dataset:
        raw_bounds = dataset.bounds
        # transform_bounds 使用 densify_pts 可减少投影坐标系转 WGS84 时边界曲线带来的包络误差。
        wgs84_bounds = (
            transform_bounds(dataset.crs, "EPSG:4326", *raw_bounds, densify_pts=21)
            if dataset.crs
            else None
        )
        resolution_meter = estimate_resolution_meter(dataset, wgs84_bounds)
        return {
            "width": dataset.width,
            "height": dataset.height,
            "bandCount": dataset.count,
            "crs": dataset.crs.to_string() if dataset.crs else None,
            "originalBounds": bounds_to_dict(raw_bounds) if dataset.crs else None,
            # 后端 footprint 字段固定为 geometry(Polygon, 4326)，这里统一输出 WGS84 范围。
            "bounds": bounds_tuple_to_dict(wgs84_bounds) if wgs84_bounds else None,
            "boundsCrs": "EPSG:4326" if wgs84_bounds else None,
            "transform": transform_to_list(dataset.transform),
            "resolution": {
                "x": dataset.res[0],
                "y": dataset.res[1],
            },
            "resolutionUnit": "degree" if dataset.crs and dataset.crs.is_geographic else (
                dataset.crs.linear_units if dataset.crs and dataset.crs.is_projected else None
            ),
            "resolutionMeter": resolution_meter,
            "nodata": dataset.nodata,
        }


def response(success, data=None, error=None):
    return {
        "success": success,
        "data": data,
        "error": error,
    }


def main():
    parser = argparse.ArgumentParser(description="使用 rasterio 解析 GeoTIFF 元数据。")
    parser.add_argument("file", help="本地 GeoTIFF 文件路径。")
    args = parser.parse_args()

    try:
        print(json.dumps(response(True, data=parse_metadata(args.file)), ensure_ascii=False))
        return 0
    except Exception as exc:
        print(json.dumps(response(False, error=f"GeoTIFF 元数据解析失败：{exc}"), ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
