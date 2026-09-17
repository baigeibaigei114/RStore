"""仅用于恢复本次首次下载重复应用 offset 的样本；不覆盖原件。

反解回整数 DN 并检查距整数的误差，再按经核对的 COG 规则转换。
完整来源和原样本 SHA256 一起记录，之后正常下载无需运行本脚本。
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import rasterio

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "python-worker"))
from scripts.prepare_ndvi_baseline import NODATA, write_stack
from fetch_public_sample import ITEM_ID, calibration_for_cog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.input.with_suffix(".json").read_text(encoding="utf-8"))
    item = json.loads(args.input.with_suffix(".stac.json").read_text(encoding="utf-8"))
    digest = hashlib.sha256(args.input.read_bytes()).hexdigest()
    if digest != manifest["sha256"] or manifest["sourceId"] != ITEM_ID:
        raise ValueError("Unexpected source sample or checksum")
    if item["properties"].get("earthsearch:boa_offset_applied") is not True:
        raise ValueError("Recovery requires confirmed pre-applied offset")
    with rasterio.open(args.input) as source:
        data = source.read(masked=True).astype("float64").filled(np.nan)
        for index, name in enumerate(("blue", "green", "red", "nir")):
            original = manifest["calibration"][name]
            if original["offset"] != -0.1 or original["scale"] != 0.0001:
                raise ValueError("This is not the original incorrectly calibrated sample")
            valid = np.isfinite(data[index])
            raw = (data[index][valid] - original["offset"]) / original["scale"]
            if np.max(np.abs(raw - np.rint(raw))) > 0.002:
                raise ValueError("Cannot recover original integer DN unambiguously")
            scale, offset = calibration_for_cog(item, item["assets"][name])
            data[index][valid] = np.rint(raw).astype("float32") * scale + offset
            manifest["calibration"][name] = {
                "scale": scale, "offset": offset, "catalogOffset": original["offset"]}
        manifest["recoveredFromSha256"] = digest
        manifest["boaOffsetAlreadyApplied"] = True
        manifest["recoveryReason"] = "Undo duplicate offset; recover integer COG DN before scaling"
        manifest["expectedNdviRange"] = [-1, 1]
        write_stack(args.output, np.where(np.isfinite(data), data, NODATA),
                    source.crs, source.transform, manifest)
    args.output.with_suffix(".stac.json").write_text(
        json.dumps(item, indent=2, allow_nan=False), encoding="utf-8")
    print(f"Created corrected sample {args.output}; original preserved")


if __name__ == "__main__":
    main()
