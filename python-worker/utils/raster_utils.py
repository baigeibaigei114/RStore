"""
栅格数据处理工具模块 -- 提供遥感影像读、写、计算的底层工具函数。

职责：
  - 从 rasterio 数据集读取指定波段的数据（转为 float32）。
  - 计算归一化差值（如 NDVI、NDWI 通用公式）。
  - 将计算结果写入单波段 GeoTIFF（继承输入影像的空间参考）。
  - 将二值掩膜写入 uint8 GeoTIFF（用于变化检测结果）。
  - 生成金字塔（overviews）优化 GeoServer 渲染性能。

设计要点：
  - 所有函数均为纯函数（不维护状态），便于组合和测试。
  - 输出 GeoTIFF 从参考影像复制 profile（CRS、transform 等），保证空间一致性。
  - 使用 DEFLATE 压缩减少存储开销（无损压缩，GeoTIFF 标准支持）。
"""

from pathlib import Path

import numpy as np
import rasterio
from rasterio.enums import Resampling


def read_band(dataset: rasterio.DatasetReader, band_index: int) -> np.ndarray:
    """从 rasterio 数据集中读取指定波段，转换为 float32 类型。

    Args:
        dataset: 已打开的 rasterio 数据集（通过 rasterio.open 获得）。
        band_index: 波段编号（rasterio 的波段从 1 开始计数，与 GeoTIFF 规范一致）。

    Returns:
        二维 NumPy 数组，dtype=float32，形状为 (height, width)。

    Raises:
        ValueError: band_index 超出 [1, dataset.count] 范围。

    之所以转换为 float32：
      - 归一化差值计算会输出浮点数（而非原始整型 DN 值）。
      - float32 在精度和存储之间取得平衡，适用于遥感分析。
      - 与 Java + GDAL 中 rasterio 调用 dataset.GetRasterBand(i).ReadAsArray().astype(float) 等价。
    """
    if band_index < 1 or band_index > dataset.count:
        raise ValueError(
            f"波段编号 {band_index} 超出范围，当前影像共有 {dataset.count} 个波段"
        )
    # dataset.read(band_index) 返回 NumPy ndarray（Python 中最核心的多维数组类型）。
    # NumPy 相当于 Java 中的 ND4J 或 Apache Commons Math 的 RealMatrix，
    # 但 NumPy 是 Python 数据科学生态的事实标准，提供向量化运算和广播机制。
    return dataset.read(band_index).astype("float32")


def normalized_difference(
    first: np.ndarray, second: np.ndarray, epsilon: float = 1e-6
) -> np.ndarray:
    """计算归一化差值：(first - second) / (first + second + epsilon)。

    这是 NDVI、NDWI、NDBI 等遥感指数的通用计算公式，
    区别仅在于使用的波段组合不同。

    Args:
        first: 被减数波段数据（如 NDVI 中的 NIR 波段）。
        second: 减数波段数据（如 NDVI 中的 RED 波段）。
        epsilon: 避免除零的小量，默认 1e-6。分母加上 epsilon 后，
                 不会对计算结果（通常值域 [-1, 1]）产生实际影响。

    Returns:
        归一化差值数组，dtype=float32，形状与输入一致。

    注意：NumPy 的向量化运算等价于 Java 中双重 for 循环逐像元计算，
    但 C 语言实现让性能接近原生代码。这是 Python 在遥感领域胜过 Java 的关键原因之一。
    """
    denominator = first + second + epsilon
    return ((first - second) / denominator).astype("float32")


