import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from types import SimpleNamespace

import numpy as np
import rasterio

from scripts.prepare_ndvi_baseline import fixture, prepare_real
from utils.ndvi_baseline import calculate_ndvi, process_ndvi, summarize_ndvi


class NdviBaselineTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.input = self.root / "input.tif"
        fixture(self.input)

    def test_hand_calculated_values_and_nodata(self):
        with rasterio.open(self.input) as dataset:
            result = calculate_ndvi(dataset, 3, 4)
        np.testing.assert_allclose(result, [[0.5, 0.5], [0, np.nan]], atol=1e-6)
        metadata = summarize_ndvi(result, 3, 4)
        self.assertEqual(metadata["statistics"]["validPixelCount"], 3)
        self.assertEqual(metadata["statistics"]["invalidPixelCount"], 1)
        self.assertEqual(metadata["statistics"]["validPixelRatio"], 0.75)
        self.assertAlmostEqual(metadata["statistics"]["mean"], 1 / 3, places=6)
        json.dumps(metadata, allow_nan=False)

    def test_zero_denominator_and_single_band_nodata(self):
        with rasterio.open(self.input, "r+") as dataset:
            red = dataset.read(3)
            nir = dataset.read(4)
            red[0, 0] = -0.6
            red[0, 1] = -9999
            nir[1, 0] = np.inf
            dataset.write(red, 3)
            dataset.write(nir, 4)
        with rasterio.open(self.input) as dataset:
            with self.assertRaisesRegex(ValueError, "no valid pixels"):
                calculate_ndvi(dataset, 3, 4)

    def test_invalid_band_mapping(self):
        with rasterio.open(self.input) as dataset:
            for bands in ((0, 4), (3, 5), (3, 3)):
                with self.subTest(bands=bands), self.assertRaises(ValueError):
                    calculate_ndvi(dataset, *bands)

    def test_dataset_mask_is_respected(self):
        with rasterio.open(self.input, "r+") as dataset:
            dataset.write_mask(np.array([[255, 0], [255, 255]], dtype="uint8"))
        with rasterio.open(self.input) as dataset:
            result = calculate_ndvi(dataset, 3, 4)
        self.assertTrue(np.isnan(result[0, 1]))
        self.assertTrue(np.isnan(result[1, 1]))

    def test_near_zero_denominator_is_masked_without_clipping(self):
        with rasterio.open(self.input, "r+") as dataset:
            dataset.write(np.array([[-0.5999999, -0.2], [0.2, -9999]], dtype="float32"), 3)
        with rasterio.open(self.input) as dataset:
            result = calculate_ndvi(dataset, 3, 4)
        self.assertTrue(np.isnan(result[0, 0]))
        self.assertAlmostEqual(float(result[0, 1]), 2.0, places=6)

    def test_real_preparation_applies_offset_and_quality_mask(self):
        with rasterio.open(self.input) as source:
            profile = source.profile.copy()
        profile.update(count=1, dtype="uint16", nodata=0)
        paths = []
        for name in ("blue", "green", "red", "nir", "scl"):
            path = self.root / (name + ".tif")
            with rasterio.open(path, "w", **profile) as output:
                values = np.full((2, 2), 7000 if name == "nir" else 3000, dtype="uint16")
                if name == "scl":
                    values = np.array([[4, 9], [6, 0]], dtype="uint16")
                output.write(values, 1)
            paths.append(path)
        prepared = self.root / "prepared.tif"
        args = SimpleNamespace(
            blue=paths[0], green=paths[1], red=paths[2], nir=paths[3], scl=paths[4],
            source_id="synthetic-raw-product", capture_time="2024-01-01T00:00:00Z",
            scale=0.0001, offset=-0.1, window=[0, 0, 2, 2], output=prepared,
        )
        prepare_real(args)
        with rasterio.open(prepared) as dataset:
            result = calculate_ndvi(dataset, 3, 4)
            self.assertEqual(dataset.descriptions, ("B02", "B03", "B04", "B08"))
        np.testing.assert_allclose(result, [[0.5, np.nan], [0.5, np.nan]], atol=1e-6)
        with self.assertRaises(FileExistsError):
            prepare_real(args)

    def test_existing_output_recovers_identical_statistics(self):
        saved = self.root / "saved.tif"
        storage = Mock()
        storage.object_exists.side_effect = [False, True]
        storage.download_file.side_effect = lambda bucket, key, target: shutil.copyfile(
            self.input if key == "input" else saved, target)
        storage.upload_file.side_effect = lambda bucket, key, source: shutil.copyfile(source, saved)
        message = {"inputBucket": "b", "inputObjectKey": "input",
                   "outputBucket": "b", "outputObjectKey": "output"}
        first = process_ndvi(storage, self.root / "work", message, 3, 4)
        second = process_ndvi(storage, self.root / "work", message, 3, 4)
        self.assertEqual(first, second)
        storage.upload_file.assert_called_once()
        with rasterio.open(self.input) as source, rasterio.open(saved) as output:
            self.assertEqual(source.crs, output.crs)
            self.assertEqual(source.transform, output.transform)
            self.assertEqual(output.read(1, masked=True).count(), 3)
        self.assertEqual(list((self.root / "work").iterdir()), [])

    def test_legacy_output_is_not_silently_certified(self):
        storage = Mock()
        storage.object_exists.return_value = True
        storage.download_file.side_effect = lambda bucket, key, target: shutil.copyfile(self.input, target)
        with self.assertRaisesRegex(ValueError, "not compatible"):
            process_ndvi(storage, self.root / "work",
                         {"outputBucket": "b", "outputObjectKey": "out"}, 3, 4)
        storage.upload_file.assert_not_called()

    def test_all_invalid_input_does_not_upload_output(self):
        invalid = self.root / "invalid.tif"
        fixture(invalid, "all-nodata")
        storage = Mock()
        storage.object_exists.return_value = False
        storage.download_file.side_effect = lambda bucket, key, target: shutil.copyfile(invalid, target)
        with self.assertRaisesRegex(ValueError, "no valid pixels"):
            process_ndvi(storage, self.root / "work", {
                "inputBucket": "b", "inputObjectKey": "in",
                "outputBucket": "b", "outputObjectKey": "out"}, 3, 4)
        storage.upload_file.assert_not_called()
        self.assertEqual(list((self.root / "work").iterdir()), [])


if __name__ == "__main__":
    unittest.main()
