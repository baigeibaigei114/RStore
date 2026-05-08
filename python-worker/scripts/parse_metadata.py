import argparse
import json
import sys
from pathlib import Path

import rasterio


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


def parse_metadata(file_path):
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"GeoTIFF file does not exist: {path}")
    if not path.is_file():
        raise ValueError(f"Input path is not a file: {path}")

    with rasterio.open(path) as dataset:
        return {
            "width": dataset.width,
            "height": dataset.height,
            "bandCount": dataset.count,
            "crs": dataset.crs.to_string() if dataset.crs else None,
            "bounds": bounds_to_dict(dataset.bounds) if dataset.crs else None,
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
