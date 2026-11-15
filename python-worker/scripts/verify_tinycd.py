"""Benchmarks the production tiling code on Altadena and the LEVIR-CD test split."""

import argparse
import io
import json
import sys
from pathlib import Path
from time import perf_counter
from zipfile import ZipFile

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from processors.tinycd import CORE_SIZE, TILE_SIZE, TinyCD, compute_raster  # noqa: E402


def read_image(archive, name, mode):
    with Image.open(io.BytesIO(archive.read(name))) as image:
        return np.array(image.convert(mode))


def evaluate_levir(model, path):
    started = perf_counter()
    tp = fp = fn = 0
    with ZipFile(path) as archive:
        names = sorted(name[2:] for name in archive.namelist() if name.startswith("A/") and name.endswith(".png"))
        if len(names) != 128:
            raise ValueError("The test archive must contain the 128 original LEVIR-CD test pairs")
        for index, name in enumerate(names, start=1):
            before = read_image(archive, f"A/{name}", "RGB")
            after = read_image(archive, f"B/{name}", "RGB")
            truth = read_image(archive, f"label/{name}", "L") > 0
            if before.shape != (1024, 1024, 3) or before.shape != after.shape or truth.shape != before.shape[:2]:
                raise ValueError(f"Unexpected shape for LEVIR-CD test pair {name}")
            guess = model.predict(before, after, np.ones(truth.shape, dtype=bool)) == 1
            tp += int(np.count_nonzero(guess & truth))
            fp += int(np.count_nonzero(guess & ~truth))
            fn += int(np.count_nonzero(~guess & truth))
            if index % 16 == 0:
                print(f"LEVIR-CD {index}/{len(names)} · {perf_counter() - started:.1f}s", flush=True)
    return {"pairs": len(names), "tp": tp, "fp": fp, "fn": fn, "precision": tp / (tp + fp),
            "recall": tp / (tp + fn), "f1": 2 * tp / (2 * tp + fp + fn), "seconds": perf_counter() - started}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", required=True, type=Path)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--altadena", type=Path)
    parser.add_argument("--levir", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not (args.altadena or args.levir):
        parser.error("use --altadena, --levir or both")
    args.output.mkdir(parents=True, exist_ok=True)
    started = perf_counter()
    model = TinyCD(args.weights, args.threads)
    result = {"threads": args.threads, "tile": TILE_SIZE, "core": CORE_SIZE, "loadSeconds": perf_counter() - started}
    if args.altadena:
        started = perf_counter()
        report = compute_raster(model, args.altadena / "altadena-before-2025-01-01.tif",
                                args.altadena / "altadena-after-2025-01-10.tif", args.output / "altadena.tif")
        result["altadena"] = {**report, "seconds": perf_counter() - started}
        print(json.dumps(result["altadena"]), flush=True)
    if args.levir:
        result["levir"] = evaluate_levir(model, args.levir)
    (args.output / "metrics.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
