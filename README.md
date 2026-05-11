# 遥感影像智能解译与时空资产管理平台

## 项目背景

遥感影像在自然资源调查、城市规划、生态监测、灾害评估、农业生产和基础设施巡检等场景中具有重要价值。随着卫星、无人机和航空遥感数据持续增长，传统依赖人工下载、整理、查看和判读影像的方式，已经难以满足高频、多源、大规模的业务需求。

本项目面向“遥感影像管理与智能解译”场景，计划建设一个集影像资产管理、空间检索、时序管理、智能解译任务调度、解译结果发布与可视化服务于一体的后端平台。平台重点关注遥感影像从入库、存储、处理、检索到服务发布的完整流程，为后续接入深度学习模型、GIS 可视化前端和业务分析应用提供稳定基础。

## 技术栈

| 类型 | 技术 | 用途 |
| --- | --- | --- |
| 后端框架 | Spring Boot 3 | 构建 REST API 和后端业务服务 |
| 持久层框架 | MyBatis | 编写可控 SQL，便于扩展 PostGIS 空间查询 |
| 构建工具 | Maven | 项目依赖管理与构建 |
| 开发语言 | Java 17 | 后端主语言 |
| 空间数据库 | PostgreSQL + PostGIS | 存储影像元数据、空间范围、时空索引 |
| 消息队列 | RabbitMQ | 解译任务、影像处理任务异步调度 |
| 缓存 | Redis | 缓存热点数据、任务状态、临时结果 |
| 对象存储 | MinIO | 存储原始遥感影像、切片、模型输出文件 |
| 地图服务 | GeoServer | 发布 WMS/WFS/WMTS 等地理空间服务 |
| 检索引擎 | Elasticsearch | 影像资产、标签、解译结果的全文检索 |
| 影像处理 | Python GDAL/Rasterio | 影像格式转换、裁剪、重投影、栅格分析 |
| 容器化 | Docker Compose | 本地开发环境一键启动 |

## 系统模块划分

当前项目采用清晰的后端分层结构，后续业务模块将在此基础上逐步扩展。

```text
src/main/java/com/remotesensing/platform
├── config          # 配置类，如跨域、消息队列、对象存储、搜索服务配置
├── controller      # REST 接口层，负责接收请求和返回响应
├── service         # 业务接口层
├── service/impl    # 业务实现层
├── entity          # 数据库实体对象
├── dto             # 请求参数和服务间数据传输对象
├── vo              # 返回给前端的视图对象
├── mapper          # MyBatis 数据访问接口，对应 resources/mapper 中的 XML SQL
├── common          # 通用工具和统一返回结果
└── exception       # 全局异常处理和业务异常
```

计划中的核心业务模块：

| 模块 | 说明 |
| --- | --- |
| 用户与权限模块 | 用户登录、角色权限、接口访问控制 |
| 影像资产管理模块 | 影像上传、元数据登记、影像文件管理、资产目录维护 |
| 空间检索模块 | 基于行政区、经纬度范围、时间范围和关键字检索影像 |
| 智能解译任务模块 | 创建解译任务、异步调度模型推理、跟踪任务状态 |
| 解译结果管理模块 | 管理目标检测、地物分类、变化检测等模型输出结果 |
| 地图服务发布模块 | 对接 GeoServer，发布影像图层和解译结果图层 |
| 文件与对象存储模块 | 对接 MinIO，统一管理影像、切片和结果文件 |
| 系统监控模块 | 查看服务健康状态、任务执行状态和关键运行指标 |

## 本地开发环境启动

### 1. 启动基础依赖服务

项目根目录下执行：

```powershell
docker compose up -d
```

停止服务：

```powershell
docker compose down
```

如需删除本地持久化数据卷：

```powershell
docker compose down -v
```

### 2. 启动 Spring Boot 后端

确认已安装 JDK 17 和 Maven，然后执行：

```powershell
mvn spring-boot:run
```

当前 Python 脚本路径使用项目内相对路径配置，请在项目根目录启动后端；后续 Docker 化时建议改为绝对路径配置或独立 Python Worker 容器。

后端默认访问地址：

```text
http://localhost:8080/api
```

### 3. 运行测试

```powershell
mvn test
```

## Docker Compose 服务地址

