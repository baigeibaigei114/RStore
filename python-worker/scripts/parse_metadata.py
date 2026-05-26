"""
GeoTIFF 元数据解析脚本 -- 供 Java 后端通过 ProcessBuilder 调用的命令行工具。

职责：
  - 解析 GeoTIFF 文件的宽、高、波段数、CRS、仿射变换、分辨率等信息。
  - 将原始边界（原始 CRS）和 WGS84 边界（EPSG:4326）都输出到 JSON。
  - 估算以米为单位的空间分辨率（支持投影和地理坐标系）。
  - 以统一 JSON 格式 { success, data, error } 输出到 stdout。

设计要点：
  - 作为独立脚本被 Java 后端调用（通过 ProcessBuilder 或 Runtime.exec），
    通过 stdout 输出 JSON 结果，通过 exit code（0/1）表示成功/失败。
  - 之所以不通过 RabbitMQ 消息处理：元数据解析是同步的、轻量的，
    适合在 Java 进程中直接启子进程获取结果，避免异步编排复杂度。
  - 使用 argparse 解析 CLI 参数，提供标准的 --help 帮助信息。
"""

import argparse
import json
import sys
from math import cos, radians
from pathlib import Path

import rasterio
from rasterio.warp import transform_bounds


def transform_to_list(transform):
    """将 rasterio 仿射变换对象转为列表（6 参数），便于 JSON 序列化。

    rasterio 的 Affine 对象有 a/b/c/d/e/f 六个属性，对应 GDAL 中
    GeoTransform 的 [GT(0), GT(1), GT(2), GT(3), GT(4), GT(5)]。

    Args:
        transform: rasterio.transform.Affine 对象。

    Returns:
        [a, b, c, d, e, f] 浮点数列表。
    """
    return [
        transform.a,
        transform.b,
        transform.c,
        transform.d,
        transform.e,
        transform.f,
    ]


def bounds_to_dict(bounds):
    """将 rasterio BoundingBox 对象转为字典。

    Args:
        bounds: rasterio.coords.BoundingBox 对象（具有 left/bottom/right/top 属性）。

    Returns:
        包含 left/bottom/right/top 的字典。
    """
    return {
        "left": bounds.left,
        "bottom": bounds.bottom,
        "right": bounds.right,
        "top": bounds.top,
    }


def bounds_tuple_to_dict(bounds):
    """将 (left, bottom, right, top) 四元组转为字典。

    Args:
        bounds: (left, bottom, right, top) 浮点数元组。

    Returns:
        包含 left/bottom/right/top 的字典。
    """
    left, bottom, right, top = bounds
    return {
        "left": left,
        "bottom": bottom,
        "right": right,
        "top": top,
    }


def center_lat(bounds):
    """计算边界框的中心纬度（用于地理坐标系的米分辨率估算）。

    经度 1 度对应的地面距离随纬度变化（纬度越高距离越短），
    所以估算米分辨率时需要知道中心纬度。

    Args:
        bounds: (left, bottom, right, top) 四元组。

    Returns:
        中心纬度值（度），bounds 为空时返回 0.0。
    """
    if not bounds:
        return 0.0
    return (bounds[1] + bounds[3]) / 2.0


def projected_unit_factor(crs):
    """获取投影坐标系的线性单位换算因子。

    不同的投影坐标系可能使用不同的线性单位（米/英尺/千米等），
    需要转换为统一的"米"单位输出。

    Args:
        crs: rasterio.crs.CRS 对象。

    Returns:
        浮点换算因子（如米制为 1.0，英尺制为 0.3048），无法获取时返回 None。
    """
    try:
        factor = crs.linear_units_factor
        # linear_units_factor 返回值类型不确定，可能是 (name, factor) 元组或单一数值。
        # 因此使用 isinstance 做类型检查，这是 Python 中常见的防御性编程写法。
        if isinstance(factor, (list, tuple)) and len(factor) >= 2:
            return float(factor[1])
        if isinstance(factor, (int, float)):
            return float(factor)
    except Exception:
        return None
    return None


def estimate_resolution_meter(dataset, wgs84_bounds):
    """估算 GeoTIFF 的空间分辨率（以米为单位）。

    处理两种坐标系场景：
      1. 投影坐标系（is_projected=True）：直接从 CRS 的线性单位换算。
         例如 UTM 坐标分辨率单位是米，直接返回。
      2. 地理坐标系（is_geographic=True）：需要根据纬度将度转换为米。
         经度方向使用 cos(lat) 修正，纬度方向直接使用常数。

    Args:
        dataset: rasterio 数据集对象。
        wgs84_bounds: WGS84 边界 (left, bottom, right, top)，用于地理坐标系的纬度计算。

    Returns:
        以米为单位的分辨率（取 X/Y 中的较大值），无法估算时返回 None。
    """
    if not dataset.crs:
        return None

    x_res = abs(dataset.res[0])
    y_res = abs(dataset.res[1])

    if dataset.crs.is_projected:
        # 投影坐标系：分辨率的线性单位即 CRS 定义的单位。
        factor = projected_unit_factor(dataset.crs) or 1.0
        # 取 X/Y 中较大值作为"代表性分辨率"，这是遥感领域的常见做法。
        return max(x_res, y_res) * factor

    if dataset.crs.is_geographic:
        # 地理坐标系（度）：需要根据纬度转换为米。
        # 地球近似为球体，不同纬度的经度跨度对应不同地面距离。
        lat = center_lat(wgs84_bounds)
        # 赤道附近 1 度经度约 111.32 千米，向两极递减。
        meters_per_degree_lon = 111320.0 * cos(radians(lat))
        # 1 度纬度约 110.57 千米，变化较小。
        meters_per_degree_lat = 110574.0
        x_meter = x_res * abs(meters_per_degree_lon)
        y_meter = y_res * meters_per_degree_lat
        return max(x_meter, y_meter)

    return None


