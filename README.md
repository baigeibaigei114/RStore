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

## AI 智能解译能力

当前 AI 能力只负责“解析”和“解释”，不会直接查询数据库、拼接 SQL 或自动执行任务。生产环境建议默认保持 `app.ai.enabled=false`，需要演示或联调时再通过环境变量显式开启，并配置 OpenAI-compatible 模型参数。

```yaml
app:
  ai:
    enabled: false
    provider: openai-compatible
    base-url: https://api.deepseek.com/v1
    api-key: ${DEEPSEEK_API_KEY:}
    model: deepseek-v4-flash
```

注意：任务报告生成会把任务类型、结果统计元数据、传感器和采集时间发送给第三方模型服务。第一版已避免发送 MinIO objectKey、bucket、用户 ID 和内部路径；公开部署前仍建议补充用户告知、脱敏策略和审计记录。

### 1. 使用 Docker Compose 启动完整环境

项目根目录下执行：

```powershell
docker compose up -d --build
```

该命令会启动 PostgreSQL/PostGIS、RabbitMQ、MinIO、Redis、GeoServer、Spring Boot 后端和 Python Worker。后端容器内已经安装 Python、GDAL、rasterio，并包含 `python-worker/scripts` 下的元数据解析和缩略图脚本，保证上传链路在容器内也能正常执行。

停止服务：

```powershell
docker compose down
```

如需删除本地持久化数据卷：

```powershell
docker compose down -v
```

### 2. 本地方式启动 Spring Boot 后端

如果希望在 IDE 或 Maven 中直接运行后端，可以只启动依赖服务：

```powershell
docker compose up -d postgres rabbitmq minio redis geoserver
```

确认已安装 JDK 17、Maven，以及 Python 3.11 和 `python-worker/requirements.txt` 中的依赖，然后执行：

```powershell
mvn spring-boot:run
```

本地方式仍使用项目内相对脚本路径，请在项目根目录启动后端。后续如果把元数据解析和缩略图也迁移到 Worker，Spring Boot 容器就可以进一步简化为纯 Java 镜像。

后端默认访问地址：

```text
http://localhost:8080/api
```

### 3. 运行测试

```powershell
mvn test
```

## GitHub Actions CI/CD

本项目已添加基础 CI 工作流：

```text
.github/workflows/ci.yml
```

触发方式：
- push 到任意分支时自动执行。
- 提交 Pull Request 时自动执行。
- 在 GitHub Actions 页面手动点击 `Run workflow` 执行。

当前 CI 包含三个 job：

| Job | 作用 |
| --- | --- |
| `backend-test` | 使用 JDK 17 执行 `mvn -B test`，验证 Spring Boot 后端编译和测试 |
| `python-worker-check` | 使用 Python 3.11 执行 `python -m compileall python-worker -q`，检查 Python Worker 语法 |
| `docker-compose-check` | 执行 `docker compose config`，检查本地开发环境编排文件是否有效 |

当前阶段先做 CI，不直接做 CD 自动部署。项目已经提供 Spring Boot 后端镜像和 Python Worker 镜像的本地构建配置，后续可以继续扩展为：
- 在 CI 中构建并缓存 Spring Boot / Python Worker 镜像。
- 推送镜像到 Docker Hub 或 GitHub Container Registry。
- 通过 SSH 或 Kubernetes 将新版本部署到服务器。

## Docker Compose 服务地址

| 服务 | 地址/端口 | 默认账号 | 默认密码 | 说明 |
| --- | --- | --- | --- | --- |
| Spring Boot 后端 | `http://localhost:8080/api` | `admin` | `admin123` | 后端 REST API |
| Python Worker | 容器内部运行 | 无 | 无 | 消费 RabbitMQ 遥感任务并回调后端 |
| PostgreSQL/PostGIS | `localhost:5433` | `postgres` | `1234` | 数据库名：`rs_image_asset` |
| RabbitMQ | `localhost:5672` | `guest` | `guest` | AMQP 连接端口 |
| RabbitMQ 管理控制台 | `http://localhost:15672` | `guest` | `guest` | 队列、交换机和连接管理 |
| MinIO API | `http://localhost:9000` | `minioadmin` | `minioadmin` | 对象存储 API |
| MinIO 控制台 | `http://localhost:9001` | `minioadmin` | `minioadmin` | 对象存储管理页面 |
| Redis | `localhost:6379` | 无 | 无 | 本地缓存服务 |
| GeoServer | `http://localhost:8081/geoserver` | `admin` | `geoserver` | 地理空间服务发布平台 |

