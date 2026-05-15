import argparse
import json
import sys
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image


def normalize_to_uint8(array):
    data = np.ma.array(array).astype("float32")
    valid = data.compressed()
    if valid.size == 0:
        return np.zeros(data.shape, dtype="uint8")

    # 使用百分位拉伸减少极端值影响，让不同传感器影像也能生成可辨认的预览图。
    low, high = np.percentile(valid, [2, 98])
    if high <= low:
        high = low + 1

    scaled = (data.filled(low) - low) * 255.0 / (high - low)
    return np.clip(scaled, 0, 255).astype("uint8")


def output_shape(width, height, max_size):
    if width <= max_size and height <= max_size:
        return height, width

    scale = min(max_size / width, max_size / height)
    return max(1, int(height * scale)), max(1, int(width * scale))


def generate_thumbnail(input_path, output_path, max_size):
    src_path = Path(input_path)
    if not src_path.exists():
        raise FileNotFoundError(f"GeoTIFF 文件不存在：{src_path}")

    dst_path = Path(output_path)
    dst_path.parent.mkdir(parents=True, exist_ok=True)

    with rasterio.open(src_path) as dataset:
        out_height, out_width = output_shape(dataset.width, dataset.height, max_size)
        if dataset.count >= 3:
            # 多波段影像优先使用前三个波段作为 RGB，符合常见遥感影像预览习惯。
            bands = dataset.read(
                [1, 2, 3],
                out_shape=(3, out_height, out_width),
                masked=True,
            )
            rgb = np.stack([normalize_to_uint8(bands[i]) for i in range(3)], axis=-1)
            image = Image.fromarray(rgb, mode="RGB")
        else:
            # 单波段影像没有天然 RGB 组合，使用灰度图保留亮度信息。
            band = dataset.read(
                1,
                out_shape=(out_height, out_width),
                masked=True,
            )
            gray = normalize_to_uint8(band)
            image = Image.fromarray(gray, mode="L")

        image.save(dst_path, format="PNG")

    return {
        "output": str(dst_path),
        "width": image.width,
        "height": image.height,
        "mode": image.mode,
    }


def response(success, data=None, error=None):
    return {
        "success": success,
        "data": data,
        "error": error,
    }


def main():
    parser = argparse.ArgumentParser(description="生成 GeoTIFF PNG 缩略图。")
    parser.add_argument("input", help="本地 GeoTIFF 文件路径。")
    parser.add_argument("output", help="输出 PNG 文件路径。")
    parser.add_argument("--max-size", type=int, default=512, help="缩略图最大宽度或高度。")
    args = parser.parse_args()

    try:
        data = generate_thumbnail(args.input, args.output, args.max_size)
        print(json.dumps(response(True, data=data), ensure_ascii=False))
        return 0
    except Exception as exc:
        print(json.dumps(response(False, error=f"缩略图生成失败：{exc}"), ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