| 服务 | 地址/端口 | 默认账号 | 默认密码 | 说明 |
| --- | --- | --- | --- | --- |
| PostgreSQL/PostGIS | `localhost:5432` | `postgres` | `postgres` | 数据库名：`rs_image_asset` |
| RabbitMQ | `localhost:5672` | `guest` | `guest` | AMQP 连接端口 |
| RabbitMQ 管理控制台 | `http://localhost:15672` | `guest` | `guest` | 队列、交换机和连接管理 |
| MinIO API | `http://localhost:9000` | `minioadmin` | `minioadmin` | 对象存储 API |
| MinIO 控制台 | `http://localhost:9001` | `minioadmin` | `minioadmin` | 对象存储管理页面 |
| Redis | `localhost:6379` | 无 | 无 | 本地缓存服务 |
| GeoServer | `http://localhost:8081/geoserver` | `admin` | `geoserver` | 地理空间服务发布平台 |

PostgreSQL 使用 `postgis/postgis` 镜像，并在首次初始化数据库时执行：

```text
docker/postgres/init/01-enable-postgis.sql
```

该脚本会自动启用 `postgis` 和 `postgis_topology` 扩展。

如果数据库已经创建过旧版 `rs_image` 表，执行以下升级脚本以支持 GeoTIFF 先上传、后解析空间范围：

```powershell
psql -U postgres -d rs_image_asset -f src/main/resources/db/upgrade/20260508_rs_image_upload.sql
```

## GeoTIFF 上传接口

接口地址：

```text
POST http://localhost:8080/api/images/upload
```

请求类型：`multipart/form-data`

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| file | File | 是 | 仅支持 `.tif` 或 `.tiff` 文件 |
| name | Text | 是 | 影像名称 |
| sensor | Text | 否 | 传感器名称 |
| captureTime | Text | 否 | 拍摄时间，例如 `2026-05-08T10:30:00+08:00` |
| cloudPercent | Text | 否 | 云量百分比，例如 `12.5` |

Postman 测试方式：

1. 选择 `POST` 请求。
2. 输入地址：`http://localhost:8080/api/images/upload`。
3. 在 `Body` 中选择 `form-data`。
4. 添加 `file` 字段，类型选择 `File`，选择本地 `.tif` 或 `.tiff` 文件。
5. 添加 `name`、`sensor`、`captureTime`、`cloudPercent` 字段，类型选择 `Text`。
6. 点击 `Send`。

上传成功后，文件会保存到 MinIO，路径格式为：

```text
raw/{yyyy}/{MM}/{uuid}_{originalFilename}
```

数据库只保存 bucket、objectKey、fileSize、contentType 等元数据信息，不保存文件本体。

上传接口会先把 Multipart 文件保存为一份本地临时 GeoTIFF，后续元数据解析、MinIO 上传和缩略图生成都复用这同一份临时文件，避免大文件在一次请求中被反复复制。

上传接口会同步调用 `python-worker/scripts/parse_metadata.py` 解析 GeoTIFF 元数据，并写入：

| 元数据 | 保存位置 |
| --- | --- |
| width | `rs_image.width` |
| height | `rs_image.height` |
| bandCount | `rs_image.band_count` |
| crs | `rs_image.projection` |
| resolution.x | `rs_image.resolution_meter` |
| bounds | Python 已转换为 EPSG:4326，写入 `rs_image.footprint` |
| originalBounds | 保留在 `rs_image.metadata_json`，表示原始影像 CRS 下的范围 |
| 完整元数据 JSON | `rs_image.metadata_json` |

上传流程会先解析本地临时 GeoTIFF，通过后再上传 MinIO。这样伪造后缀或无法被 rasterio 打开的文件不会先进入对象存储。文件保存、Python 解析和 MinIO 上传都在数据库事务外执行；只有影像记录入库和缩略图路径回写使用短事务。如果数据库保存失败，后端会补偿删除已经上传的 `raw/` 对象；临时文件会在上传流程结束后自动清理。

当前阶段上传接口使用轻量并发限制，默认最多同时处理 2 个 GeoTIFF 上传请求。超过限制时接口会返回“当前上传任务较多，请稍后重试”，避免多个大文件同时触发磁盘 IO、MinIO 上传和 Python 进程堆积。可通过配置调整：

```yaml
upload:
  max-concurrent: 2
```

上传接口还会同步生成 PNG 缩略图：

