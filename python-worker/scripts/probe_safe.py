"""S0 local SAFE ZIP probe: metadata, display preview and a <=2048 crop.

Run in the Worker Python runtime. This command never writes business state.
Caller controls a fresh output directory and external process timeout/memory limits.
"""
import argparse
import json
import os
import platform
import shutil
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import rasterio
from ingestion.safe_product import PARSER_VERSION, SafeProduct, sha256_file
from ingestion.safe_processing import materialize_bands, prepare_window, write_preview


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--window", nargs=4, type=int, required=True, metavar=("COL", "ROW", "WIDTH", "HEIGHT"))
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    report = {"status": "running", "parserVersion": PARSER_VERSION, "stagesSeconds": {},
              "businessStateWritten": False, "runtime": {"python": platform.python_version(),
                  "rasterio": rasterio.__version__, "gdal": rasterio.__gdal_version__}}
    # Only this command's process is affected; no machine-wide environment changes.
    os.environ["OPJ_NUM_THREADS"] = "1"
    try:
        with rasterio.Env(GDAL_CACHEMAX=128 * 1024**2, GDAL_NUM_THREADS="1") as env:
            report["runtime"]["jp2OpenJpegRegistered"] = "JP2OpenJPEG" in env.drivers()
            if not report["runtime"]["jp2OpenJpegRegistered"]:
                raise ValueError("Worker Rasterio has no JP2OpenJPEG driver")
            checkpoint = time.monotonic()
            archive_hash = sha256_file(args.archive)
            report["stagesSeconds"]["hash"] = time.monotonic() - checkpoint
            with SafeProduct(args.archive) as product:
                report.update({"sourceId": product.source_id, "archiveSha256": archive_hash,
                    "archiveBytes": args.archive.stat().st_size, "expandedArchiveBytes": product.total_expanded_bytes,
                    "entries": len(product.members), "processingBaseline": product.baseline,
                    "sourceGrid": {"crs": product.crs, "width": product.width, "height": product.height,
                                   "transform": product.transform},
                    "calibration": product.calibration, "rawOffsets": product.raw_offsets})
                # Reserve room for selected files, output and transient copies. S1 sets API budgets.
                selected = sum(product.require_member(n).file_size for n in product.band_members.values())
                if shutil.disk_usage(args.output_dir).free < selected + 512 * 1024**2:
                    raise ValueError("Insufficient temporary disk space for probe")
                with tempfile.TemporaryDirectory(prefix="safe-attempt-", dir=args.output_dir) as work:
                    checkpoint = time.monotonic()
                    paths = materialize_bands(product, work)
                    report["stagesSeconds"]["selectedExtraction"] = time.monotonic() - checkpoint
                    report["selectedExtractedBytes"] = product.extracted_bytes
                    checkpoint = time.monotonic()
                    report["preview"] = write_preview(product, paths, args.output_dir / "preview.png")
                    report["stagesSeconds"]["preview"] = time.monotonic() - checkpoint
                    checkpoint = time.monotonic()
                    report["crop"] = prepare_window(product, paths, args.window,
                        args.output_dir / "reflectance.tif", archive_hash)
                    report["stagesSeconds"]["crop"] = time.monotonic() - checkpoint
        report["status"] = "succeeded"
    except Exception as exc:
        report.update({"status": "failed", "errorType": type(exc).__name__, "error": str(exc)})
        raise
    finally:
        report["elapsedSeconds"] = time.monotonic() - started
        if sys.platform == "linux":
            import resource
            report["peakRssKiB"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        (args.output_dir / "probe-result.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, allow_nan=False), flush=True)


if __name__ == "__main__":
    main()
