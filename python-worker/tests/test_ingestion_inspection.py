import io
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from ingestion.inspection import download_archive
from ingestion.preflight import StageFailure


class InspectionBoundaryTest(unittest.TestCase):
    def test_actual_download_never_exceeds_reserved_bytes(self):
        class Response(io.BytesIO):
            def release_conn(self): pass

        class Client:
            def stat_object(self, *args): return SimpleNamespace(size=3)
            def get_object(self, *args): return Response(b"four")

        with tempfile.TemporaryDirectory() as temp, self.assertRaises(StageFailure) as error:
            download_archive(Client(), {"bucket": "fixture", "key": "fixture", "bytes": 3, "sha256": "0" * 64}, Path(temp) / "archive.zip", 8)
        self.assertEqual(error.exception.reason, "RESOURCE_LIMIT")
