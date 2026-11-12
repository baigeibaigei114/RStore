import shutil
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np
import rasterio
from rasterio.transform import from_origin

from processors.ndwi_processor import NdwiProcessor
from scripts.summarize_result_analysis import summarize


class Storage:
    def __init__(self, root):
        self.root = root
        self.uploads = 0

    def object_exists(self, bucket, key):
        return (self.root / key).is_file()

    def download_file(self, bucket, key, path):
        shutil.copyfile(self.root / key, path)

    def upload_file(self, bucket, key, path):
        self.uploads += 1
        shutil.copyfile(path, self.root / key)


class NdwiResultAnalysisTest(unittest.TestCase):
    def test_source_masks_finite_nodata_and_zero_denominator_control_the_statistics(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source.tif"
            green = np.array([[6, 4, -9999, 10], [2, 0, 2, np.nan]], dtype=np.float32)
            nir = np.array([[2, 4, 4, 2], [-9999, 0, 6, 1]], dtype=np.float32)
            with rasterio.Env(GDAL_TIFF_INTERNAL_MASK=True), rasterio.open(
                source, "w", driver="GTiff", width=4, height=2, count=2, dtype="float32",
                crs="EPSG:4326", transform=from_origin(0, 2, 1, 1), nodata=-9999,
            ) as dataset:
                dataset.write(green, 1)
                dataset.write(nir, 2)
                dataset.write_mask(np.array([[255, 255, 255, 0], [255, 255, 255, 255]], dtype=np.uint8))
            message = {"taskId": 1, "inputBucket": "test", "inputObjectKey": source.name,
                       "outputBucket": "test", "outputObjectKey": "result.tif", "params": {"greenBand": 1, "nirBand": 2}}
            storage = Storage(root)
            processor = NdwiProcessor(storage, root / "work")

            metadata = processor.process(message)["resultMetadata"]
            self.assertEqual("NDWI", metadata["algorithm"])
            self.assertEqual("cropped_raster", metadata["scope"])
            self.assertEqual({
                "totalPixelCount": 8, "validPixelCount": 3, "invalidPixelCount": 5, "validPixelRatio": 3 / 8,
                "min": -0.5, "max": 0.5, "mean": 0.0,
            }, metadata["statistics"])
            with rasterio.open(root / "result.tif") as result:
                np.testing.assert_array_equal(result.read(1, masked=True).mask,
                                              [[False, False, True, True], [True, True, False, True]])
            spatial = summarize(root / "result.tif", metadata)
            self.assertEqual(0.0, spatial["p50"])
            self.assertEqual([2, 0, 0, 1], [section["valid"] for section in spatial["sections"]])
            self.assertEqual(metadata, processor.process(message)["resultMetadata"])
            self.assertEqual(1, storage.uploads)

    def test_legacy_result_is_retained_without_claiming_a_source_mask(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            with rasterio.open(root / "legacy.tif", "w", driver="GTiff", width=1, height=1,
                               count=1, dtype="float32", transform=from_origin(1, 1, 1, 1)) as result:
                result.write(np.array([[0]], dtype=np.float32), 1)
            storage = Storage(root)
            output = NdwiProcessor(storage, root / "work").process({
                "taskId": 2, "outputBucket": "test", "outputObjectKey": "legacy.tif",
                "params": {"greenBand": 1, "nirBand": 2},
            })
            self.assertNotIn("resultMetadata", output)
            self.assertEqual(0, storage.uploads)

    def test_nodata_is_compared_at_source_precision_and_crs_is_optional_for_statistics(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            with rasterio.Env(GDAL_TIFF_INTERNAL_MASK=True), rasterio.open(
                root / "source.tif", "w", driver="GTiff", width=2, height=1, count=2, dtype="float64",
                transform=from_origin(1, 1, 1, 1), nodata=-9999,
            ) as dataset:
                dataset.write(np.array([[-9999.0, -9999.0 + 1e-5]]), 1)
                dataset.write(np.array([[0.0, 0.0]]), 2)
                dataset.write_mask(np.array([[255, 255]], dtype=np.uint8))
            message = {"taskId": 3, "inputBucket": "test", "inputObjectKey": "source.tif",
                       "outputBucket": "test", "outputObjectKey": "result.tif", "params": {"greenBand": 1, "nirBand": 2}}
            result = NdwiProcessor(Storage(root), root / "work").process(message)
            self.assertEqual(1, result["resultMetadata"]["statistics"]["validPixelCount"])
            evidence = summarize(root / "result.tif", result["resultMetadata"])
            self.assertEqual(1.0, evidence["p50"])
            self.assertIsNone(evidence["srid"])
            self.assertTrue(all(section["polygon"] is None for section in evidence["sections"]))
