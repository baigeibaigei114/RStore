import copy
import hashlib
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

import numpy as np
import rasterio
from rasterio.transform import from_origin
from rasterio.warp import transform

from utils.ndvi_baseline import process_ndvi
from utils.region_mask import region_mask


class RegionNdviTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.input = self.root / "input.tif"
        self.output = self.root / "saved.tif"
        with rasterio.open(self.input, "w", driver="GTiff", width=4, height=4,
                           count=4, dtype="float32", nodata=-9999, crs="EPSG:32650",
                           transform=from_origin(400000, 3200000, 10, 10)) as ds:
            ds.write(np.full((4, 4, 4), 0.2, dtype="float32"))
            nir = np.full((4, 4), 0.6, dtype="float32")
            nir[0, 1] = -9999
            ds.write(nir, 4)
            for i, name in enumerate(("B02", "B03", "B04", "B08"), 1):
                ds.set_band_description(i, name)
            ds.update_tags(input_units="surface_reflectance")
        self.storage = Mock()
        self.storage.object_exists.side_effect = lambda b, k: self.output.exists()
        self.storage.download_file.side_effect = lambda b, k, p: shutil.copyfile(
            self.input if k == "input" else self.output, p)
        self.storage.upload_file.side_effect = lambda b, k, p: shutil.copyfile(p, self.output)

    def region(self, pixels):
        with rasterio.open(self.input) as ds:
            world = [ds.transform * point for point in pixels]
            lon, lat = transform(ds.crs, "EPSG:4326", *zip(*world))
        return {"schemaVersion": 1, "regionId": 1, "version": 1, "name": "test",
                "crs": "EPSG:4326", "pixelRule": "center_covered",
                "geometry": {"type": "Polygon", "coordinates": [list(map(list, zip(lon, lat)))]}}

    def run_region(self, region):
        with self.input.open("rb") as stream:
            sha = hashlib.file_digest(stream, "sha256").hexdigest()
        snapshot = {"status": "PASSED", "validatorVersion": "input-v1",
                    "algorithmVersion": "ndvi-v1", "denominatorTolerance": 1e-6,
                    "manifest": {"sha256": sha}}
        message = {"inputBucket": "b", "inputObjectKey": "input", "outputBucket": "b",
                   "outputObjectKey": "output",
                   "params": {"inputSnapshot": snapshot, "regionSnapshot": region}}
        return process_ndvi(self.storage, self.root / "work", message, 3, 4)

    def test_region_counts_nodata_and_output_grid(self):
        region = self.region([(0, 0), (2, 0), (2, 2), (0, 2), (0, 0)])
        first = self.run_region(region)
        stats = first["resultMetadata"]["statistics"]
        self.assertEqual((stats["totalPixelCount"], stats["validPixelCount"], stats["invalidPixelCount"]), (4, 3, 1))
        self.assertEqual(stats["validPixelRatio"], 0.75)
        self.assertAlmostEqual(stats["mean"], 0.5, places=6)
        self.assertEqual(first, self.run_region(region))
        with rasterio.open(self.input) as src, rasterio.open(self.output) as dst:
            self.assertEqual(src.transform, dst.transform)
            self.assertEqual(src.crs, dst.crs)
            self.assertEqual(dst.shape, (4, 4))
            self.assertTrue(np.isnan(dst.read(1)[2:, :]).all())
        changed = copy.deepcopy(region)
        changed["version"] = 2
        with self.assertRaisesRegex(ValueError, "region snapshot"):
            self.run_region(changed)

    def test_partial_and_disjoint_coverage_rejected(self):
        for pixels in [
            [(-1, 0), (2, 0), (2, 2), (-1, 2), (-1, 0)],
            [(5, 5), (6, 5), (6, 6), (5, 6), (5, 5)],
        ]:
            with self.subTest(pixels=pixels), self.assertRaisesRegex(ValueError, "fully covered"):
                self.run_region(self.region(pixels))
        self.storage.upload_file.assert_not_called()

    def test_tiny_polygon_without_centers_rejected(self):
        with self.assertRaisesRegex(ValueError, "no pixel centers"):
            self.run_region(self.region([(0.1, 0.1), (0.2, 0.1), (0.2, 0.2), (0.1, 0.1)]))

    def test_region_all_invalid_not_whole_image(self):
        with self.assertRaisesRegex(ValueError, "no valid NDVI"):
            self.run_region(self.region([(1, 0), (2, 0), (2, 1), (1, 1), (1, 0)]))

    def test_triangle_and_boundary_centers(self):
        snapshot = self.region([(0, 0), (3, 0), (0, 3), (0, 0)])
        with rasterio.open(self.input) as ds:
            mask = region_mask(ds, snapshot)
        # x+y <= 3；对角线上的三个中心也归内。
        np.testing.assert_array_equal(mask, [
            [1, 1, 1, 0], [1, 1, 0, 0], [1, 0, 0, 0], [0, 0, 0, 0]])

    def test_full_coverage_matches_original_grid_count(self):
        result = self.run_region(self.region([(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)]))
        self.assertEqual(result["resultMetadata"]["statistics"]["totalPixelCount"], 16)


if __name__ == "__main__":
    unittest.main()
