# 面向区域植被监测的遥感分析与报告平台

一个结合遥感计算与 AI 助手的影像管理平台。用户可以管理标准化遥感影像、绘制监测区域、提交 NDVI 分析，并在地图上查看结果与区域统计。

平台集成 LangChain/DeepSeek Agent 与 RAG，辅助查找影像、整理分析参数，并结合真实计算结果和方法资料生成带来源引用的解释。

## 主要功能

- **影像管理**：上传与登记 GeoTIFF，查看元数据、来源信息和空间范围，按时间、传感器及空间条件检索。
- **区域分析**：在地图上绘制和保存监测区域，计算单期 NDVI，查看有效像素比例、最小值、最大值和均值。
- **异步任务**：通过消息队列调度计算，支持状态跟踪、提交幂等、租约续期和失败重试。
- **结果展示**：下载计算结果，通过 GeoServer 发布图层，在地图中浏览影像与分析结果。
- **NDVI 助手**：候选查询、缺参补问、提交前确认、任务恢复与带引用的结果解释；方法知识来自内置资料的向量检索。

当前重点是已有标准化影像和已保存区域的**单期 NDVI**。多期分析、原始卫星产品自动预处理及通用文档上传暂不包含在当前范围内。

## 系统架构

```text
用户 → Vue 页面 → Spring Boot 业务入口
                      ├─ Agent Service → DeepSeek / 向量知识库
                      └─ RabbitMQ → Python Worker → GeoTIFF / 统计结果
```

- **Spring Boot**：负责身份与权限、影像和区域管理、参数校验、任务确认、状态及结果保存。
- **Agent Service**：负责模型交互、受控动作生成、知识检索和引用解释。
- **Python Worker**：负责确定性的栅格处理，不调用大模型。

PostgreSQL/PostGIS 保存资产、区域和任务数据，MinIO 保存影像与结果文件，GeoServer 提供地图图层服务。

AI 提议需要经过 Java 校验和用户确认才能提交任务；结果中的统计数值来自实际计算，模型只生成有资料依据的说明。

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

NDVI 助手入口为 `/agent`。它需要单独启用内部 Agent Service，并配置模型、知识索引和内部认证，详见 [Agent 服务运行说明](agent-service/README.md)。默认 Compose 启动命令不包含该服务。

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
