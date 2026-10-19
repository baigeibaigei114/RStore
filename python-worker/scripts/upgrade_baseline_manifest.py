"""仅将已验收的固定公开样本说明显式升级到 v1，不改 TIFF 或旧 JSON。"""
import argparse
import hashlib
import json
from pathlib import Path

EXPECTED_SHA256 = "edff6e35918a42a055125fe2677f230a7afedaf5ba78141dee80bd2601f494b1"


def upgrade(source, manifest, output):
    with Path(source).open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    data = json.loads(Path(manifest).read_text(encoding="utf-8"))
    if (digest != EXPECTED_SHA256 or data.get("sha256") != digest
            or data.get("sourceId") != "S2B_50RLT_20241129_0_L2A"
            or data.get("boaOffsetAlreadyApplied") is not True):
        raise ValueError("Only the verified, corrected baseline sample can be upgraded")
    for band in ("blue", "green", "red", "nir"):
        if data["calibration"][band]["scale"] != 0.0001 or data["calibration"][band]["offset"] != 0:
            raise ValueError("Unexpected calibration")
    data.update(schemaVersion=1, productType="SENTINEL2_L2A", preprocessingVersion="s2-reflectance-v1")
    data["transform"] = data["transform"][:6]
    data["manifestMigration"] = "Explicit upgrade of verified baseline; raster unchanged"
    with Path(output).open("x", encoding="utf-8") as stream:
        json.dump(data, stream, indent=2, allow_nan=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    upgrade(args.input, args.manifest, args.output)
    print("Created v1 manifest; original files unchanged")
