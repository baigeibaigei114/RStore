"""
变化检测处理器模块 -- 基于两期遥感影像的像元级差值计算生成变化掩膜。

职责：
  - 下载前后两期 GeoTIFF 影像到本地临时目录。
  - 验证两期影像的空间一致性（尺寸、坐标系、仿射变换）。
  - 逐像元计算差值，根据阈值生成二值变化掩膜（0=未变化, 1=变化）。
  - 将变化掩膜上传回 MinIO。

设计要点：
  - 变化检测采用最简方案：直接计算同一波段的前后差值，超过阈值标记为变化。
    相比于更复杂的分类后比较法（CVA），本方案计算量小、适合大范围快速筛查。
  - 掩膜以 after 影像的空间参考（CRS、transform、尺寸）为准，
    因为最终用户关注的是"变化后的位置"。
  - 强制要求前后影像的 width/height/CRS/transform 一致，避免误匹配。
"""

from pathlib import Path
import shutil
from uuid import uuid4

import numpy as np
import rasterio

from clients.minio_client import MinioStorageClient
from utils.raster_utils import read_band, write_mask_geotiff


class ChangeDetectionProcessor:
    """基于像元差值的简单变化检测处理器。

    计算策略：
      1. 读取前后两期影像的指定波段
      2. diff = abs(after - before)
      3. mask = diff > threshold ? 1 : 0
      4. 1 = 变化像元，0 = 未变化像元

    适用场景：
      - 土地利用/覆盖变化快速筛查
      - 同一传感器、同一区域的多时相对比
      - 预警类应用（如违建检测、水体扩张监测）

    局限性：
      - 不考虑不同时相间的辐射校正，直接使用原始 DN 值或反射率
      - 对配准误差敏感，亚像元偏移可能产生"椒盐"噪声
      - 单一阈值无法适应不同地类的变化幅度差异
    """

    DEFAULT_BAND = 1
    DEFAULT_THRESHOLD = 0.2

    def __init__(self, storage_client: MinioStorageClient, temp_dir: Path):
        """初始化变化检测处理器。

        Args:
            storage_client: MinIO 存储客户端。
            temp_dir: 临时文件根目录。
        """
        self._storage_client = storage_client
        self._temp_dir = temp_dir

    def process(self, message: dict) -> dict:
        """执行变化检测流程。

        Args:
            message: 任务消息字典，必须包含：
                - outputBucket / outputObjectKey: 输出位置
                - inputBucket: 输入文件所在 bucket（作为 beforeBucket/afterBucket 的默认值）
                - params.beforeObjectKey: 前一期影像的对象路径
                - params.afterObjectKey: 后一期影像的对象路径
              可选字段：
                - params.beforeBucket / params.afterBucket: 前后影像各自的 bucket（覆盖 inputBucket）
                - params.band: 参与差值计算的波段编号，默认 1
                - params.threshold: 变化判定阈值，默认 0.2

        Returns:
            结果字典，包含 bucket、objectKey 及使用的参数（beforeObjectKey、afterObjectKey、band、threshold）。

        Raises:
            ValueError: 缺少必要参数或前后影像空间参考不一致时抛出。
        """
        # 幂等性检查：如果结果对象已存在，跳过计算直接返回。
        if self._storage_client.object_exists(
            message["outputBucket"], message["outputObjectKey"]
        ):
            return {
                "outputBucket": message["outputBucket"],
                "outputObjectKey": message["outputObjectKey"],
                "skippedReason": "结果对象已存在",
            }

        params = message.get("params") or {}

        # 提取前后影像的对象键 -- 这两个是必填参数。
        before_object_key = params.get("beforeObjectKey")
        after_object_key = params.get("afterObjectKey")
        if not before_object_key or not after_object_key:
            raise ValueError(
                "CHANGE_DETECTION 需要 params.beforeObjectKey 和 params.afterObjectKey"
            )

        band = int(params.get("band", self.DEFAULT_BAND))
        threshold = float(params.get("threshold", self.DEFAULT_THRESHOLD))

        # 确定前后影像所在的 bucket：优先使用各自指定的 bucket，否则使用 inputBucket。
        # 这种设计支持前后影像在不同 bucket 的场景（如不同数据源）。
        input_bucket = message.get("inputBucket")
        before_bucket = params.get("beforeBucket") or input_bucket
        after_bucket = params.get("afterBucket") or input_bucket
        if not before_bucket or not after_bucket:
            raise ValueError(
                "CHANGE_DETECTION 需要 inputBucket 或 params.beforeBucket/params.afterBucket"
            )

        # 创建隔离的工作目录，uuid 防止并发冲突。
        work_dir = (
            self._temp_dir / f"change_{message['taskId']}_{uuid4().hex}"
        )
        work_dir.mkdir(parents=True, exist_ok=True)
        before_path = work_dir / "before.tif"
        after_path = work_dir / "after.tif"
        output_path = work_dir / "change_mask.tif"

        try:
            # 下载前后两期影像到本地。
            self._storage_client.download_file(
                before_bucket, before_object_key, before_path
            )
            self._storage_client.download_file(
                after_bucket, after_object_key, after_path
            )

            # Python 的 with 语句支持同时打开多个上下文管理器（用逗号分隔），
            # 等价于 Java 中嵌套的 try-with-resources：
            #   try (Dataset before = open(path1); Dataset after = open(path2)) { ... }
            # 多个资源会按声明顺序进入、逆序退出。
            with rasterio.open(before_path) as before_dataset, rasterio.open(
                after_path
            ) as after_dataset:
                self._validate_compatible(before_dataset, after_dataset)
                before = read_band(before_dataset, band)
                after = read_band(after_dataset, band)

                # 计算绝对差值，然后应用阈值生成二值掩膜。
                # NumPy 的向量化运算替代了 Java 中双重 for 循环遍历像元，
                # 在 Python 中利用底层 C 实现获得接近原生性能。
                diff = np.abs(after - before)
                # np.where(condition, x, y) 等价于 Java 中的三元运算符数组逻辑：
                # condition 为 True 取 x，为 False 取 y。
                mask = np.where(diff > threshold, 1, 0).astype("uint8")

                # 变化结果以 after 影像作为空间参考，便于表达变化后的空间位置。
                # 因为用户通常更关心"后一时相发生了什么变化"。
                write_mask_geotiff(after_dataset, output_path, mask)

            # 上传变化掩膜到 MinIO。
            self._storage_client.upload_file(
                message["outputBucket"],
                message["outputObjectKey"],
                output_path,
            )
            return {
                "outputBucket": message["outputBucket"],
                "outputObjectKey": message["outputObjectKey"],
                "beforeObjectKey": before_object_key,
                "afterObjectKey": after_object_key,
                "band": band,
                "threshold": threshold,
            }
        finally:
            # 无论成功还是异常，保证临时文件被清理。
            shutil.rmtree(work_dir, ignore_errors=True)

    def _validate_compatible(
        self,
        before_dataset: rasterio.DatasetReader,
        after_dataset: rasterio.DatasetReader,
    ) -> None:
        """验证前后两期影像在空间参考上是否兼容。

        像元级差值计算要求两期影像严格对齐，否则结果无意义。
        本方法检查三个核心属性：

        1. width / height: 像元矩阵维度（行列数）必须一致。
        2. crs: 坐标参考系必须一致。
        3. transform: 仿射变换矩阵必须一致（对应像元在地理空间中对齐）。

        Args:
            before_dataset: 前一期影像的 rasterio 数据集。
            after_dataset: 后一期影像的 rasterio 数据集。

        Raises:
            ValueError: 任一兼容性检查失败时抛出，描述具体不兼容项。
        """
        if (
            before_dataset.width != after_dataset.width
            or before_dataset.height != after_dataset.height
        ):
            # f-string 支持嵌入复杂表达式，如 f"before={dataset.width}x{dataset.height}"，
            # 这在 Java 中需要 String.format("before=%dx%d", w, h) 来实现。
            raise ValueError(
                "前后两期 GeoTIFF 尺寸不一致："
                f"before={before_dataset.width}x{before_dataset.height}, "
                f"after={after_dataset.width}x{after_dataset.height}"
            )
        if before_dataset.crs != after_dataset.crs:
            raise ValueError(
                f"前后两期 GeoTIFF 坐标系不一致：before={before_dataset.crs}, after={after_dataset.crs}"
            )
        if before_dataset.transform != after_dataset.transform:
            raise ValueError("前后两期 GeoTIFF 仿射变换不一致")
