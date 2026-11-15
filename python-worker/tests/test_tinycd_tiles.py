import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np
import rasterio
from rasterio.transform import from_origin

from processors.tinycd import TinyCD, compute_raster


class TinyCdTilesTest(unittest.TestCase):
    def test_tile_edges_threshold_and_invalid_neighborhood(self):
        try:
            import torch
        except ImportError:
            self.skipTest("requires the TinyCD CPU runtime")

        engine = TinyCD.__new__(TinyCD)
        engine._torch = torch
        engine._input = lambda image: torch.from_numpy(image[:, :, 0].copy()).float()[None, None] / 2
        # A local dependency makes it possible to verify the halo at a tile boundary.
        engine._model = lambda first, after: torch.roll(after, shifts=1, dims=3) + after
        before = np.zeros((197, 385, 3), dtype="uint8")
        after = before.copy()
        after[4, [191, 383], 0] = 2
        after[9, 192, 0] = 1  # exactly 0.5
        after[12, 191, 0] = 255  # hidden RGB immediately beside a valid tile
        valid = np.ones(before.shape[:2], dtype=bool)
        valid[12, 191] = False
        mask = engine.predict(before, after, valid)
        self.assertEqual(np.where(mask[4] == 1)[0].tolist(), [191, 192, 383, 384])
        self.assertEqual(mask[9, 192:194].tolist(), [0, 0])
        self.assertEqual(mask[12, 191:193].tolist(), [255, 0])
        with self.assertRaisesRegex(ValueError, "共同有效像元"):
            engine.predict(before, after, np.zeros(valid.shape, dtype=bool))

    def test_misaligned_rasters_are_rejected_before_inference(self):
        with TemporaryDirectory() as directory:
            folder = Path(directory)
            profile = dict(driver="GTiff", width=32, height=32, count=3, dtype="uint8", crs="EPSG:32611",
                           photometric="RGB", transform=from_origin(400000, 3700000, 0.5, 0.5))
            before, after = folder / "before.tif", folder / "after.tif"
            data = np.ones((3, 32, 32), dtype="uint8")
            with rasterio.open(before, "w", **profile) as output:
                output.write(data)
            profile["transform"] = from_origin(400000.5, 3700000, 0.5, 0.5)
            with rasterio.open(after, "w", **profile) as output:
                output.write(data)
            with self.assertRaisesRegex(ValueError, "仿射网格"):
                compute_raster(None, before, after, folder / "result.tif")
