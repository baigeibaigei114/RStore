"""Pinned TinyCD CPU inference and aligned RGB raster handling."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np
import rasterio
from rasterio.enums import ColorInterp

WEIGHTS_SHA256 = "743645b87199c96bd0c3271ae5df6f0918b3f1d84618dfb9396843ca08ee4a3e"
TILE_SIZE = 256
CORE_SIZE = 192
HALO = (TILE_SIZE - CORE_SIZE) // 2
THRESHOLD = 0.5
NODATA = 255
MAX_DIMENSION = 2400


class TinyCD:
    def __init__(self, weights: Path, threads: int = 2):
        import torch
        from models.change_classifier import ChangeClassifier

        if hashlib.sha256(weights.read_bytes()).hexdigest() != WEIGHTS_SHA256:
            raise ValueError("TinyCD 权重摘要与固定 LEVIR-CD 模型不符")
        torch.set_num_threads(threads)
        self._torch = torch
        self._model = ChangeClassifier(pretrained=False)
        self._model.load_state_dict(torch.load(weights, map_location="cpu", weights_only=True), strict=True)
        self._model.eval()
        self._mean = torch.tensor([0.485, 0.456, 0.406]).reshape(1, 3, 1, 1)
        self._std = torch.tensor([0.229, 0.224, 0.225]).reshape(1, 3, 1, 1)

    def _input(self, rgb):
        array = np.ascontiguousarray(rgb.transpose(2, 0, 1))
        return (self._torch.from_numpy(array).float()[None] / 255 - self._mean) / self._std

    def predict(self, before, after, valid):
        height, width = valid.shape
        if not valid.any():
            raise ValueError("两期影像没有共同有效像元，请换景")
        extra_y, extra_x = (-height) % CORE_SIZE, (-width) % CORE_SIZE
        padding = ((HALO, HALO + extra_y), (HALO, HALO + extra_x), (0, 0))
        # Mask both dates identically so arbitrary RGB values under NoData cannot affect neighbors.
        first, last = (np.pad(np.where(valid[..., None], rgb, np.uint8(0)), padding, mode="reflect")
                       for rgb in (before, after))
        predicted = np.empty((height + extra_y, width + extra_x), dtype="uint8")
        with self._torch.inference_mode():
            for y in range(0, height, CORE_SIZE):
                for x in range(0, width, CORE_SIZE):
                    tile = np.s_[y:y + TILE_SIZE, x:x + TILE_SIZE]
                    probabilities = self._model(self._input(first[tile]), self._input(last[tile]))[0, 0]
                    core = probabilities[HALO:-HALO, HALO:-HALO].numpy()
                    predicted[y:y + CORE_SIZE, x:x + CORE_SIZE] = core > THRESHOLD
        return np.where(valid, predicted[:height, :width], np.uint8(NODATA))


def grid(dataset):
    return {"crs": dataset.crs.to_string(), "width": dataset.width, "height": dataset.height,
            "transform": list(dataset.transform)[:6]}


def check_rgb(dataset):
    if (dataset.driver != "GTiff" or dataset.dtypes != ("uint8",) * 3
            or dataset.colorinterp != (ColorInterp.red, ColorInterp.green, ColorInterp.blue)):
        raise ValueError("模型检测需要三波段 uint8 RGB GeoTIFF")
    if (not dataset.crs or not dataset.crs.is_projected or dataset.crs.linear_units_factor[1] != 1
            or not np.isfinite(tuple(dataset.transform)).all() or dataset.transform.is_degenerate):
        raise ValueError("模型检测需要米制投影和有效的仿射网格")
    if max(dataset.shape) > MAX_DIMENSION:
        raise ValueError("模型检测影像单边最多 2400 像素")


def statistics(mask, transform):
    if not np.isin(mask, (0, 1, NODATA)).all():
        raise ValueError("TinyCD 结果包含未知的掩膜值")
    total = mask.size
    valid = int(np.count_nonzero(mask != NODATA))
    if not valid:
        raise ValueError("两期影像没有共同有效像元，请换景")
    changed = int(np.count_nonzero(mask == 1))
    area = abs(transform.determinant)
    return {"totalPixelCount": total, "validPixelCount": valid, "changedPixelCount": changed,
            "pixelAreaM2": area, "changedAreaM2": changed * area,
            "changeRatio": changed / valid, "validPixelRatio": valid / total}


def compute_raster(model, before_path, after_path, result_path, expected_grid=None, binding=None):
    with rasterio.open(before_path) as before, rasterio.open(after_path) as after:
        check_rgb(before)
        check_rgb(after)
        actual_grid = grid(after)
        if grid(before) != actual_grid or (expected_grid is not None and expected_grid != actual_grid):
            raise ValueError("前后两期影像的投影、范围和仿射网格必须一致")
        valid = (before.dataset_mask() != 0) & (after.dataset_mask() != 0)
        mask = model.predict(before.read().transpose(1, 2, 0), after.read().transpose(1, 2, 0), valid)
        profile = {"driver": "GTiff", "count": 1, "dtype": "uint8", "nodata": NODATA, "compress": "lzw",
                   "tiled": True, "width": after.width, "height": after.height,
                   "transform": after.transform, "crs": after.crs}
        report = statistics(mask, after.transform)
        with rasterio.open(result_path, "w", **profile) as result:
            result.write(mask, 1)
            result.write_colormap(1, {0: (227, 232, 226, 255), 1: (208, 56, 50, 255), NODATA: (0, 0, 0, 0)})
            result.update_tags(model="TinyCD LEVIR-CD", weights_sha256=WEIGHTS_SHA256, threshold=str(THRESHOLD),
                               mask_values="0=no change;1=predicted change;255=not comparable",
                               **({"task_binding": binding} if binding is not None else {}))
    with rasterio.open(result_path) as result:
        if grid(result) != actual_grid or not np.array_equal(result.read(1), mask):
            raise ValueError("TinyCD GeoTIFF 回读校验失败")
    return report


class BuildingChangeProcessor:
    def __init__(self, storage, temp_dir, model):
        self._storage = storage
        self._temp_dir = temp_dir
        self._model = model

    def process(self, message):
        output_bucket, output_key = message["outputBucket"], message["outputObjectKey"]
        params = message["params"]
        snapshot = params["buildingChangeSnapshot"]
        binding = hashlib.sha256(json.dumps(snapshot, sort_keys=True, ensure_ascii=True).encode()).hexdigest()
        expected = snapshot["grid"]
        with TemporaryDirectory(prefix="building-change-", dir=self._temp_dir) as directory:
            work = Path(directory)
            result_path = work / "change.tif"
            if self._storage.object_exists(output_bucket, output_key):
                self._storage.download_file(output_bucket, output_key, result_path)
                with rasterio.open(result_path) as result:
                    if (grid(result) != expected or result.nodata != NODATA or result.dtypes != ("uint8",)
                            or result.tags().get("task_binding") != binding):
                        raise ValueError("检测输出与本次任务快照不一致")
                    report = statistics(result.read(1), result.transform)
            else:
                before_path, after_path = work / "before.tif", work / "after.tif"
                self._storage.download_file(params["beforeBucket"], params["beforeObjectKey"], before_path)
                self._storage.download_file(params["afterBucket"], params["afterObjectKey"], after_path)
                report = compute_raster(self._model, before_path, after_path, result_path, expected, binding)
                self._storage.upload_file(output_bucket, output_key, result_path)
        return {"outputBucket": output_bucket, "outputObjectKey": output_key,
                "resultMetadata": {"schemaVersion": 1, "algorithm": "BUILDING_CHANGE_DETECTION",
                                   "scope": "aligned_rgb_raster", "snapshot": snapshot, "statistics": report}}
