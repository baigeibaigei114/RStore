# 遥感影像智能解译与时空资产管理平台

## 项目背景

遥感影像在自然资源调查、城市规划、生态监测、灾害评估、农业生产和基础设施巡检等场景中具有重要价值。随着卫星、无人机和航空遥感数据持续增长，传统依赖人工下载、整理、查看和判读影像的方式，已经难以满足高频、多源、大规模的业务需求。

本项目面向“遥感影像管理与智能解译”场景，计划建设一个集影像资产管理、空间检索、时序管理、智能解译任务调度、解译结果发布与可视化服务于一体的后端平台。平台重点关注遥感影像从入库、存储、处理、检索到服务发布的完整流程，为后续接入深度学习模型、GIS 可视化前端和业务分析应用提供稳定基础。

## 技术栈

| 类型 | 技术 | 用途 |
| --- | --- | --- |
| 后端框架 | Spring Boot 3 | 构建 REST API 和后端业务服务 |
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
├── mapper          # 数据访问层接口
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