1. Spring Boot 复用上传入口已经保存的本地临时 GeoTIFF。
2. 调用 `python-worker/scripts/generate_thumbnail.py`。
3. Python 使用 rasterio 读取影像，优先使用前三个波段生成 RGB 缩略图。
4. 如果只有单波段，则生成灰度缩略图。
5. 生成的 PNG 上传到 MinIO。
6. `rs_image.thumbnail_object_key` 保存缩略图对象路径。

缩略图 objectKey 格式：

```text
thumbnail/{yyyy}/{MM}/{imageId}.png
```

缩略图生成异常处理：

- GeoTIFF 读取失败：记录日志，影像主记录仍然保存成功。
- Python 脚本超时：记录日志，影像主记录仍然保存成功。
- Python 未生成 PNG：记录日志，影像主记录仍然保存成功。
- MinIO 上传失败：记录日志，影像主记录仍然保存成功。
- 成功或失败后都会清理本地临时 GeoTIFF 和 PNG 文件。

缩略图是展示增强能力，当前阶段不会因为缩略图失败回滚原始影像和元数据。列表页可根据 `thumbnailObjectKey` 是否为空展示默认占位图，后续可改为异步任务补生成。

## 影像删除策略

当前阶段采用“未完成任务禁止删除 + 历史任务下软删除影像 + 保留任务日志和结果文件”的策略：

1. 如果影像存在 `PENDING`、`RUNNING`、`RETRYING` 任务，删除接口会拒绝删除。
2. 如果只存在 `SUCCESS`、`FAILED`、`CANCELED` 历史任务，允许软删除影像。
3. 软删除只更新 `rs_image.status = DELETED`、`deleted_at`、`deleted_reason`，不会物理删除 `rs_task`、`rs_task_log`、`rs_result_file`。
4. 默认不删除 MinIO 中的原始影像、缩略图和结果文件，后续可由管理员确认或后台清理任务异步处理。
5. 影像列表、空间检索和行政区检索默认过滤 `deleted_at IS NULL`。
6. 删除 SQL 只允许 `READY` 或 `FAILED` 影像进入 `DELETED`，避免删除正在处理中的资产。

影像资产与处理任务的状态协作规则：

| 场景 | 影像状态 | 任务状态 | 说明 |
| --- | --- | --- | --- |
| 上传完成入库 | `READY` | 无 | 当前阶段上传完成后才创建影像记录，`UPLOADING/PARSING` 作为后续增强预留 |
| 提交任务 | `READY -> PROCESSING` | `PENDING` | 同一事务内先占用影像，再创建任务 |
| Worker 开始处理 | `PROCESSING` | `PENDING -> RUNNING` | 影像保持处理中 |
| Worker 重试 | `PROCESSING` | `RUNNING -> RETRYING` | 影像保持处理中 |
| 任务成功 | `PROCESSING -> READY` | `SUCCESS` | 若同一影像没有其他未完成任务，则恢复影像可用 |
| 任务失败 | `PROCESSING -> READY` | `FAILED` | 任务失败不代表原始影像损坏，原始资产仍恢复可用 |
| 任务取消 | `PROCESSING -> READY` | `CANCELED` | 若没有其他未完成任务，则恢复影像可用 |
| 删除影像 | `READY/FAILED -> DELETED` | 历史任务保留 | 不级联删除任务、日志、结果文件和 MinIO 对象 |

当前第一版只允许 `READY` 影像提交处理任务。如果后续需要支持同一影像并行多个处理任务，建议把“是否有活跃任务”设计为派生状态或单独计数字段，而不是继续扩展单值 `rs_image.status`。

已有数据库需要执行升级脚本：

```powershell
psql -U postgres -d rs_image_asset -f src/main/resources/db/upgrade/20260511_rs_image_soft_delete.sql
```

## 文件预签名 URL

MinIO bucket 默认私有，前端访问原始影像、缩略图或结果文件时，需要通过后端生成临时 URL。

接口地址：

```text
GET http://localhost:8080/api/files/presigned-url?objectKey=raw/2026/05/example.tif
```

