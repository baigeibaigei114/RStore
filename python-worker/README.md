# Python Worker

该目录用于放置遥感影像处理节点脚本和 RabbitMQ 消费进程。当前包含两类能力：

- 独立脚本：解析 GeoTIFF 元数据、生成缩略图。
- Worker 框架：消费 `rs.task.queue`，从 MinIO 下载影像，执行 NDVI/NDWI 等处理，再上传结果并回调 Spring Boot。

## 安装依赖

```powershell
cd python-worker
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
```

## 独立脚本运行示例

```powershell
python scripts\parse_metadata.py D:\data\sample.tif
```

生成缩略图：

```powershell
python scripts\generate_thumbnail.py D:\data\sample.tif D:\data\sample_thumb.png
```

成功输出示例：

```json
{
  "success": true,
  "data": {
    "width": 1024,
    "height": 1024,
    "bandCount": 4,
    "crs": "EPSG:4326",
    "bounds": {
      "left": 116.1,
      "bottom": 39.8,
      "right": 116.5,
      "top": 40.1
    },
    "transform": [0.000390625, 0.0, 116.1, 0.0, -0.00029296875, 40.1],
    "resolution": {
      "x": 0.000390625,
      "y": 0.00029296875
    },
    "nodata": null
  }
}
```

解析失败时会输出：

```json
{
  "success": false,
  "error": "Failed to parse GeoTIFF metadata: ..."
}
```

## RabbitMQ Worker 启动

先启动本地依赖：

```powershell
docker compose up -d
```

进入 `python-worker` 目录后启动：

```powershell
python main.py
```

可用环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ 地址 |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP 端口 |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ 用户名 |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ 密码 |
| `RABBITMQ_TASK_QUEUE` | `rs.task.queue` | 遥感任务主队列 |
| `RABBITMQ_PREFETCH_COUNT` | `1` | 单个 Worker 同时处理的消息数 |
| `RABBITMQ_REQUEUE_ON_ERROR` | `true` | 处理失败时是否重新入队 |
| `MINIO_ENDPOINT` | `localhost:9000` | MinIO endpoint，不带 `http://` |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO access key |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO secret key |
| `MINIO_SECURE` | `false` | 是否使用 HTTPS |
| `CALLBACK_BASE_URL` | `http://localhost:8080` | Spring Boot 服务地址 |
| `CALLBACK_ENABLED` | `true` | 是否启用任务状态回调 |
| `WORKER_TEMP_DIR` | `tmp` | Worker 临时文件目录 |

PowerShell 示例：

```powershell
$env:RABBITMQ_HOST="localhost"
$env:MINIO_ENDPOINT="localhost:9000"
$env:CALLBACK_BASE_URL="http://localhost:8080"
python main.py
```

当前处理器状态：

| 任务类型 | 状态 |
| --- | --- |
| `NDVI` | 已实现基础计算流程，默认 `redBand=3`、`nirBand=4`，公式为 `(NIR - RED) / (NIR + RED + 1e-6)` |
| `NDWI` | 已实现基础计算流程，默认 `greenBand=2`、`nirBand=4`，公式为 `(GREEN - NIR) / (GREEN + NIR + 1e-6)` |
| `CHANGE_DETECTION` | 已实现简化差值检测，默认 `band=1`、`threshold=0.2` |

NDVI 处理流程：

1. 从 RabbitMQ 收到任务消息。
2. 根据 `inputBucket` 和 `inputObjectKey` 从 MinIO 下载原始 GeoTIFF。
3. 使用 rasterio 读取 `redBand` 和 `nirBand`。
4. 输出单波段 GeoTIFF，并保留原始影像的 CRS、transform、width、height 等空间信息。
5. 将结果上传到 `outputBucket` 和 `outputObjectKey`。
6. 成功时回调任务状态为 `SUCCESS`，失败时回调 `FAILED` 并返回错误信息。
7. 任务结束后清理本地临时目录。

CHANGE_DETECTION 参数示例：

```json
{
  "taskId": 1002,
  "taskType": "CHANGE_DETECTION",
  "inputBucket": "remote-sensing-images",
  "outputBucket": "remote-sensing-images",
  "outputObjectKey": "result/CHANGE_DETECTION/2026/05/task_1002.tif",
  "params": {
    "beforeObjectKey": "raw/2026/05/before.tif",
    "afterObjectKey": "raw/2026/05/after.tif",
    "band": 1,
    "threshold": 0.2
  }
}
```

简化变化检测流程：

1. 从 MinIO 下载 `beforeObjectKey` 和 `afterObjectKey`。
2. 校验两期影像的 width、height、crs、transform 是否一致。
3. 读取指定 `band`，计算 `diff = abs(after - before)`。
4. 根据 `threshold` 输出 0/1 单波段变化掩膜 GeoTIFF。
5. 结果使用 after 影像的 CRS 和 transform，并上传到 `outputBucket/outputObjectKey`。
