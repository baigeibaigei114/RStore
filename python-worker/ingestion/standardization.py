"""Deterministic STANDARDIZE candidate generation; Java alone grants admission/registers assets."""
from pathlib import Path
import shutil

import numpy as np
import rasterio

from ingestion.safe_product import SafeProduct, PARSER_VERSION, sha256_file
from ingestion.safe_processing import materialize_bands, prepare_window
from ingestion.preflight import preflight_window, StageFailure
from utils.region_mask import region_mask


def reflectance_counts(dataset, snapshot):
    mask = region_mask(dataset, snapshot, allow_empty=True)
    data = dataset.read(masked=True)
    valid = ~np.ma.getmaskarray(data).any(axis=0) & np.isfinite(data.filled(np.nan)).all(axis=0)
    return {"regionPixelCount": int(mask.sum()), "regionValidReflectanceCount": int((mask & valid).sum())}


def standardize_archive(archive, directory, context):
    descriptor = context["descriptor"]
    with SafeProduct(archive) as product:
        expected = {"sourceId": product.source_id, "captureTime": product.capture_time, "granule": product.granule,
            "processingBaseline": product.baseline, "parserVersion": PARSER_VERSION,
            "profileVersion": "safe-l2a-profile-v1", "productMetadataSha256": product.metadata_sha256,
            "grid": {"crs": product.crs, "width": product.width, "height": product.height, "transform": list(product.transform)},
            "calibration": product.calibration,
            "rawCalibration": {"quantificationValue": product.quantification, "boaAddOffsets": product.raw_offsets}}
        if any(descriptor.get(key) != value for key, value in expected.items()):
            raise StageFailure("INPUT_HASH_MISMATCH")
        actual_window = preflight_window(expected["grid"], context["regionSnapshot"])
        if actual_window != context["window"]:
            raise StageFailure("INPUT_HASH_MISMATCH")
        selected = sum(product.require_member(name).file_size for name in product.band_members.values())
        safety = max(int(context["resourceBudget"]["diskSafetyBytes"]), 1024**3)
        needed = selected + 512 * 1024**2 + safety
        if (Path(archive).stat().st_size + needed > 10 * 1024**3 or shutil.disk_usage(directory).free < needed):
            raise StageFailure("RESOURCE_LIMIT")
        paths = materialize_bands(product, directory)
        tiff = Path(directory) / "reflectance.tif"
        with rasterio.Env(GDAL_CACHEMAX=128 * 1024**2, GDAL_NUM_THREADS="1"):
            try:
                prepare_window(product, paths, actual_window, tiff, context["input"]["sha256"])
            except ValueError as error:
                if str(error) == "Selected window has no valid pixels":
                    raise StageFailure("NO_VALID_PIXELS") from error
                raise StageFailure("INVALID_GRID") from error
            with rasterio.open(tiff, driver="GTiff") as output:
                counts = reflectance_counts(output, context["regionSnapshot"])
        result = {"outputs": [], **counts}
        for role, path, maximum in (("TIFF", tiff, 256 * 1024**2), ("MANIFEST", tiff.with_suffix(".json"), 65536)):
            limit = min(context["outputs"][role]["maxBytes"], maximum)
            size = path.stat().st_size
            if not 1 <= size <= limit:
                raise StageFailure("RESOURCE_LIMIT")
            result["outputs"].append({"role": role, "bytes": size, "sha256": sha256_file(path)})
        return result