返回示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "objectKey": "raw/2026/05/example.tif",
    "url": "http://localhost:9000/remote-sensing-images/raw/2026/05/example.tif?...",
    "expireSeconds": 1800
  }
}
```

默认过期时间为 30 分钟。接口只返回临时访问 URL，不会返回 MinIO 的 `accessKey` 或 `secretKey`。

为避免私有 bucket 中任意对象被猜路径访问，当前接口只允许 `raw/`、`thumbnail/`、`result/` 前缀，并会校验 objectKey 是否已登记在 `rs_image` 或 `rs_result_file` 中。

## 行政区范围查询

行政区表：

```sql
CREATE TABLE IF NOT EXISTS rs_admin_region (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    level VARCHAR(50) NOT NULL,
    parent_id BIGINT,
    geom geometry(MultiPolygon, 4326) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rs_admin_region_parent FOREIGN KEY (parent_id) REFERENCES rs_admin_region (id)
);

CREATE INDEX IF NOT EXISTS idx_rs_admin_region_geom_gist ON rs_admin_region USING GIST (geom);
```

已存在数据库可执行升级脚本：

```powershell
psql -U postgres -d rs_image_asset -f src/main/resources/db/upgrade/20260509_admin_region.sql
```

接口地址：

```text
GET http://localhost:8080/api/images/search-by-region?regionId=1
```

可叠加过滤条件：

```text
startTime=2026-05-01T00:00:00+08:00
endTime=2026-05-09T23:59:59+08:00
sensor=Sentinel-2
maxCloudPercent=20
pageNum=1
pageSize=10
```

示例：

```text
GET http://localhost:8080/api/images/search-by-region?regionId=1&sensor=Sentinel-2&maxCloudPercent=20&pageNum=1&pageSize=10
```

核心空间查询逻辑：

```sql
ST_Intersects(rs_image.footprint, rs_admin_region.geom)
```

## 遥感处理任务提交

任务提交接口：

```text
POST http://localhost:8080/api/tasks
```

请求示例：

```json
{
  "imageId": 1,
  "taskType": "NDVI",
  "params": {
    "redBand": 3,
    "nirBand": 4,
    "threshold": 0.3
  }
}
```

支持任务类型：

```text
NDVI
NDWI
CHANGE_DETECTION
```

提交流程：

1. 校验 `imageId` 对应影像是否存在。
2. 创建 `rs_task` 记录，初始状态为 `PENDING`。
3. 生成输出对象路径：

```text
result/{taskType}/{yyyy}/{MM}/task_{taskId}.tif
```

4. 发送 RabbitMQ 消息。
5. 返回 `taskId`。

RabbitMQ 配置：

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true

rabbitmq:
  remote-sensing-task:
    exchange: rs.task.exchange
    queue: rs.task.queue
    routing-key: rs.task.submit
    dead-letter-exchange: rs.task.dlx.exchange
    dead-letter-queue: rs.task.dlx.queue
    dead-letter-routing-key: rs.task.dlx
    max-retry-count: 3
```

发送给 worker 的消息示例：

```json
{
  "taskId": 1001,
  "taskType": "NDVI",
  "inputBucket": "remote-sensing-images",
  "inputObjectKey": "raw/2026/05/example.tif",
  "outputBucket": "remote-sensing-images",
  "outputObjectKey": "result/NDVI/2026/05/task_1001.tif",
  "params": {
    "redBand": 3,
    "nirBand": 4,
    "threshold": 0.3
  }
}
```

如果 RabbitMQ 本地发送异常、broker 返回 `nack`，或消息因 `mandatory` 未路由到队列，任务状态会更新为 `FAILED`，并写入 `rs_task_log`。当前阶段采用 publisher confirm/return 做最小可靠性增强，后续更稳妥的方案是增加 `rs_task_outbox` 和补偿投递线程。

任务队列失败重试和死信流转：

1. 后端提交任务消息到 `rs.task.exchange`。
2. 消息通过 `rs.task.submit` 路由到主队列 `rs.task.queue`。
3. 主队列声明了死信交换机 `rs.task.dlx.exchange` 和死信路由键 `rs.task.dlx`。
4. 主队列使用 quorum queue 的 `x-delivery-limit` 控制最大投递次数，当前默认 `3` 次。
5. Worker 消费失败并重新入队后，RabbitMQ 会累计投递次数。
6. 超过最大投递次数后，消息进入 `rs.task.dlx.exchange`。
7. 死信交换机将消息路由到 `rs.task.dlx.queue`。
8. 后端监听死信队列，将任务状态更新为 `FAILED`，并把 `x-death`、headers、routingKey 等信息记录到 `rs_task_log`，便于后续排查失败任务。

