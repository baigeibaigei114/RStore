"""Small developer checks; synthetic fixtures are not official-product evidence."""
import json
import io
import stat
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from xml.sax.saxutils import escape

import numpy as np
import rasterio
from rasterio.transform import Affine, from_origin

from ingestion.safe_product import BANDS, CALIBRATION_NAMES, PRODUCT_NS, TILE_NS, SafeProduct, member_path, parse_xml
from ingestion.safe_processing import checked_window, materialize_bands, prepare_window
from scripts import probe_safe


def synthetic_safe(root, scl_transform=None, scl_dtype="uint8", vrt_canary=None):
    """Complete small parser/CLI fixture, explicitly synthetic; no official data needed."""
    identity = "S2A_MSIL2A_20240101T000000_N0513_R001_T52SCG_20240101T010000.SAFE"
    directory = root / identity
    granule = "L2A_SYNTHETIC_TEST_ONLY"
    images = {b: f"GRANULE/{granule}/IMG_DATA/R10m/synthetic_{b}_10m.jp2" for b in BANDS}
    images["SCL"] = f"GRANULE/{granule}/IMG_DATA/R20m/synthetic_SCL_20m.jp2"
    transform = from_origin(300000, 4200000, 10, 10)
    for band, relative in images.items():
        path = directory / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        quality = band == "SCL"
        if quality and vrt_canary is not None:
            path.write_text(f'''<VRTDataset rasterXSize="4" rasterYSize="4">
<SRS>EPSG:32652</SRS><GeoTransform>300000,20,0,4200000,0,-20</GeoTransform>
<VRTRasterBand dataType="Byte" band="1"><SimpleSource>
<SourceFilename relativeToVRT="0">{escape(str(vrt_canary))}</SourceFilename>
<SourceBand>1</SourceBand><SrcRect xOff="0" yOff="0" xSize="4" ySize="4"/>
<DstRect xOff="0" yOff="0" xSize="4" ySize="4"/>
</SimpleSource></VRTRasterBand></VRTDataset>''', encoding="utf-8")
            continue
        edge, dtype = (4, scl_dtype) if quality else (8, "uint16")
        grid = (scl_transform or from_origin(300000, 4200000, 20, 20)) if quality else transform
        with rasterio.open(path, "w", driver="JP2OpenJPEG", width=edge, height=edge, count=1,
                           dtype=dtype, crs="EPSG:32652", transform=grid,
                           REVERSIBLE="YES", QUALITY=100, GMLJP2="NO") as output:
            output.write(np.full((edge, edge), 4 if quality else 2000, dtype=dtype), 1)
    references = "".join(f"<IMAGE_FILE>{p[:-4]}</IMAGE_FILE>" for p in images.values())
    offsets = "".join(f'<BOA_ADD_OFFSET band_id="{i}">-1000</BOA_ADD_OFFSET>' for i in (1, 2, 3, 7))
    spectral = "".join(f'<Spectral_Information bandId="{i}" physicalBand="B{b}"/>'
                       for i, b in ((1, 2), (2, 3), (3, 4), (7, 8)))
    (directory / "MTD_MSIL2A.xml").write_text(f'''<p:Level-2A_User_Product xmlns:p="{PRODUCT_NS}">
<!-- SYNTHETIC TEST FIXTURE, NOT A SATELLITE OBSERVATION -->
<PRODUCT_URI>{identity}</PRODUCT_URI><PROCESSING_LEVEL>Level-2A</PROCESSING_LEVEL>
<PRODUCT_TYPE>S2MSI2A</PRODUCT_TYPE><PROCESSING_BASELINE>05.13</PROCESSING_BASELINE>
<PRODUCT_START_TIME>2024-01-01T00:00:00Z</PRODUCT_START_TIME>
<Granule granuleIdentifier="synthetic-test-only">{references}</Granule>
<BOA_QUANTIFICATION_VALUE>10000</BOA_QUANTIFICATION_VALUE>{offsets}{spectral}
</p:Level-2A_User_Product>''', encoding="utf-8")
    (directory / "GRANULE" / granule / "MTD_TL.xml").write_text(f'''<t:Level-2A_Tile_ID xmlns:t="{TILE_NS}">
<TILE_ID>synthetic-test-only</TILE_ID><HORIZONTAL_CS_CODE>EPSG:32652</HORIZONTAL_CS_CODE>
<Size resolution="10"><NROWS>8</NROWS><NCOLS>8</NCOLS></Size>
<Geoposition resolution="10"><ULX>300000</ULX><ULY>4200000</ULY><XDIM>10</XDIM><YDIM>-10</YDIM></Geoposition>
</t:Level-2A_Tile_ID>''', encoding="utf-8")
    for name in ("manifest.safe", "INSPIRE.xml"):
        (directory / name).write_text("synthetic-test-only", encoding="utf-8")
    archive = root / "synthetic.zip"
    with zipfile.ZipFile(archive, "w") as packed:
        for path in directory.rglob("*"):
            if path.is_file():
                packed.write(path, path.relative_to(root).as_posix())
    return archive


