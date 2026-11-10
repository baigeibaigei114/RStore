import hashlib
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
import rasterio
from rasterio.transform import from_origin

from clients.ingestion_callback_client import canonical, canonical_sha
from ingestion.preflight import StageFailure
from ingestion.standardization import reflectance_counts, standardize_archive
from ingestion.work_handler import validate_context
from utils.region_mask import region_mask


class StandardizationTest(unittest.TestCase):
    def snapshot(self, left=0, bottom=0, right=2, top=2):
        return {"schemaVersion": 1, "crs": "EPSG:4326", "pixelRule": "center_covered",
            "geometry": {"type": "Polygon", "coordinates": [[[left,bottom],[right,bottom],[right,top],[left,top],[left,bottom]]]}}

    def test_counts_preserve_matrix_and_only_standardization_allows_empty_region(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.tif"
            data = np.ones((4,2,2), dtype="float32") * .2
            data[:,0,0] = -9999
            with rasterio.open(path, "w", driver="GTiff", width=2, height=2, count=4, dtype="float32",
                    crs="EPSG:4326", transform=from_origin(0,2,1,1), nodata=-9999) as output:
                output.write(data)
            with rasterio.open(path) as dataset:
                self.assertEqual(reflectance_counts(dataset, self.snapshot()), {"regionPixelCount":4, "regionValidReflectanceCount":3})
                thin = self.snapshot(.01,.01,.02,.02)
                self.assertEqual(reflectance_counts(dataset, thin), {"regionPixelCount":0, "regionValidReflectanceCount":0})
                with self.assertRaisesRegex(ValueError, "no pixel centers"):
                    region_mask(dataset, thin)
                self.assertEqual(dataset.read(1)[1,1], np.float32(.2))

    def fixture(self):
        product = "11111111-1111-4111-8111-111111111111"
        job = "22222222-2222-4222-8222-222222222222"
        attempt = "33333333-3333-4333-8333-333333333333"
        message = {"schemaVersion":1, "kind":"STANDARDIZE", "workId":product, "targetId":job, "generation":1}
        grid = {"width":2,"height":2,"crs":"EPSG:4326","transform":[1,0,0,0,-1,2]}
        doc = {"schemaVersion":1,"profileVersion":"safe-l2a-profile-v1","archiveSha256":"a"*64,
            "sourceGrid":grid,"calibration":{},"rawCalibration":{},"regionSnapshot":self.snapshot(),"window":[0,0,2,2],
            "qualityRules":{"dnZeroInvalid":True,"sclResampling":"nearest","sclValidClasses":[4,5,6,7],"validatorVersion":"input-v1","preprocessingVersion":"s2-reflectance-v1"}}
        context = {"profileVersion":doc["profileVersion"],"work":message,"canonicalDocument":canonical(doc),"canonicalHash":canonical_sha(doc),
            "descriptor":{"grid":grid,"calibration":{},"rawCalibration":{}},"regionSnapshot":self.snapshot(),"regionSnapshotHash":canonical_sha(self.snapshot()),
            "window":doc["window"],"input":{"productId":product,"bucket":"private","key":f"ingestion/raw/{product}/{attempt}/product.zip","sha256":"a"*64,"bytes":1},
            "outputs":{role:{"bucket":"private","key":f"ingestion/artifacts/{job}/{attempt}/reflectance.{suffix}","maxBytes":limit}
                for role,suffix,limit in (("TIFF","tif",256*1024**2),("MANIFEST","json",65536))}}
        claim = {"action":"EXECUTE","attemptId":attempt,"slotFence":1,"leaseUntil":"x","hardDeadline":"x","context":context}
        return message, claim

    def test_standardize_context_cannot_redirect_output_or_change_window(self):
        message, claim = self.fixture()
        self.assertIs(validate_context(message, claim, "private"), claim["context"])
        claim["context"]["outputs"]["TIFF"]["key"] = "arbitrary/path.tif"
        with self.assertRaises(StageFailure):
            validate_context(message, claim, "private")
        message, claim = self.fixture()
        claim["context"]["window"] = [0,0,1,1]
        with self.assertRaises(StageFailure):
            validate_context(message, claim, "private")

    def test_actual_preflight_window_must_equal_confirmation_before_materializing(self):
        product = SimpleNamespace(source_id="synthetic-unit",capture_time="2024-01-01T00:00:00Z",granule="unit",baseline="05.13",
            metadata_sha256="a"*64,crs="EPSG:4326",width=2,height=2,transform=[1,0,0,0,-1,2],calibration={},quantification=10000,raw_offsets={})
        descriptor = {"sourceId":product.source_id,"captureTime":product.capture_time,"granule":product.granule,"processingBaseline":product.baseline,
            "parserVersion":"safe-l2a-probe-v1","profileVersion":"safe-l2a-profile-v1","productMetadataSha256":product.metadata_sha256,
            "grid":{"crs":product.crs,"width":2,"height":2,"transform":product.transform},"calibration":{},"rawCalibration":{"quantificationValue":10000,"boaAddOffsets":{}}}
        with patch("ingestion.standardization.SafeProduct") as safe, patch("ingestion.standardization.materialize_bands") as materialize:
            safe.return_value.__enter__.return_value = product
            with self.assertRaises(StageFailure) as error:
                standardize_archive("unused.zip", "unused", {"descriptor":descriptor,"regionSnapshot":self.snapshot(),"window":[0,0,1,1]})
            self.assertEqual(error.exception.reason, "INPUT_HASH_MISMATCH")
            materialize.assert_not_called()