def safe_description(value):
    """清洗单个波段描述，避免空字符串进入后端元数据。"""
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def band_descriptions(dataset):
    """读取 GeoTIFF 每个波段的描述信息，供后端推断 B02/B03/B04/B08 等波段角色。"""
    return [safe_description(value) for value in dataset.descriptions]


def color_interpretations(dataset):
    """读取 GeoTIFF 波段颜色解释，供后端识别 RGB 可见光波段。"""
    return [value.name.lower() for value in dataset.colorinterp]


def parse_metadata(file_path):
    """解析 GeoTIFF 文件的完整元数据。

    这是核心函数，被 Java 后端通过 CLI 调用。

    Args:
        file_path: GeoTIFF 文件路径字符串。

    Returns:
        包含 width、height、bandCount、crs、bounds、transform、
        resolution、resolutionMeter、nodata 等信息的字典。

    Raises:
        FileNotFoundError: 文件不存在。
        ValueError: 路径不是文件。
        rasterio.errors.RasterioIOError: 无法识别为有效 GeoTIFF。
    """
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"GeoTIFF 文件不存在：{path}")
    if not path.is_file():
        raise ValueError(f"输入路径不是文件：{path}")

    with rasterio.open(path) as dataset:
        raw_bounds = dataset.bounds

        # 将原始 CRS 的边界转换为 WGS84（EPSG:4326），供后端存储 footprint。
        # transform_bounds 使用 densify_pts 可减少投影坐标系转 WGS84 时边界曲线带来的包络误差。
        # densify_pts=21 在每条边界上插入 21 个采样点，使曲线转换更精确。
        # Python 的 *raw_bounds 是元组/序列解包语法（将 BoundingBox 的 left/bottom/right/top
        # 展开为四个独立参数传给函数），等价于 Java 中手动调用 bounds.left, bounds.bottom 等。
        wgs84_bounds = (
            transform_bounds(
                dataset.crs, "EPSG:4326", *raw_bounds, densify_pts=21
            )
            if dataset.crs
            else None
        )
        resolution_meter = estimate_resolution_meter(dataset, wgs84_bounds)

        return {
            "width": dataset.width,
            "height": dataset.height,
            "bandCount": dataset.count,
            "crs": dataset.crs.to_string() if dataset.crs else None,
            "originalBounds": bounds_to_dict(raw_bounds) if dataset.crs else None,
            # 后端 footprint 字段固定为 geometry(Polygon, 4326)，这里统一输出 WGS84 范围。
            "bounds": bounds_tuple_to_dict(wgs84_bounds)
            if wgs84_bounds
            else None,
            "boundsCrs": "EPSG:4326" if wgs84_bounds else None,
            "transform": transform_to_list(dataset.transform),
            "resolution": {
                "x": dataset.res[0],
                "y": dataset.res[1],
            },
            "resolutionUnit": (
                "degree"
                if dataset.crs and dataset.crs.is_geographic
                else (
                    dataset.crs.linear_units
                    if dataset.crs and dataset.crs.is_projected
                    else None
                )
            ),
            "resolutionMeter": resolution_meter,
            "nodata": dataset.nodata,
            "bandDescriptions": band_descriptions(dataset),
            "colorInterpretations": color_interpretations(dataset),
        }


def response(success, data=None, error=None):
    """构造标准 JSON 响应格式。

    所有脚本输出都遵循 { success, data, error } 格式，
    便于 Java 端统一解析（ObjectMapper 反序列化为通用 Result 类）。

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
    """CLI 入口：解析命令行参数并调用 parse_metadata。

    用法：python parse_metadata.py <file_path>

    Returns:
        int: 成功返回 0，失败返回 1（与 Unix 惯例一致）。
    """
    # argparse 是 Python 标准库的命令行参数解析器，
    # 相当于 Java 中 Apache Commons CLI 或 picocli。
    parser = argparse.ArgumentParser(
        description="使用 rasterio 解析 GeoTIFF 元数据。"
    )
    parser.add_argument("file", help="本地 GeoTIFF 文件路径。")
    args = parser.parse_args()

    try:
        # json.dumps 将 Python dict 序列化为 JSON 字符串。
        # ensure_ascii=False 确保中文字符不被转义为 \uXXXX，
        # 这对应 Java 中 ObjectMapper.writeValueAsString() + 不设置 NON_ASCII_ESCAPE。
        print(
            json.dumps(
                response(True, data=parse_metadata(args.file)),
                ensure_ascii=False,
            )
        )
        return 0
    except Exception as exc:
        print(
            json.dumps(
                response(False, error=f"GeoTIFF 元数据解析失败：{exc}"),
                ensure_ascii=False,
            )
        )
        return 1


if __name__ == "__main__":
    # sys.exit(main()) 将 main() 的返回值作为进程退出码，
    # Java 端通过 Process.waitFor() 获取该退出码。
    # 这也是为什么 main() 返回 int 而非打印后 sys.exit(0) 的原因。
    sys.exit(main())
