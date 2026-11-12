"""Read bounded evidence from a saved, mask-aware result GeoTIFF."""

import json
import math
import sys
from pathlib import Path

import numpy as np
import rasterio
from rasterio.warp import transform

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from utils.ndvi_baseline import read_result_pixels
from utils.result_analysis import read_index_result


MAX_EDGE = 2048


def polygon(ring):
    return {"type": "Polygon", "coordinates": [ring]}


def summarize(raster, metadata):
    region = metadata.get("regionSnapshot")
    algorithm = metadata["algorithm"]
    bands = metadata["comparison"] if algorithm == "CHANGE_DETECTION" else metadata["bandMapping"]
    statistics = metadata["statistics"]
    with rasterio.open(raster, driver="GTiff") as dataset:
        if max(dataset.shape) > MAX_EDGE:
            raise ValueError("Raster exceeds the result evidence budget")
        if algorithm == "CHANGE_DETECTION":
            grid = metadata["grid"]
            if dataset.shape != (grid["height"], grid["width"]):
                raise ValueError("Change raster dimensions do not match the saved grid")
        if region is None:
            values = read_index_result(dataset, algorithm, bands)
            area = np.ones(dataset.shape, dtype=bool)
        else:
            values, area = read_result_pixels(dataset, bands["redBand"], bands["nirBand"], region)
        valid = area & np.isfinite(values)
        pixels = values[valid]
        if int(area.sum()) != statistics["totalPixelCount"] or pixels.size != statistics["validPixelCount"]:
            raise ValueError("Raster counts do not match saved evidence")
        for field, value in (("min", pixels.min()), ("max", pixels.max()), ("mean", pixels.mean(dtype="float64"))):
            if not math.isclose(float(value), statistics[field], rel_tol=1e-7, abs_tol=1e-8):
                raise ValueError("Raster values do not match saved evidence")

        r0, r1 = np.flatnonzero(area.any(axis=1))[[0, -1]]
        c0, c1 = np.flatnonzero(area.any(axis=0))[[0, -1]]
        r1 += 1
        c1 += 1
        row_edges = (r0, (r0 + r1) / 2, r1)
        col_edges = (c0, (c0 + c1) / 2, c1)
        p10, p50, p90 = np.percentile(pixels, [10, 50, 90], method="linear")
        srid = dataset.crs.to_epsg() if dataset.crs is not None else None
        projected = None
        if srid and region is not None:
            ring = region["geometry"]["coordinates"][0]
            xs, ys = transform("EPSG:4326", dataset.crs, [p[0] for p in ring], [p[1] for p in ring])
            projected = polygon(list(zip(xs, ys)))
        elif srid:
            corners = [(0, dataset.height), (dataset.width, dataset.height),
                       (dataset.width, 0), (0, 0), (0, dataset.height)]
            projected = polygon([dataset.transform * corner for corner in corners])
        sections = []
        for section, row, col in (("NW", 0, 0), ("NE", 0, 1), ("SW", 1, 0), ("SE", 1, 1)):
            top, bottom = row_edges[row:row + 2]
            left, right = col_edges[col:col + 2]
            # Centers on the midpoint belong to the lower/right section.
            block = np.s_[int(top):int(bottom), int(left):int(right)]
            samples = values[block][valid[block]]
            total = int(area[block].sum())
            shape = None
            if total and srid:
                corners = [(left, bottom), (right, bottom), (right, top), (left, top), (left, bottom)]
                shape = polygon([dataset.transform * corner for corner in corners])
            sections.append({"id": section, "total": total, "valid": int(samples.size),
                             "mean": float(samples.mean(dtype="float64")) if samples.size else None,
                             "polygon": shape})
    return {"p10": float(p10), "p50": float(p50), "p90": float(p90), "sections": sections,
            "srid": srid, "projectedRegion": projected}


if __name__ == "__main__":
    raster, source, output = map(Path, sys.argv[1:])
    metadata = json.loads(source.read_text(encoding="utf-8"))
    output.write_text(json.dumps(summarize(raster, metadata), allow_nan=False), encoding="utf-8")
