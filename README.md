# 面向区域植被监测的遥感分析与报告平台

一个结合遥感计算与 AI 助手的影像管理平台。用户可以管理标准化遥感影像、绘制监测区域、提交 NDVI 分析，并在地图上查看结果与区域统计。

用户手动选择影像并提交计算。任务完成后，结果页可根据保存的统计回答问题、恢复追问并在地图定位分区。旧助手记录与报告可在历史入口查阅。

## 主要功能

- **影像管理**：上传与登记 GeoTIFF，查看元数据、来源信息和空间范围，按时间、传感器及空间条件检索。
- **SAFE 产品入库**：上传限定格式的 Sentinel-2 L2A 产品包，预览选区、确认标准化，经实际文件校验后登记私有影像。
- **区域分析**：在地图上绘制和保存监测区域，计算单期 NDVI，查看有效像素比例、最小值、最大值和均值。
- **异步任务**：通过消息队列调度计算，支持状态跟踪、提交幂等、租约续期和失败重试。
- **结果展示**：下载计算结果，通过 GeoServer 发布图层，在地图中浏览影像与分析结果。
- **任务结果解读**：支持区域 NDVI、整幅 NDWI 及对齐网格的双期变化检测。数值和地图范围来自当前任务，历史对话按结果版本保存。

启用 SAFE 产品入库后，可从受支持的产品包生成私有影像，并查看已发布的真彩色地图或手动提交区域 NDVI。SAFE 入库默认关闭。双期变化检测要求两期尺寸、坐标系和仿射网格相同。

## 系统架构

```text
用户 → Vue 页面 → Spring Boot 业务入口
                      ├─ Agent Service → DeepSeek / 向量知识库
                      └─ RabbitMQ → Python Worker → GeoTIFF / 统计结果
```

- **Spring Boot**：负责身份与权限、影像和区域管理、参数校验、任务确认、状态及结果保存。
- **Agent Service**：识别对当前任务的提问类型。后端从已保存的结果统计和快照组织回答。
- **Python Worker**：负责确定性的栅格处理，不调用大模型。

PostgreSQL/PostGIS 保存资产、区域和任务数据，MinIO 保存影像与结果文件，GeoServer 提供地图图层服务。

结果中的统计数值来自实际计算。用户可以在任务详情展开数据依据。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Element Plus、OpenLayers |
| 业务后端 | Java 17、Spring Boot 3、MyBatis |
| Agent / RAG | Python 3.11、FastAPI、LangChain、DeepSeek、BGE |
| 遥感处理 | GDAL、Rasterio、NumPy |
| 数据与存储 | PostgreSQL、PostGIS、pgvector、MinIO |
| 消息与访问控制 | RabbitMQ、Redis |
| 图层与部署 | GeoServer、Docker Compose |

## 本地运行

准备 Docker Compose 和 Node.js 22。首次运行前按下方运行说明准备配置；已有数据库需要执行相应的增量迁移。

启动后端、Worker 和基础服务：

```sh
docker compose up -d --build
```

启动前端：

```sh
cd frontend
npm ci
npm run dev
```

前端默认地址：`http://localhost:5173`；后端 API：`http://localhost:8080/api`。

任务结果页的「问这次结果」需要单独启用内部 Agent Service，配置模型和内部认证，并执行对应迁移。旧链接 `/agent` 保留为只读历史。默认 Compose 启动命令不包含 Agent Service。 [Agent 服务运行说明](agent-service/README.md)

## 项目结构

```text
frontend/        Vue 前端与地图交互
src/             Spring Boot 业务后端
python-worker/   遥感处理与消息消费
agent-service/   Agent、RAG 与内置方法知识
shared/          数据契约、样本准备和运行说明
docker/          基础服务初始化配置
```

## 运行说明

- [前端开发](frontend/README.md)
- [NDVI 样本与计算基线](shared/baseline/README.md)
- [标准化影像入库](shared/ingestion/README.md)
- [监测区域与区域 NDVI](shared/regions/README.md)
- [Agent 服务配置与知识索引](agent-service/README.md)

NDVI 用于描述植被绿度，不能仅凭单期均值判断作物健康或预测产量。
