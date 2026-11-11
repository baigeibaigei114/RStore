"""Evidence preserved in a versioned, mask-aware result raster."""

import numpy as np

from utils.raster_utils import read_valid_band

VERSION_TAG = "result_analysis_version"


def index_result_tags(algorithm: str, bands: dict) -> dict:
    return {VERSION_TAG: "1", "algorithm": algorithm, **{name: str(value) for name, value in bands.items()}}


def read_index_result(dataset, algorithm: str, bands: dict):
    tags = dataset.tags()
    expected = index_result_tags(algorithm, bands)
    if dataset.count != 1 or any(tags.get(name) != value for name, value in expected.items()):
        raise ValueError("Result raster does not match its analysis metadata")
    return read_valid_band(dataset, 1)


def index_statistics(values) -> dict:
    valid = values[np.isfinite(values)]
    total = int(values.size)
    count = int(valid.size)
    if count == 0:
        raise ValueError("Index has no valid pixels after masking")
    return {"totalPixelCount": total, "validPixelCount": count, "invalidPixelCount": total - count,
            "validPixelRatio": count / total, "min": float(valid.min()), "max": float(valid.max()),
            "mean": float(valid.mean(dtype="float64"))}