### GeoServer 结果影像发布目录

任务结果发布到 GeoServer 时，不再使用 MinIO 预签名 URL 作为图层数据源。后端会先把 `result/` 目录下的 GeoTIFF 从 MinIO 同步到本地共享目录：

```text
data/geoserver-raster
```

Docker Compose 会把该目录挂载到 GeoServer 容器内：

```text
/opt/geoserver/raster-data
```

Spring Boot 发布 coverage store 时传给 GeoServer 的是稳定的 `file:///opt/geoserver/raster-data/...` 路径，避免预签名 URL 过期后 WMS/WCS 无法继续读取影像。

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

上传接口会先把 Multipart 文件保存为一份本地临时 GeoTIFF，主流程只复用这同一份临时文件完成元数据解析和原始影像上传。缩略图作为派生资源，在数据库提交成功后交给后台线程池异步生成。

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

上传流程会先解析本地临时 GeoTIFF，通过后再上传 MinIO。这样伪造后缀或无法被 rasterio 打开的文件不会先进入对象存储。文件保存、Python 解析和 MinIO 上传都在数据库事务外执行；只有影像记录入库使用短事务。如果数据库保存失败，后端会补偿删除已经上传的 `raw/` 对象；上传主流程的临时文件会在接口结束前自动清理。

当前阶段上传接口使用轻量并发限制，默认最多同时处理 2 个 GeoTIFF 上传请求。超过限制时接口会返回“当前上传任务较多，请稍后重试”，避免多个大文件同时触发磁盘 IO、MinIO 上传和 Python 进程堆积。可通过配置调整：

```yaml
upload:
  max-concurrent: 2
  thumbnail-core-pool-size: 1
  thumbnail-max-pool-size: 2
  thumbnail-queue-capacity: 20
  thumbnail-retry-batch-size: 20
  thumbnail-retry-fixed-delay-ms: 60000
```

缩略图生成流程：

1. 原始影像记录入库事务提交成功。
2. `afterCommit` 将缩略图任务提交到受限线程池。
3. 异步线程重新从 MinIO 下载原始 GeoTIFF 到自己的临时目录。
4. 调用 `python-worker/scripts/generate_thumbnail.py`。
5. Python 使用 rasterio 读取影像，优先使用前三个波段生成 RGB 缩略图。
6. 如果只有单波段，则生成灰度缩略图。
7. 生成的 PNG 上传到 MinIO。
8. `rs_image.thumbnail_object_key` 保存缩略图对象路径。

缩略图状态字段：

| 字段 | 说明 |
| --- | --- |
| `thumbnail_status` | `PENDING`、`RUNNING`、`SUCCESS`、`FAILED`、`SKIPPED` |
| `thumbnail_error_message` | 记录线程池拒绝、Python 失败、MinIO 失败或跳过原因 |

缩略图任务开始前会使用 `PENDING -> RUNNING` 条件更新做抢占，只有抢占成功的线程才会真正下载 GeoTIFF 和调用 Python，避免同一影像被多个异步线程重复处理。

`PENDING` 表示还没有真正开始处理，可能是刚上传成功、线程池暂时满了，或等待补偿扫描重新投递。定时补偿任务会周期性扫描 `thumbnail_status = 'PENDING' AND deleted_at IS NULL` 的影像，并重新提交缩略图异步任务。

缩略图 objectKey 格式：

```text
thumbnail/{yyyy}/{MM}/{imageId}.png
```

缩略图生成异常处理：

- GeoTIFF 读取失败：记录日志，影像主记录仍然保存成功。
- Python 脚本超时：记录日志，影像主记录仍然保存成功。
- Python 未生成 PNG：记录日志，影像主记录仍然保存成功。
- MinIO 上传失败：记录日志，影像主记录仍然保存成功。
- 线程池队列已满：保持 `thumbnail_status = PENDING`，等待下一轮补偿扫描重新投递。
- 只有 Python、GeoTIFF 文件处理或 MinIO 处理过程中真正失败时，才标记 `thumbnail_status = FAILED`。
- 缩略图上传成功但数据库回写失败时，会补偿删除已上传的缩略图对象。
- 异步执行时如果影像已被软删除，会跳过缩略图生成。
- 成功或失败后都会清理异步任务自己的本地临时 GeoTIFF 和 PNG 文件。

