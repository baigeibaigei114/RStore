"""
GeoTIFF 缩略图生成脚本 -- 供 Java 后端通过 ProcessBuilder 调用的命令行工具。

职责：
  - 读取 GeoTIFF 文件并将其转换为 PNG 缩略图。
  - 多波段影像优先使用前三个波段作为 RGB 预览，符合常见遥感影像习惯。
  - 单波段影像使用灰度图保留亮度信息。
  - 自动缩放至不超过 max_size x max_size（默认 512px），保持宽高比。

设计要点：
  - 百分位拉伸（2%-98%）而非 min-max 线性拉伸，可抵抗遥感影像中极端值（云、雪等）干扰。
  - 使用 rasterio 的 out_shape 参数在读取时直接降采样，避免加载全分辨率大文件导致的 OOM。
  - 使用 Pillow（PIL）库生成 PNG，这是 Python 生态中最成熟的图像处理库。
  - 作为独立脚本通过 stdout 输出 JSON，与 Java 后端的 ProcessBuilder 通信。
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image


def normalize_to_uint8(array):
    """将浮点数组通过百分位拉伸归一化为 uint8（0-255）图像，用于预览图生成。

    遥感影像通常具有较大的动态范围（如 16 位 DN 值 0-65535），
    直接线性映射到 0-255 会导致大部分像元集中在很窄的灰度区间，预览图不可辨认。

    百分位拉伸策略：
      - 截取 2% 到 98% 百分位之间的像素值，排除传感器噪声和极端值（如云顶高亮）。
      - 将有效范围内的值线性映射到 0-255。
      - 使用 np.ma.array（掩膜数组）正确处理 NoData 遮罩。

    Args:
        array: 浮点数组（可能包含掩膜，如 rasterio 的 masked=True 读取结果）。

    Returns:
        uint8 类型的图像数组 (0-255)，形状与输入一致。
    """
    # np.ma.array 创建掩膜数组（Masked Array）：
    # 掩膜数组是 NumPy 的一个特性，可以标记某些元素为"无效"而无需删除它们。
    # 在 Java 中没有直接的等价物，通常需要使用一个单独的 boolean 数组来标记无效值。
    data = np.ma.array(array).astype("float32")
    # compressed() 返回去除了掩膜元素的一维数组，即只保留有效像素。
    # 相当于先遍历全部像素，跳过 NoData 标记的位置，收集有效值。
    valid = data.compressed()
    if valid.size == 0:
        return np.zeros(data.shape, dtype="uint8")

    # 使用百分位拉伸减少极端值影响，让不同传感器影像也能生成可辨认的预览图。
    # np.percentile 计算指定的百分位数。2% 和 98% 是遥感预览的常用参数，
    # 相比 0%-100%（min-max 拉伸）更能抵抗异常值干扰。
    low, high = np.percentile(valid, [2, 98])
    if high <= low:
        high = low + 1

    # 将 [low, high] 区间的值线性拉伸到 [0, 255]。
    # filled(low) 将掩膜（NoData）位置用 low 填充，避免拉伸后的无效值污染图像。
    scaled = (data.filled(low) - low) * 255.0 / (high - low)
    # np.clip 将值裁剪到 [0, 255] 范围（超出部分截断），
    # 相当于 Java 中 Math.max(0, Math.min(255, value)) 的向量化版本。
    return np.clip(scaled, 0, 255).astype("uint8")


def output_shape(width, height, max_size):
    """计算缩略图的目标尺寸，保持原始宽高比。

    如果原始宽高均不超过 max_size，保留原始尺寸（不放大）。
    否则按比例缩放，使得最长边 = max_size，短边等比例缩小。

    Args:
        width: 原始宽度（像素）。
        height: 原始高度（像素）。
        max_size: 缩略图最大宽度或高度（像素）。

    Returns:
        (new_height, new_width) 元组，保证至少为 1x1 像素。
    """
    if width <= max_size and height <= max_size:
        return height, width

    # min(max_size / width, max_size / height) 计算使得最长边恰好为 max_size 的缩放比例。
    # min 确保缩放后两边都不超过 max_size。
    scale = min(max_size / width, max_size / height)
    # max(1, int(...)) 保证缩放后尺寸至少为 1 像素，防止完全空白。
    return max(1, int(height * scale)), max(1, int(width * scale))


def generate_thumbnail(input_path, output_path, max_size):
    """生成 GeoTIFF 的 PNG 缩略图。

    Args:
        input_path: 输入 GeoTIFF 文件路径。
        output_path: 输出 PNG 文件路径。
        max_size: 缩略图的最大宽度或高度（像素），默认 512。

    Returns:
        包含输出路径、宽度、高度、颜色模式的字典。

    Raises:
        FileNotFoundError: 输入文件不存在。
        rasterio.errors.RasterioIOError: 输入不是有效 GeoTIFF。
    """
    src_path = Path(input_path)
    if not src_path.exists():
        raise FileNotFoundError(f"GeoTIFF 文件不存在：{src_path}")

    dst_path = Path(output_path)
    # mkdir(parents=True, exist_ok=True) 确保输出目录存在，不存在则递归创建。
    # 等价于 Java Files.createDirectories(path)。
    dst_path.parent.mkdir(parents=True, exist_ok=True)

    with rasterio.open(src_path) as dataset:
        out_height, out_width = output_shape(
            dataset.width, dataset.height, max_size
        )
        if dataset.count >= 3:
            # 多波段影像优先使用前三个波段作为 RGB，符合常见遥感影像预览习惯。
            # 使用 rasterio 的 out_shape 参数在读取时直接降采样：
            # 这利用了 GDAL 的内部重采样机制，避免读取全分辨率数据再缩放的性能浪费。
            bands = dataset.read(
                [1, 2, 3],
                out_shape=(3, out_height, out_width),
                masked=True,
            )
            # 列表推导式 [normalize_to_uint8(bands[i]) for i in range(3)] 对三个波段逐一归一化。
            # 列表推导式（list comprehension）是 Python 的标志性语法（PEP 202）：
            # [expression for item in iterable] 等价于 Java 中：
            #   List<T> result = new ArrayList<>();
            #   for (int i = 0; i < 3; i++) result.add(normalize(bands[i]));
            # np.stack([...], axis=-1) 将三个独立的 (H, W) 数组合并为 (H, W, 3) 的 RGB 图像。
            # 在 Java 中需要手动创建 BufferedImage 并逐像素设置 RGB 值。
            rgb = np.stack(
                [normalize_to_uint8(bands[i]) for i in range(3)], axis=-1
            )
            image = Image.fromarray(rgb, mode="RGB")
        else:
            # 单波段影像没有天然 RGB 组合，使用灰度图保留亮度信息。
            # mode="L" 表示 8 位灰度图（Luminance）。
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
    """构造标准 JSON 响应格式。

    所有脚本输出都遵循 { success, data, error } 格式，
    便于 Java 端通过 ObjectMapper 统一反序列化为通用 Result 类。

    Args:
        success: 是否成功。
        data: 成功时的数据。
        error: 失败时的错误描述。

    Returns:
        格式化的字典。
    """
    return {
        "success": success,
        "data": data,
        "error": error,
    }


def main():
    """CLI 入口：解析命令行参数并调用 generate_thumbnail。

    用法：python generate_thumbnail.py <input> <output> [--max-size N]

    Python 的 argparse 等价于 Java 中 Apache Commons CLI 或 picocli，
    可自动生成 --help 帮助信息和参数校验。

    Returns:
        int: 成功返回 0，失败返回 1（与 Unix 惯例一致，Java 端通过 Process.exitValue() 获取）。
    """
    parser = argparse.ArgumentParser(
        description="生成 GeoTIFF PNG 缩略图。"
    )
    parser.add_argument("input", help="本地 GeoTIFF 文件路径。")
    parser.add_argument("output", help="输出 PNG 文件路径。")
    parser.add_argument(
        "--max-size",
        type=int,
        default=512,
        help="缩略图最大宽度或高度。",
    )
    args = parser.parse_args()

    try:
        data = generate_thumbnail(args.input, args.output, args.max_size)
        # ensure_ascii=False 确保中文字符不被转义为 \uXXXX 形式。
        print(json.dumps(response(True, data=data), ensure_ascii=False))
        return 0
    except Exception as exc:
        print(
            json.dumps(
                response(False, error=f"缩略图生成失败：{exc}"),
                ensure_ascii=False,
            )
        )
        return 1


if __name__ == "__main__":
    # sys.exit(main()) 以 main() 的返回值作为进程退出码。
    # Java 端通过 Process.waitFor() 获取退出码判断执行结果。
    sys.exit(main())
