"""Official single-granule inspection using S0's immutable JP2/grid/calibration guards."""
import hashlib
from pathlib import Path
import shutil

import rasterio
from rasterio.windows import Window
from ingestion.safe_product import SafeProduct, BANDS, PARSER_VERSION, sha256_file
from ingestion.safe_processing import materialize_bands, check_grid, check_scl_grid, write_preview
from ingestion.preflight import StageFailure


def download_archive(client, reference, destination, max_bytes):
    expected = reference.get("bytes")
    if type(expected) is not int or not 1 <= expected <= max_bytes:
        raise StageFailure("RESOURCE_LIMIT")
    stat = client.stat_object(reference["bucket"], reference["key"])
    if stat.size != expected:
        raise StageFailure("INPUT_HASH_MISMATCH")
    response = client.get_object(reference["bucket"], reference["key"])
    total, digest = 0, hashlib.sha256()
    try:
        with Path(destination).open("xb") as stream:
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                total += len(chunk)
                if total > expected:
                    raise StageFailure("RESOURCE_LIMIT")
                digest.update(chunk)
                stream.write(chunk)
    finally:
        response.close()
        response.release_conn()
    if total != expected or digest.hexdigest() != reference["sha256"]:
        raise StageFailure("INPUT_HASH_MISMATCH")


def inspect_archive(archive, directory, *, max_disk_bytes=10 * 1024**3, safety_bytes=1024**3):
    with SafeProduct(archive) as product:
        selected = sum(product.require_member(name).file_size for name in product.band_members.values())
        if (Path(archive).stat().st_size + selected + 512 * 1024**2 + safety_bytes > max_disk_bytes
                or shutil.disk_usage(directory).free < selected + 512 * 1024**2 + safety_bytes):
            raise StageFailure("RESOURCE_LIMIT")
        paths = materialize_bands(product, directory)
        with rasterio.Env(GDAL_CACHEMAX=128 * 1024**2, GDAL_NUM_THREADS="1"):
            with rasterio.open(paths["B04"], driver="JP2OpenJPEG") as reference:
                check_grid(reference, product)
                grid = {"crs": reference.crs.to_string(), "width": reference.width, "height": reference.height,
                        "transform": list(reference.transform)[:6]}
                for band in BANDS:
                    with rasterio.open(paths[band], driver="JP2OpenJPEG") as source:
                        check_grid(source, product)
                        source.read(1, window=Window(0, 0, 1, 1))
                with rasterio.open(paths["SCL"], driver="JP2OpenJPEG") as source:
                    check_scl_grid(source, reference)
                    source.read(1, window=Window(0, 0, 1, 1))
                    scl = {"dtype": source.dtypes[0], "width": source.width, "height": source.height,
                           "crs": source.crs.to_string(), "transform": list(source.transform)[:6], "validClasses": [4, 5, 6, 7]}
            preview_file = Path(directory) / "preview.png"
            preview = write_preview(product, paths, preview_file, max_edge=512)
        if preview_file.stat().st_size > 8 * 1024**2:
            raise StageFailure("RESOURCE_LIMIT")
        descriptor = {"schemaVersion": 1, "sourceId": product.source_id, "captureTime": product.capture_time,
            "granule": product.granule, "processingBaseline": product.baseline, "parserVersion": PARSER_VERSION,
            "profileVersion": "safe-l2a-profile-v1", "productMetadataSha256": product.metadata_sha256,
            "grid": grid, "calibration": product.calibration,
            "rawCalibration": {"quantificationValue": product.quantification, "boaAddOffsets": product.raw_offsets},
            "scl": scl, "previewGrid": {key: preview[key] for key in ("crs", "width", "height", "transform", "bounds")}}
        return {"descriptor": descriptor, "preview": {"bytes": preview_file.stat().st_size, "sha256": sha256_file(preview_file)}}