Python Worker 消费约定：

1. 收到消息后先回调后端或更新任务状态为 `RUNNING`。
2. 处理成功并上传结果文件后，再确认消息 `ack`，并将任务更新为 `SUCCESS`。
3. 可恢复异常使用 `nack/reject` 且 `requeue=true`，交给 RabbitMQ 按 `x-delivery-limit` 进入重试或死信。
4. 不可恢复异常应记录错误原因，并将任务更新为 `FAILED`；如果仍希望进入死信审计，可 `reject(requeue=false)`。
5. Worker 不应在处理失败但未更新任务状态时直接 `ack`，否则会出现消息已消失但任务仍卡住的问题。

Worker 状态回调接口：

```http
POST http://localhost:8080/api/tasks/{taskId}/status
```

请求体示例：

```json
{
  "status": "RUNNING",
  "progress": 30,
  "message": "已读取波段数据",
  "outputObjectKey": null,
  "errorMessage": null
}
```

状态流转规则：

| 当前状态 | 允许流转到 | 说明 |
| --- | --- | --- |
| `PENDING` | `RUNNING`、`RETRYING`、`FAILED`、`CANCELED` | 任务已入库，等待 Worker 消费或被取消 |
| `RUNNING` | `RUNNING`、`RETRYING`、`SUCCESS`、`FAILED`、`CANCELED` | `RUNNING -> RUNNING` 用于进度回调 |
| `RETRYING` | `RETRYING`、`RUNNING`、`FAILED`、`CANCELED` | 任务等待重新执行或再次进入运行 |
| `SUCCESS` | `SUCCESS` | 成功终态，不允许回退到运行中 |
| `FAILED` | `FAILED` | 失败终态，后续重投建议创建新任务或人工处理 |
| `CANCELED` | `CANCELED` | 取消终态，不允许继续执行 |

每次合法回调都会写入 `rs_task_log`。当状态为 `SUCCESS` 时，后端会保存 `outputObjectKey` 并写入 `finished_at`；当状态为 `FAILED` 时，后端会保存 `errorMessage` 并写入 `finished_at`。

注意：如果本地 RabbitMQ 已经创建过旧版 `rs.task.queue`，需要先删除旧队列，再让应用按新的 DLX/quorum 参数自动声明，否则 RabbitMQ 会因为队列参数不一致拒绝重声明。

已有数据库需要执行升级脚本：

```powershell
psql -U postgres -d rs_image_asset -f src/main/resources/db/upgrade/20260509_task_output_fields.sql
```

## 当前已完成

- 搭建 Maven + Spring Boot 3 后端项目基础结构。
- 使用 Java 17 作为项目开发版本。
- 建立 `config`、`controller`、`service`、`service.impl`、`entity`、`dto`、`vo`、`mapper`、`common`、`exception` 分层包结构。
- 添加统一接口返回结构 `Result<T>`。
- 添加统一响应码 `ResultCode`。
- 添加业务异常 `BusinessException`。
- 添加全局异常处理器 `GlobalExceptionHandler`。
- 添加基础 `application.yml` 配置结构。
- 添加 Docker Compose 本地开发环境，包含 PostgreSQL/PostGIS、RabbitMQ、MinIO、Redis、GeoServer。
- 添加 PostGIS 初始化脚本。

## 后续计划

- 接入 PostgreSQL/PostGIS，设计影像资产元数据表结构。
- 添加影像资产管理接口，包括新增、查询、分页、详情和删除。
- 对接 MinIO，实现遥感影像文件上传与下载。
- 添加 RabbitMQ 异步任务队列，用于影像处理和智能解译任务调度。
- 接入 Redis，缓存任务状态和高频查询结果。
- 对接 GeoServer，实现影像图层和解译结果图层发布。
- 接入 Elasticsearch，实现影像资产和解译结果检索。
- 预留 Python GDAL/Rasterio 处理服务调用能力。
- 添加接口文档、单元测试和基础权限控制。

## 项目定位

该项目适合作为遥感、GIS、空间数据管理、智能解译和后端工程能力的综合实践项目。它不仅关注普通 CRUD 接口开发，也覆盖空间数据库、对象存储、异步任务、地图服务发布和遥感影像处理等更贴近真实业务的工程场景。
