"""Bounded SAFE preview/crop primitives; no asset registration or NDVI task."""
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image
from rasterio.enums import Resampling
from rasterio.transform import Affine
from rasterio.vrt import WarpedVRT
from rasterio.windows import Window

from ingestion.safe_product import BANDS, CALIBRATION_NAMES, PARSER_VERSION
from scripts.prepare_ndvi_baseline import NODATA, write_stack

GRID_TOLERANCE = 1e-9  # Absolute tolerance per affine coefficient; no relative origin tolerance.


def checked_window(values, width, height):
    if len(values) != 4 or any(type(n) is not int for n in values):
        raise ValueError("Window requires four integers")
    col, row, cols, rows = values
    if min(col, row) < 0 or min(cols, rows) < 1 or max(cols, rows) > 2048:
        raise ValueError("Window must be positive and at most 2048 x 2048")
    if col + cols > width or row + rows > height:
        raise ValueError("Window is not fully covered by the source grid")
    return Window(col, row, cols, rows)


def check_grid(dataset, product):
    if (dataset.driver != "JP2OpenJPEG" or dataset.count != 1 or dataset.dtypes != ("uint16",)
            or str(dataset.crs) != product.crs or dataset.width != product.width
            or dataset.height != product.height
            or not np.allclose(list(dataset.transform)[:6], product.transform, rtol=0, atol=GRID_TOLERANCE)):
        raise ValueError("JP2 spectral grid/type disagrees with SAFE metadata")


def check_scl_grid(dataset, reference):
    # Axis lengths (dataset.res) cannot establish orientation or full coverage.
    # The fixed 20m grid has exactly half the dimensions and twice each pixel axis.
    expected_transform = reference.transform * Affine.scale(2, 2)
    if (dataset.driver != "JP2OpenJPEG" or dataset.count != 1 or dataset.dtypes != ("uint8",)
            or dataset.crs != reference.crs or dataset.width * 2 != reference.width
            or dataset.height * 2 != reference.height
            or not np.allclose(list(dataset.transform)[:6], list(expected_transform)[:6],
                               rtol=0, atol=GRID_TOLERANCE)):
        raise ValueError("SCL grid/type disagrees with 10m product grid")


def materialize_bands(product, directory):
    return {band: product.materialize(name, directory) for band, name in product.band_members.items()}


def prepare_window(product, paths, values, output, archive_sha256):
    window = checked_window(values, product.width, product.height)
    arrays = []
    # Restrict drivers at open time: a .jp2 suffix must not enable VRT references.
    with rasterio.open(paths["B04"], driver="JP2OpenJPEG") as reference:
        check_grid(reference, product)
        crs, transform = reference.crs, reference.window_transform(window)
        for band, name in zip(BANDS, CALIBRATION_NAMES):
            with rasterio.open(paths[band], driver="JP2OpenJPEG") as source:
                check_grid(source, product)
                raw = source.read(1, window=window, masked=True).astype("float32").filled(np.nan)
                raw[raw == 0] = np.nan
                calibration = product.calibration[name]
                arrays.append(raw * calibration["scale"] + calibration["offset"])
        with rasterio.open(paths["SCL"], driver="JP2OpenJPEG") as source:
            check_scl_grid(source, reference)
            with WarpedVRT(source, crs=crs, transform=transform, width=int(window.width),
                           height=int(window.height), resampling=Resampling.nearest) as aligned:
                quality = aligned.read(1, masked=True).filled(0)
    data = np.stack(arrays)
    valid = np.isfinite(data).all(axis=0) & np.isin(quality, [4, 5, 6, 7])
    if not valid.any():
        raise ValueError("Selected window has no valid pixels")
    data[:, ~valid] = NODATA
    provenance = {
        "sourceId": product.source_id, "captureTime": product.capture_time,
        "window": list(values), "calibration": product.calibration,
        "sclValidClasses": [4, 5, 6, 7], "sclResampling": "nearest",
        "safe": {"parserVersion": PARSER_VERSION, "processingBaseline": product.baseline,
                 "archiveSha256": archive_sha256, "productMetadataSha256": product.metadata_sha256,
                 "quantificationValue": product.quantification, "boaAddOffsets": product.raw_offsets,
                 "granule": product.granule},
    }
    write_stack(output, data, crs, transform, provenance)
    return {"validPixels": int(valid.sum()), "totalPixels": int(valid.size),
            "negativeReflectanceValues": int((data[:, valid] < 0).sum())}


def write_preview(product, paths, output, max_edge=512):
    """Display-only overview; reflectance crop is never downsampled or stretched."""
    if type(max_edge) is not int or not 1 <= max_edge <= 1024:
        raise ValueError("Preview edge must be between 1 and 1024")
    output = Path(output)
    if output.exists():
        raise FileExistsError("Refusing to overwrite preview")
    ratio = max(product.width, product.height) / max_edge
    width = max(1, min(product.width, round(product.width / max(1, ratio))))
    height = max(1, min(product.height, round(product.height / max(1, ratio))))
    rgb = []
    valid = np.ones((height, width), dtype=bool)
    for band, name in (("B04", "red"), ("B03", "green"), ("B02", "blue")):
        with rasterio.open(paths[band], driver="JP2OpenJPEG") as source:
            check_grid(source, product)
            raw = source.read(1, out_shape=(height, width), masked=True, resampling=Resampling.nearest)
            values = raw.astype("float32").filled(np.nan)
            valid &= np.isfinite(values) & (values != 0)
            rgb.append(values * product.calibration[name]["scale"] + product.calibration[name]["offset"])
    rgba = np.zeros((height, width, 4), dtype="uint8")
    if not valid.any():
        raise ValueError("Product preview has no valid pixels")
    for i, values in enumerate(rgb):
        low, high = np.percentile(values[valid], [2, 98])
        displayed = np.clip((np.nan_to_num(values, nan=float(low)) - low) / max(high - low, 1e-6), 0, 1)
        rgba[:, :, i] = (displayed * 255).astype("uint8")
    rgba[:, :, 3] = valid * 255
    Image.fromarray(rgba).save(output, format="PNG")
    a, b, c, d, e, f = product.transform
    return {"file": output.name, "width": width, "height": height, "crs": product.crs,
            "transform": [a * product.width / width, b, c, d, e * product.height / height, f],
            "bounds": [c, f + e * product.height, c + a * product.width, f],
            "purpose": "display_only; not an admitted analysis asset", "rgbBands": [3, 2, 1]}
