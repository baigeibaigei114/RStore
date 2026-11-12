import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
import rasterio
from rasterio.transform import from_origin

from scripts.summarize_result_analysis import summarize
from utils.ndvi_baseline import read_result_metadata
from utils.region_mask import snapshot_digest


class ResultAnalysisTest(unittest.TestCase):
    def test_spatial_summary_preserves_mask_and_assigns_odd_grid_once(self):
        region = {
            "schemaVersion": 1, "version": 2, "crs": "EPSG:4326", "pixelRule": "center_covered",
            "geometry": {"type": "Polygon", "coordinates": [[[0, 0], [5, 0], [5, 3], [0, 3], [0, 0]]]},
        }
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "result.tif"
            values = np.arange(15, dtype=np.float32).reshape(3, 5) / 20
            values[0, 0] = np.nan
            with rasterio.open(path, "w", driver="GTiff", width=5, height=3, count=1, dtype="float32",
                               crs="EPSG:4326", transform=from_origin(0, 3, 1, 1), nodata=np.nan) as dataset:
                dataset.write(values, 1)
                dataset.update_tags(ndvi_baseline_version="1", redBand="3", nirBand="4",
                                    region_snapshot_sha256=snapshot_digest(region))
            metadata = Path(temp) / "metadata.json"
            metadata.write_text(json.dumps(read_result_metadata(path, 3, 4, region)), encoding="utf-8")
            output = Path(temp) / "spatial.json"
            helper = Path(__file__).resolve().parents[1] / "scripts/summarize_result_analysis.py"
            subprocess.run([sys.executable, "-B", str(helper), str(path), str(metadata), str(output)], check=True)
            evidence = json.loads(output.read_text(encoding="utf-8"))
            sections = evidence["sections"]
            self.assertEqual([2, 3, 4, 6], [section["total"] for section in sections])
            self.assertEqual([1, 3, 4, 6], [section["valid"] for section in sections])
            self.assertAlmostEqual(0.115, evidence["p10"], places=6)
            self.assertAlmostEqual(0.375, evidence["p50"], places=6)
            self.assertAlmostEqual(0.635, evidence["p90"], places=6)
            self.assertEqual([0.0, 1.5], sections[0]["polygon"]["coordinates"][0][0])
            self.assertEqual([2.5, 3.0], sections[0]["polygon"]["coordinates"][0][2])

    def test_nonrectangular_mask_distinguishes_empty_section_zero_and_finite_nodata(self):
        region = {
            "schemaVersion": 1, "version": 2, "crs": "EPSG:4326", "pixelRule": "center_covered",
            "geometry": {"type": "Polygon", "coordinates": [
                [[0, 0], [1, 0], [1, 2], [3, 2], [3, 3], [0, 3], [0, 0]]
            ]},
        }
        nodata = -9999.0
        values = np.array([[0, 0.5, nodata], [nodata, nodata, nodata], [0.25, nodata, nodata]], dtype=np.float32)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "result.tif"
            with rasterio.open(path, "w", driver="GTiff", width=3, height=3, count=1, dtype="float32",
                               crs="EPSG:4326", transform=from_origin(0, 3, 1, 1), nodata=nodata) as dataset:
                dataset.write(values, 1)
                dataset.update_tags(ndvi_baseline_version="1", redBand="3", nirBand="4",
                                    region_snapshot_sha256=snapshot_digest(region))
            metadata = read_result_metadata(path, 3, 4, region)
            evidence = summarize(path, metadata)
            self.assertEqual([1, 2, 2, 0], [section["total"] for section in evidence["sections"]])
            self.assertEqual([1, 1, 1, 0], [section["valid"] for section in evidence["sections"]])
            self.assertEqual([0.0, 0.5, 0.25, None], [section["mean"] for section in evidence["sections"]])
            self.assertIsNone(evidence["sections"][3]["polygon"])
            metadata["statistics"]["mean"] = 0.1
            with self.assertRaisesRegex(ValueError, "do not match saved evidence"):
                summarize(path, metadata)
