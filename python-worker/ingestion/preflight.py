"""Deterministic SAFE source-grid window calculation, shared with future standardization."""
import math
import numpy as np
from affine import Affine
from rasterio.warp import transform
from utils.region_mask import COVERAGE_TOLERANCE


class StageFailure(ValueError):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


def preflight_window(grid, snapshot):
    if snapshot.get("schemaVersion") != 1 or snapshot.get("crs") != "EPSG:4326" or snapshot.get("pixelRule") != "center_covered":
        raise StageFailure("INVALID_REQUEST")
    geometry = snapshot.get("geometry", {})
    rings = geometry.get("coordinates", [])
    if geometry.get("type") != "Polygon" or len(rings) != 1:
        raise StageFailure("INVALID_REQUEST")
    points = np.asarray(rings[0], dtype="float64")
    if (points.ndim != 2 or points.shape[1] != 2 or not 4 <= len(points) <= 501
            or not np.isfinite(points).all() or not np.array_equal(points[0], points[-1])
            or (np.abs(points[:, 0]) > 180).any() or (np.abs(points[:, 1]) > 85).any()
            or np.ptp(points[:, 0]) > 180):
        raise StageFailure("INVALID_REQUEST")
    if (type(grid.get("width")) is not int or type(grid.get("height")) is not int
            or not 1 <= grid["width"] <= 20000 or not 1 <= grid["height"] <= 20000
            or len(grid.get("transform", [])) != 6 or not np.isfinite(grid["transform"]).all()):
        raise StageFailure("INVALID_GRID")
    affine = Affine(*grid["transform"])
    xs, ys = transform("EPSG:4326", grid["crs"], points[:, 0], points[:, 1])
    inverse = ~affine
    pixels = np.asarray([inverse * (x, y) for x, y in zip(xs, ys)])
    if (not np.isfinite(pixels).all() or (pixels < -COVERAGE_TOLERANCE).any()
            or (pixels[:, 0] > grid["width"] + COVERAGE_TOLERANCE).any()
            or (pixels[:, 1] > grid["height"] + COVERAGE_TOLERANCE).any()):
        raise StageFailure("REGION_OUTSIDE_PRODUCT")
    nearest = np.rint(pixels)
    pixels = np.where(np.abs(pixels - nearest) <= COVERAGE_TOLERANCE, nearest, pixels)
    col, row = math.floor(pixels[:, 0].min()), math.floor(pixels[:, 1].min())
    width, height = math.ceil(pixels[:, 0].max()) - col, math.ceil(pixels[:, 1].max()) - row
    if not 1 <= width <= 2048 or not 1 <= height <= 2048:
        raise StageFailure("WINDOW_TOO_LARGE")
    return [int(col), int(row), int(width), int(height)]
