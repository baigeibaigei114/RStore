"""Java LOCAL validator's native child. Fixed local files only; no network or business callbacks."""
import ctypes
import json
import os
from pathlib import Path
import resource
import signal
import sys


def main():
    if len(sys.argv) != 7 or os.name != "posix":
        return 2
    raster, region, result, ready = map(Path, sys.argv[1:5])
    parent = int(sys.argv[5])
    seconds = max(1, int(sys.argv[6]))
    os.setsid()
    if ctypes.CDLL(None).prctl(1, signal.SIGKILL) != 0 or os.getppid() != parent:
        return 70
    resource.setrlimit(resource.RLIMIT_AS, (2816 * 1024**2, 2816 * 1024**2))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    os.environ.update(GDAL_NUM_THREADS="1", OPJ_NUM_THREADS="1", OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="1",
        GDAL_CACHEMAX="128", GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", GDAL_PAM_ENABLED="NO")
    ready.write_text(str(os.getpid()), encoding="ascii")
    signal.signal(signal.SIGALRM, lambda *_: os._exit(124))
    signal.alarm(seconds)
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import numpy as np
    import rasterio
    from scripts.parse_metadata import parse_metadata
    from ingestion.standardization import reflectance_counts
    with rasterio.Env(GDAL_CACHEMAX=128 * 1024**2, GDAL_NUM_THREADS="1"):
        with rasterio.open(raster, driver="GTiff") as dataset:
            if dataset.count != 4 or not 1 <= dataset.width <= 2048 or not 1 <= dataset.height <= 2048:
                return 3
            data = dataset.read(masked=True)
            valid = ~np.ma.getmaskarray(data).any(axis=0) & np.isfinite(data.filled(np.nan)).all(axis=0)
            counts = reflectance_counts(dataset, json.loads(region.read_bytes()))
            counts["rectangleValidReflectanceCount"] = int(valid.sum())
        payload = {"metadata": parse_metadata(raster), **counts}
        encoded = json.dumps(payload, allow_nan=False, separators=(",", ":")).encode()
        if len(encoded) > 1024**2:
            return 4
        with result.open("xb") as output:
            output.write(encoded)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        # No file paths, XML, provider response, or traceback in the Java callback boundary.
        raise SystemExit(1)