缩略图是展示增强能力，当前阶段不会因为缩略图失败回滚原始影像和元数据。上传接口返回时，`thumbnailObjectKey` 通常为空；列表页可先展示默认占位图，后续刷新获取缩略图。后续可增加补偿任务，定期扫描 `thumbnail_object_key IS NULL` 的影像并重新生成。

已有数据库需要执行升级脚本：

```powershell
psql -U postgres -d rs_image_asset -f src/main/resources/db/upgrade/20260511_thumbnail_status.sql
```

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

Docker Compose 部署时，后端容器通过 `MINIO_ENDPOINT=http://minio:9000` 访问 MinIO；返回给浏览器的预签名 URL 使用 `MINIO_PUBLIC_ENDPOINT=http://localhost:9000` 生成。预签名 URL 会把访问域名纳入签名，不能先用内部地址签名后再替换域名，因此后端会直接使用 public endpoint 和固定 region 生成可被浏览器访问的签名地址。

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

当前演示数据导入约定使用行政区划代码 `adcode` 作为 `rs_admin_region.id`，接口会同时返回 `id` 和字符串类型的 `adcode`。现阶段二者取值相同，`/api/images/search-by-region` 仍使用 `regionId` 参数保持兼容；后续如果改为独立数据库主键，可以继续用 `adcode` 作为稳定行政区划编码。

行政区级联与边界接口：

```text
GET http://localhost:8080/api/admin-regions/children
GET http://localhost:8080/api/admin-regions/children?parentId=310000
GET http://localhost:8080/api/admin-regions?level=province
GET http://localhost:8080/api/admin-regions/310101
GET http://localhost:8080/api/admin-regions/search?keyword=黄浦
```

`/api/admin-regions/{id}` 返回 `boundaryGeoJson` 用于前端绘制边界。默认使用展示级简化边界；如果确实需要原始边界，可以传：

```text
GET http://localhost:8080/api/admin-regions/310101?simplifyTolerance=0
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

变化检测提交时前端只传两期影像 ID，后端会在校验权限、状态、波段数、尺寸和坐标系后填充 MinIO 对象路径：

```json
{
  "imageId": 2,
  "taskType": "CHANGE_DETECTION",
  "params": {
    "beforeImageId": 1,
    "afterImageId": 2,
    "band": 1,
    "threshold": 0.2
  }
}
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

1. 收到消息后先调用 `POST /api/tasks/{taskId}/claim` 抢占任务。
2. 只有 `PENDING/RETRYING -> RUNNING` 抢占成功时，Worker 才能下载影像并执行计算。
3. 如果 claim 返回 `ALREADY_FINISHED`，说明任务已经是 `SUCCESS/FAILED/CANCELED`，Worker 直接 `ack`，不重复计算。
4. 如果 claim 返回 `ALREADY_RUNNING` 或抢占失败，Worker 使用 `nack/requeue`，交给 RabbitMQ 后续重投。
5. 处理成功并上传结果文件后，必须回调 `SUCCESS` 成功后才能 `ack`。
6. 可重试异常不直接标记 `FAILED`，而是回调 `RETRYING` 后 `nack/requeue`。
7. 超过 RabbitMQ `x-delivery-limit` 后进入 DLQ，由 Java 侧 DLQ 监听器标记最终 `FAILED`。
8. Python 回调必须解析统一响应 JSON，只有 `code = 200` 才算成功，不能只看 HTTP 200。
9. Worker 不应在关键回调失败时直接 `ack`，否则会出现结果已上传但任务状态仍卡住的问题。

Worker 抢占接口：

```http
POST http://localhost:8080/api/tasks/{taskId}/claim
```