class SafeIngestionTest(unittest.TestCase):
    def test_cli_rejects_scl_vrt_at_driver_open_before_warp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            canary = root / "outside-product-quality.tif"
            with rasterio.open(canary, "w", driver="GTiff", width=4, height=4, count=1,
                               dtype="uint8", crs="EPSG:32652",
                               transform=from_origin(300000, 4200000, 20, 20)) as output:
                output.write(np.full((4, 4), 4, dtype="uint8"), 1)
            archive = synthetic_safe(root, vrt_canary=canary)
            output_dir = root / "probe"
            argv = ["probe_safe.py", "--archive", str(archive), "--output-dir", str(output_dir),
                    "--window", "0", "0", "8", "8"]
            # Spy on the real open call: reject through GDAL's driver filter,
            # rather than recognizing VRT after it has already been opened.
            with patch.object(sys, "argv", argv), redirect_stdout(io.StringIO()), \
                    patch("ingestion.safe_processing.rasterio.open", wraps=rasterio.open) as opens, \
                    patch("ingestion.safe_processing.WarpedVRT") as warp:
                with self.assertRaises(rasterio.errors.RasterioIOError):
                    probe_safe.main()
                self.assertTrue(str(opens.call_args.args[0]).endswith("_SCL_20m.jp2"))
                self.assertTrue(all(call.kwargs.get("driver") == "JP2OpenJPEG" for call in opens.call_args_list))
                warp.assert_not_called()
            report = json.loads((output_dir / "probe-result.json").read_text())
            self.assertEqual(report["status"], "failed")
            self.assertNotIn(str(canary), report["error"])
            self.assertFalse((output_dir / "reflectance.tif").exists())
            self.assertFalse((output_dir / "reflectance.json").exists())

    def test_scl_full_affine_type_and_edge_window(self):
        cases = {
            "normal": (from_origin(300000, 4200000, 20, 20), "uint8"),
            "rotated": (Affine(12, 16, 300000, 16, -12, 4200000), "uint8"),
            "reversed": (Affine(-20, 0, 300000, 0, -20, 4200000), "uint8"),
            "shifted": (from_origin(300001, 4200000, 20, 20), "uint8"),
            "wrong-type": (from_origin(300000, 4200000, 20, 20), "uint16"),
        }
        with tempfile.TemporaryDirectory() as directory:
            for name, (transform, dtype) in cases.items():
                with self.subTest(case=name):
                    root = Path(directory) / name
                    archive = synthetic_safe(root, scl_transform=transform, scl_dtype=dtype)
                    with SafeProduct(archive) as product:
                        paths = materialize_bands(product, root / "attempt")
                        with rasterio.open(paths["SCL"], driver="JP2OpenJPEG") as source:
                            np.testing.assert_allclose(list(source.transform)[:6], list(transform)[:6], rtol=0, atol=1e-9)
                        if name == "normal":
                            self.assertEqual(prepare_window(product, paths, [0, 0, 8, 8],
                                root / "full.tif", "0" * 64)["validPixels"], 64)
                            self.assertEqual(prepare_window(product, paths, [6, 6, 2, 2],
                                root / "edge.tif", "0" * 64)["validPixels"], 4)
                        else:
                            with patch("ingestion.safe_processing.WarpedVRT") as warp:
                                with self.assertRaisesRegex(ValueError, "SCL grid/type"):
                                    prepare_window(product, paths, [0, 0, 8, 8], root / "bad.tif", "0" * 64)
                                warp.assert_not_called()
                            self.assertFalse((root / "bad.tif").exists())

    def test_xml_and_path_rejection(self):
        for raw in (b'<!DOCTYPE x [<!ENTITY e "bad">]><x/>', b'<x/>', b'\x00<x/>'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                parse_xml(raw, f"{{{PRODUCT_NS}}}Level-2A_User_Product")
        for name in ("../escape", "a/../escape", "C:/escape", "/escape", "a\\escape", "a/NUL.txt", "a/b."):
            with self.subTest(name=name), self.assertRaises(ValueError):
                member_path(name)

    def test_zip_aliases_and_links_rejected_before_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.zip"
            for linked in (False, True):
                with zipfile.ZipFile(path, "w") as archive:
                    if linked:
                        entry = zipfile.ZipInfo("a.SAFE/link")
                        entry.create_system = 3
                        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                        archive.writestr(entry, "../../escape")
                    else:
                        archive.writestr("a.SAFE/x", "one")
                        archive.writestr("a.SAFE/X", "two")
                with self.assertRaisesRegex(ValueError, "Duplicate/aliased or special"):
                    with SafeProduct(path):
                        pass

    def test_window_size_coverage_and_types(self):
        self.assertEqual(checked_window([0, 0, 2048, 2048], 2048, 2048).width, 2048)
        for values in ([0, 0, 2049, 1], [1, 0, 2048, 1], [-1, 0, 1, 1], [0, 0, 0, 1], [False, 0, 1, 1]):
            with self.subTest(values=values), self.assertRaises(ValueError):
                checked_window(values, 2048, 2048)

    def test_per_band_calibration_nodata_scl_and_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {}
            transform = from_origin(300000, 4200000, 10, 10)
            calibration = {name: {"scale": 0.0001, "offset": offset}
                           for name, offset in zip(CALIBRATION_NAMES, (-0.1, -0.2, -0.05, -0.3))}
            for band in BANDS:
                raw = np.full((8, 8), 2000, dtype="uint16")
                if band == "B02":
                    raw[0, 0], raw[0, 1] = 0, 500
                paths[band] = root / (band + ".jp2")
                with rasterio.open(paths[band], "w", driver="JP2OpenJPEG", width=8, height=8, count=1,
                                   dtype="uint16", crs="EPSG:32652", transform=transform,
                                   REVERSIBLE="YES", QUALITY=100) as output:
                    output.write(raw, 1)
            paths["SCL"] = root / "SCL.jp2"
            quality = np.full((4, 4), 4, dtype="uint8")
            quality[2, 2] = 9
            with rasterio.open(paths["SCL"], "w", driver="JP2OpenJPEG", width=4, height=4, count=1,
                               dtype="uint8", crs="EPSG:32652", transform=from_origin(300000, 4200000, 20, 20),
                               REVERSIBLE="YES", QUALITY=100) as output:
                output.write(quality, 1)
            product = SimpleNamespace(width=8, height=8, crs="EPSG:32652", transform=list(transform)[:6],
                calibration=calibration, source_id="synthetic-unit-test-only", capture_time="2024-01-01T00:00:00Z",
                baseline="05.13", metadata_sha256="0" * 64, quantification=10000,
                raw_offsets={b: calibration[n]["offset"] * 10000 for b, n in zip(BANDS, CALIBRATION_NAMES)},
                granule="synthetic-unit-test-only")
            output = root / "result.tif"
            summary = prepare_window(product, paths, [0, 0, 8, 8], output, "0" * 64)
            with rasterio.open(output) as result:
                pixels = result.read()
                self.assertTrue((pixels[:, 0, 0] == -9999).all())
                self.assertTrue((pixels[:, 4:6, 4:6] == -9999).all())
                np.testing.assert_allclose(pixels[:, 0, 1], [-0.05, 0, 0.15, -0.1], atol=1e-7)
                self.assertEqual(result.scales, (1, 1, 1, 1))
                self.assertEqual(result.descriptions, BANDS)
            manifest = json.loads(output.with_suffix(".json").read_text())
            self.assertEqual(manifest["calibration"], calibration)
            self.assertEqual(summary["validPixels"], 59)
            self.assertGreater(summary["negativeReflectanceValues"], 0)


if __name__ == "__main__":
    unittest.main()
