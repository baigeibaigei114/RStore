"""监测区域投影及严格像素中心掩膜，不依赖地图底图或显示级几何。"""

import hashlib
import json

import numpy as np
from rasterio.warp import transform

COVERAGE_TOLERANCE = 1e-7  # 像素单位，仅吸收 CRS 往返浮点误差
BOUNDARY_TOLERANCE = 1e-9


def snapshot_digest(snapshot: dict) -> str:
    return hashlib.sha256(
        json.dumps(snapshot, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def region_mask(dataset, snapshot: dict) -> np.ndarray:
    if (snapshot.get("schemaVersion") != 1 or snapshot.get("crs") != "EPSG:4326"
            or snapshot.get("pixelRule") != "center_covered"
            or dataset.crs is None):
        raise ValueError("Unsupported region snapshot or missing raster CRS")
    geometry = snapshot.get("geometry", {})
    rings = geometry.get("coordinates", [])
    if geometry.get("type") != "Polygon" or len(rings) != 1:
        raise ValueError("Region must be a single-ring Polygon")
    coordinates = np.asarray(rings[0], dtype="float64")
    if (coordinates.ndim != 2 or coordinates.shape[1] != 2
            or not 4 <= len(coordinates) <= 501
            or not np.isfinite(coordinates).all()
            or not np.array_equal(coordinates[0], coordinates[-1])):
        raise ValueError("Invalid region coordinates")
    xs, ys = transform("EPSG:4326", dataset.crs, coordinates[:, 0], coordinates[:, 1])
    inverse = ~dataset.transform
    points = np.asarray([inverse * (x, y) for x, y in zip(xs, ys)])
    if (not np.isfinite(points).all()
            or (points < -COVERAGE_TOLERANCE).any()
            or (points[:, 0] > dataset.width + COVERAGE_TOLERANCE).any()
            or (points[:, 1] > dataset.height + COVERAGE_TOLERANCE).any()):
        raise ValueError("Region is not fully covered by input raster")

    # 射线奇偶规则 + 点在线段上测试；显式定义中心落在边界时归内。
    # 分块限制临时数组内存；只扫描区域包围框，但最终输出仍为原网格。
    mask = np.zeros((dataset.height, dataset.width), dtype=bool)
    col_start = max(0, int(np.floor(points[:, 0].min())))
    col_end = min(dataset.width, int(np.ceil(points[:, 0].max())))
    row_start = max(0, int(np.floor(points[:, 1].min())))
    row_end = min(dataset.height, int(np.ceil(points[:, 1].max())))
    for start in range(row_start, row_end, 128):
        stop = min(start + 128, row_end)
        x, y = np.meshgrid(np.arange(col_start, col_end) + 0.5, np.arange(start, stop) + 0.5)
        inside = np.zeros(x.shape, dtype=bool)
        boundary = np.zeros(x.shape, dtype=bool)
        for (ax, ay), (bx, by) in zip(points[:-1], points[1:]):
            dx, dy = bx - ax, by - ay
            length = np.hypot(dx, dy)
            if length <= BOUNDARY_TOLERANCE:
                continue
            cross = (x - ax) * dy - (y - ay) * dx
            on_segment = (
                (np.abs(cross) <= BOUNDARY_TOLERANCE * length)
                & (x >= min(ax, bx) - BOUNDARY_TOLERANCE)
                & (x <= max(ax, bx) + BOUNDARY_TOLERANCE)
                & (y >= min(ay, by) - BOUNDARY_TOLERANCE)
                & (y <= max(ay, by) + BOUNDARY_TOLERANCE)
            )
            boundary |= on_segment
            if dy != 0:
                inside ^= ((ay > y) != (by > y)) & (x < ax + (y - ay) * dx / dy)
        mask[start:stop, col_start:col_end] = inside | boundary
    if not mask.any():
        raise ValueError("Region contains no pixel centers at input resolution")
    return mask