def write_single_band_geotiff(
    reference: rasterio.DatasetReader,
    output_path: Path,
    data: np.ndarray,
    nodata: float = -9999.0,
) -> None:
    """将单波段浮点数据写入 GeoTIFF 文件。

    从参考影像复制空间参考信息（CRS、transform、尺寸），
    确保输出文件具有完整的地理定位能力。

    Args:
        reference: 参考数据集，用于继承 profile（空间参考信息）。
        output_path: 输出 GeoTIFF 文件路径。
        data: 待写入的浮点数组，形状必须与 reference 的 (height, width) 一致。
        nodata: NoData 值，默认 -9999.0。
                  -9999.0 可以避免与有效值重叠（NDVI 值域 [-1, 1]）。
    """
    # 复用参考影像 profile，确保输出结果继承 CRS、transform、分辨率等空间定位信息。
    # profile 是一个 dict，包含 driver、width、height、count、crs、transform 等键。
    # 相当于 Java 中从参考影像读取 CreateCopy() 参数。
    profile = reference.profile.copy()
    # Python 的 dict.update(key=value) 方式更新字典，等价于 profile["key"] = value。
    # Java 中没有完全等价的语法，通常使用 map.put(key, value) 逐个设置。
    profile.update(
        driver="GTiff",  # 输出格式为 GeoTIFF
        count=1,  # 单波段输出
        dtype="float32",  # 浮点像素类型
        nodata=nodata,
        compress="deflate",  # DEFLATE 无损压缩，GeoTIFF 标准兼容
    )

    # 处理非有限值：np.isfinite 同时检测 NaN、Inf、-Inf，
    # 将所有这些无效值替换为 nodata，确保不污染像素值范围。
    safe_data = np.where(np.isfinite(data), data, nodata).astype("float32")
    # **profile 是 Python 的字典解包语法（PEP 448），
    # 将 profile dict 的所有键值对展开为关键字参数传给 rasterio.open。
    # 等价于 rasterio.open(path, "w", driver=..., count=..., ...)。
    # Java 中没有等价的语法，通常需要 Builder 模式或逐个 setter 调用。
    with rasterio.open(output_path, "w", **profile) as dst:
        dst.write(safe_data, 1)
        build_overviews(dst, Resampling.average)


def write_mask_geotiff(
    reference: rasterio.DatasetReader, output_path: Path, mask: np.ndarray
) -> None:
    """将二值掩膜数据写入 GeoTIFF 文件。

    与 write_single_band_geotiff 的区别：
      - 使用 uint8 类型（而非 float32），文件体积显著更小。
      - 使用 nearest 重采样（而非 average），保持二值边界清晰。
      - nodata=0，因为 0 表示"未变化"，与"无效数据"在语义上一致。

    Args:
        reference: 参考数据集。
        output_path: 输出 GeoTIFF 路径。
        mask: 二值掩膜数组（0 和 1），形状与 reference 一致。
    """
    # 掩膜只表达是否变化，使用 uint8 可以显著减小结果文件体积。
    # 一个 uint8 像元只占 1 字节，而 float32 占 4 字节。
    profile = reference.profile.copy()
    profile.update(
        driver="GTiff",
        count=1,
        dtype="uint8",
        nodata=0,
        compress="deflate",
    )

    with rasterio.open(output_path, "w", **profile) as dst:
        dst.write(mask.astype("uint8"), 1)
        # 二值掩膜使用 nearest 重采样，避免 average 在边界处产生中间灰度值。
        build_overviews(dst, Resampling.nearest)


def build_overviews(
    dataset: rasterio.io.DatasetWriter, resampling: Resampling
) -> None:
    """为 GeoTIFF 构建金字塔（overviews），优化 GeoServer 渲染性能。

    金字塔是多分辨率影像的预览副本：大比例尺（缩小）浏览时，
    GeoServer 读取低分辨率金字塔而非原始全分辨率数据，
    大幅减少 I/O 和重采样计算量。

    Args:
        dataset: 已打开的 GeoTIFF DatasetWriter（写入模式）。
        resampling: 金字塔构建时的重采样策略。
                    连续数据（如 NDVI）使用 average，
                    离散数据（如变化掩膜）使用 nearest。

    金字塔层级：2x, 4x, 8x, 16x 缩减。当影像尺寸小于对应层级时自动跳过。
    例如 1000x800 的影像只会构建 2x(500x400) 和 4x(250x200) 两级。
    """
    # overviews 让 GeoServer 在小比例尺浏览时读取低分辨率金字塔，减少动态重采样压力。
    # 列表推导式（list comprehension）是 Python 的标志性语法（PEP 202）：
    # [factor for factor in factors if condition]
    # 等价于 Java 中的 stream().filter().collect(Collectors.toList())：
    #   factors.stream().filter(f -> width // f >= 1 && height // f >= 1).collect(toList())
    # 但列表推导式更简洁直观。
    factors = [2, 4, 8, 16]
    usable_factors = [
        factor
        for factor in factors
        if dataset.width // factor >= 1 and dataset.height // factor >= 1
    ]
    if not usable_factors:
        return
    dataset.build_overviews(usable_factors, resampling)
    # 在 GeoTIFF 标签中记录重采样方式，便于 GeoServer 等下游工具理解 pyramid 属性。
    dataset.update_tags(ns="rio_overview", resampling=resampling.name)
