from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np
import rasterio
from rasterio.transform import from_origin

from processors.change_processor import ChangeDetectionProcessor
from scripts.summarize_result_analysis import summarize
from test_ndwi_result_analysis import Storage


def source(path, pixels, mask=None, dtype="float32"):
    with rasterio.Env(GDAL_TIFF_INTERNAL_MASK=True), rasterio.open(
        path, "w", driver="GTiff", width=4, height=2, count=1, dtype=dtype,
        crs="EPSG:4326", transform=from_origin(0, 2, 1, 1), nodata=-9999,
    ) as dataset:
        dataset.write(np.array(pixels, dtype=dtype), 1)
        if mask is not None:
            dataset.write_mask(np.array(mask, dtype=np.uint8))


def message(key="change.tif", threshold=0.5):
    return {"taskId": 1, "inputBucket": "test", "outputBucket": "test", "outputObjectKey": key,
            "params": {"beforeImageId": 7, "afterImageId": 8, "beforeObjectKey": "before.tif",
                       "afterObjectKey": "after.tif", "band": 1, "threshold": threshold}}


class ChangeResultAnalysisTest(unittest.TestCase):
    def test_common_valid_mask_and_unchanged_zero_are_distinct(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            source(root / "before.tif", [[1, 2, -9999, 3], [2, 7, 4, 5]])
            source(root / "after.tif", [[1, 3, 5, 3], [2, -9999, 4, 6]],
                   [[255, 255, 255, 0], [255, 255, 255, 255]])
            storage = Storage(root)
            processor = ChangeDetectionProcessor(storage, root / "work")

            result = processor.process(message())
            metadata = result["resultMetadata"]
            self.assertEqual({"width": 4, "height": 2, "alignment": "same_grid"}, metadata["grid"])
            self.assertEqual(8, metadata["statistics"]["totalPixelCount"])
            self.assertEqual(5, metadata["statistics"]["validPixelCount"])
            self.assertEqual(2, metadata["statistics"]["changedPixelCount"])
            self.assertEqual(3, metadata["statistics"]["unchangedPixelCount"])
            self.assertEqual(0.4, metadata["statistics"]["changeRatio"])
            with rasterio.open(root / "change.tif") as data:
                self.assertEqual(255, data.nodata)
                np.testing.assert_array_equal(data.read(1), [[0, 1, 255, 255], [0, 255, 0, 1]])
            sections = summarize(root / "change.tif", metadata)["sections"]
            self.assertEqual([2, 0, 1, 2], [section["valid"] for section in sections])
            self.assertEqual([0.5, None, 0.0, 0.5], [section["mean"] for section in sections])
            self.assertEqual(metadata, processor.process(message())["resultMetadata"])
            self.assertEqual(1, storage.uploads)
            metadata["grid"].update(width=2, height=4)
            with self.assertRaisesRegex(ValueError, "dimensions do not match"):
                summarize(root / "change.tif", metadata)

    def test_threshold_equality_remains_valid_unchanged(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            source(root / "before.tif", [[1] * 4, [1] * 4])
            source(root / "after.tif", [[2] * 4, [2] * 4])
            result = ChangeDetectionProcessor(Storage(root), root / "work").process(message(threshold=1))
            stats = result["resultMetadata"]["statistics"]
            self.assertEqual(8, stats["validPixelCount"])
            self.assertEqual(8, stats["unchangedPixelCount"])
            self.assertEqual(0, stats["changeRatio"])
            with rasterio.open(root / "change.tif") as data:
                self.assertFalse(np.ma.getmaskarray(data.read(1, masked=True)).any())

    def test_old_zero_nodata_result_remains_readable_without_a_denominator(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            with rasterio.open(root / "legacy.tif", "w", driver="GTiff", width=2, height=2,
                               count=1, dtype="uint8", nodata=0, transform=from_origin(1, 1, 1, 1)) as result:
                result.write(np.array([[0, 1], [0, 0]], dtype=np.uint8), 1)
            storage = Storage(root)
            result = ChangeDetectionProcessor(storage, root / "work").process(message(key="legacy.tif"))
            self.assertNotIn("resultMetadata", result)
            self.assertEqual(0, storage.uploads)

    def test_finite_source_values_still_count_when_the_difference_overflows(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            source(root / "before.tif", [[-1e308, 0, 0, 0], [0] * 4], dtype="float64")
            source(root / "after.tif", [[1e308, 0, 0, 0], [0] * 4], dtype="float64")
            result = ChangeDetectionProcessor(Storage(root), root / "work").process(message())
            stats = result["resultMetadata"]["statistics"]
            self.assertEqual(8, stats["validPixelCount"])
            self.assertEqual(1, stats["changedPixelCount"])
            self.assertEqual(1 / 8, stats["changeRatio"])