返回示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "claimed": true,
    "action": "CLAIMED",
    "taskStatus": "RUNNING",
    "message": "任务抢占成功",
    "outputObjectKey": "result/NDVI/2026/05/task_1001.tif"
  }
}
```

抢占动作含义：

| action | Worker 行为 |
| --- | --- |
| `CLAIMED` | 执行计算 |
| `ALREADY_FINISHED` | 直接 `ack`，不重复计算 |
| `ALREADY_RUNNING` | `nack/requeue`，稍后重试 |
| `CLAIM_REJECTED` | `nack/requeue`，等待下次投递 |

Worker 结果文件幂等：

结果路径基于 `taskId` 固定生成。Worker 抢占成功后，如果发现 `outputObjectKey` 已经存在于 MinIO，说明可能是上次计算已上传结果但 `SUCCESS` 回调前崩溃，此时会跳过重复计算，直接回调 `SUCCESS` 并 `ack`。

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

- 完成 Maven + Spring Boot 3 + Java 17 后端基础工程，采用 controller、service、mapper、entity、dto、vo、config、common、exception 分层结构。
- 完成统一返回结构 `Result<T>`、统一响应码、业务异常和全局异常处理。
- 完成本地 Docker Compose 开发环境，包含 PostgreSQL/PostGIS、RabbitMQ、Redis、MinIO、GeoServer、Spring Boot 后端和 Python Worker。
- 完成 PostgreSQL/PostGIS 第一版表结构，覆盖影像资产、处理任务、任务日志、结果文件、行政区和 Outbox 可靠消息表。
- 完成影像资产基础模块，支持新增、详情、分页、删除、组合检索和行政区空间范围检索。
- 完成 GeoTIFF 上传链路，支持 MinIO 对象存储、rasterio 元数据解析、EPSG:4326 footprint 写入、缩略图异步生成和预签名 URL 访问。
- 完成第一版最小登录和权限模型，支持 `Authorization: Bearer <token>` 识别当前用户，并保留 `X-User-Id` 开发阶段兼容能力。
- 完成影像可见性修改接口，查询、任务提交、任务详情、任务日志、结果文件访问和 GeoServer 手动发布均已接入基础权限校验。
- 完成 RabbitMQ 遥感任务提交链路，支持 NDVI、NDWI、CHANGE_DETECTION 消息模型和任务状态机。
- 完成 RabbitMQ 可靠投递增强，支持 publisher confirm/return、死信队列、失败兜底、Worker claim 幂等抢占和简化版 Outbox 补偿投递。
- 完成 Python Worker 基础框架，支持 RabbitMQ 消费、MinIO 下载/上传、Spring Boot 状态回调。
- 完成 Spring Boot 后端和 Python Worker 容器化；后端镜像短期内内置 Python、GDAL、rasterio 和上传链路脚本，保证 GeoTIFF 元数据解析与缩略图生成可在容器内运行。
- 完成 Python Worker NDVI、NDWI 和简化变化检测计算逻辑，并复用公共栅格处理工具。
- 完成任务查询接口，支持任务详情、分页列表和任务日志升序查询。
- 完成 GeoServer REST API 接入，支持 workspace 创建、coverage store 创建、任务结果 GeoTIFF 发布和异步发布状态记录。
- 完成 GeoServer 结果发布可靠性优化，使用共享挂载目录替代 MinIO 预签名 URL 作为图层数据源，并增加 REST 超时、发布幂等和结果文件唯一约束。
- 完成 GitHub Actions 基础 CI，覆盖后端测试、Python Worker 语法检查和 Docker Compose 配置检查。
- 已补充多轮关键路径单元测试，覆盖上传、任务状态流转、Outbox、权限模型和 GeoServer 发布权限。

## 后续计划

- 接入真正的认证体系，将当前 `CurrentUserContext` 从请求头方案替换为 Spring Security + JWT 或统一网关认证。
- 扩展权限模型，支持组织、项目空间、共享授权、角色权限和 GeoServer 图层访问控制。
- 增加结果文件管理接口，支持结果文件列表、详情、发布状态查询、人工重试发布和可见性调整。
- 增加任务取消、人工重试、死信任务查询、死信重投和任务超时补偿机制。
- 将缩略图生成、元数据解析和 GeoServer 发布进一步任务化，逐步迁移到 Worker，最终让 Spring Boot 镜像回归纯 Java 运行环境。
- 完善 Python Worker 多实例部署、日志采集、资源限制和运行监控。
- 优化大文件处理能力，支持分片上传、断点续传、上传进度、文件校验和异步入库流程。
- 接入 Elasticsearch，实现影像资产、任务和结果文件的全文检索与标签检索。
- 接入 Redis，用于热点查询缓存、任务状态缓存或轻量分布式锁。
- 增强遥感算法能力，扩展裁剪、重投影、波段合成、栅格统计和更复杂的变化检测/智能解译模型。
- 增加 OpenAPI/Swagger 接口文档，补充端到端集成测试和接口示例集合。
- 完善生产化部署方案，补充镜像推送、服务器部署、环境变量密钥管理和 CI/CD 发布流程。

## 项目定位

该项目适合作为遥感、GIS、空间数据管理、智能解译和后端工程能力的综合实践项目。它不仅关注普通 CRUD 接口开发，也覆盖空间数据库、对象存储、异步任务、地图服务发布和遥感影像处理等更贴近真实业务的工程场景。
