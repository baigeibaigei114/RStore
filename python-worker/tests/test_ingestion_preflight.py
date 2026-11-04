import unittest
from affine import Affine
from rasterio.warp import transform
from ingestion.preflight import preflight_window, StageFailure


class PreflightTest(unittest.TestCase):
    grid = {"crs": "EPSG:32652", "width": 10980, "height": 10980, "transform": [10, 0, 300000, 0, -10, 4200000]}

    def region(self, left, top, right, bottom):
        affine = Affine(*self.grid["transform"])
        source = [affine * p for p in [(left, top), (right, top), (right, bottom), (left, bottom), (left, top)]]
        xs, ys = transform(self.grid["crs"], "EPSG:4326", [p[0] for p in source], [p[1] for p in source])
        return {"schemaVersion": 1, "crs": "EPSG:4326", "pixelRule": "center_covered", "geometry": {"type": "Polygon", "coordinates": [list(map(list, zip(xs, ys)))]}}

    def test_full_2048_edge_is_not_expanded_by_projection_roundtrip(self):
        self.assertEqual(preflight_window(self.grid, self.region(4000, 4000, 6048, 6048)), [4000, 4000, 2048, 2048])

    def test_outside_and_2049_are_rejected_without_clipping(self):
        for bounds, reason in [((-1, 0, 30, 30), "REGION_OUTSIDE_PRODUCT"), ((0, 0, 2049, 10), "WINDOW_TOO_LARGE")]:
            with self.subTest(reason=reason), self.assertRaises(StageFailure) as error:
                preflight_window(self.grid, self.region(*bounds))
            self.assertEqual(error.exception.reason, reason)
