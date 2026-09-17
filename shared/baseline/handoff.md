# Agent 任务交接

## 基本信息

- Work item：BASELINE-001
- 当前状态：done（限定为本文件列明的单期基线，不代表整个原 Agent 规格完成）
- 交接 Agent：codex-ndvi-baseline
- 时间：2026-09-17，Asia/Shanghai

## 已完成

1. NDVI 输入掩膜与 NoData 取交集，排除非有限值和近零分母，不向分母加 epsilon。
2. Float32 输出、Float64 统计累加；输出内置版本和波段标签，复用结果重新读取统计。
3. Java 在 SUCCESS CAS 更新前校验元数据，写入现有 JSONB，不修改数据库结构。
4. 页面显示 NDVI 统计与缺失提示；异常值域提示；任务成功后继续轮询地图发布状态。
5. 人工、全 NoData 和公开 Sentinel-2 数据已准备；真实服务成功和失败路径已验收。
6. 没有测试旧 AI 规划，没有调用 LLM，没有新增生产依赖，没有提交或推送 Git。

## 修改文件

- python-worker/processors/ndvi_processor.py
- python-worker/utils/ndvi_baseline.py
- python-worker/scripts/prepare_ndvi_baseline.py
- python-worker/tests/test_ndvi_baseline.py
- src/main/java/com/remotesensing/platform/dto/RsTaskStatusUpdateDTO.java
- src/main/java/com/remotesensing/platform/common/NdviMetadataValidator.java
- src/main/java/com/remotesensing/platform/service/impl/RsTaskServiceImpl.java
- src/test/java/com/remotesensing/platform/service/impl/RsTaskServiceImplTest.java
- frontend/src/views/TaskDetailView.vue
- shared/baseline/：契约、复现说明、数据与验收脚本、校准测试、本地镜像备选文件、本交接。
- shared/work-items.yaml、shared/decision-log.md。

保留开始时用户的 python-worker/main.py 改动、AGENTS.md、postman/、原 shared 内容，
以及 RsTaskSubmitAutomationTest.java，未覆盖或提交它们。

## 实际验证

2026-09-17 执行（本机命令来源已核对）：

| 命令或检查 | 结果 |
| --- | --- |
| Maven 3.9.4 / JDK17：mvn -o '-Dtest=RsTask*,RsImage*,ImageBand*,MessageOutbox*,GeoServer*,AuthInterceptorTest,TaskStatusTest' test | 83 个通过 |
| my_gdal_env：python -m unittest discover -s tests -v（Worker 目录） | 13 个通过；Rasterio1.4.3 / NumPy2.4.3 |
| docker exec rs-python-worker python -m unittest discover -s tests -v | 13 个通过；部署依赖环境 |
| python -m unittest discover -s shared/baseline -p 'test_*.py' -v | 校准回归测试通过 |
| python -m compileall python-worker shared/baseline -q | 通过 |
| frontend：npm run build | 通过；有现存大 bundle 提示 |
| docker compose config --quiet | 通过 |
| 本地运行镜像构建并部署 backend、python-worker | 通过，不重建数据库或卷 |
| 人工影像真实 E2E | 影像22 / 任务14：SUCCESS、PUBLISHED、均值1/3 |
| 全 NoData 真实 E2E | 影像23 / 任务15：按预期 FAILED，日志确认 no valid pixels |
| 公开影像最终真实 E2E | 影像26 / 任务18：SUCCESS、PUBLISHED、幂等提交、下载、数值和 WMS 通过 |

实际完整输出位于忽略 Git 的 data/ndvi-baseline/：
- synthetic.tif / all-nodata.tif；
- poyang-20241129-baseline.tif 与 .json / .stac.json；
- e2e-synthetic-final/verification.json；
- e2e-all-nodata/verification.json；
- e2e-public-final/verification.json、ndvi.tif、wms.png。

公开样本是 S2B_50RLT_20241129_0_L2A，500×500、EPSG:32650、10米。
最终统计：有效248174、无效1826、有效比例0.992696、
min=-0.7214699983596802、max=0.905451238155365、mean=0.21487394715856648。
独立 Float64 公式比对最大绝对误差约2.98e-8，非 ArcGIS 对照。
WMS PNG 已人工查看，可见水体和地块；前端仅做构建，未执行浏览器交互回归。

## 调试中发现并处理的问题

- Conda 捕获中文输出引发 GBK 编码错误：当前进程 PYTHONUTF8=1、--no-capture-output 后正常。
- GDAL 原始远程分块读取不完整：有限重试、串行 Range 和超时调整后成功。
- Docker Hub 返回 unexpected EOF：常规镜像重建未完成；复用已安装依赖的本地项目运行时构建联调镜像。
- IPv4 8080 被独立 httpd 服务占用：未停止该服务，验收使用 http://[::1]:8080/api。
- 公开旧版 COG 已执行 BOA 偏移，首次准备脚本重复减0.1，产生异常 NDVI。
  检查数值范围发现，而非用 clipping 掩盖；修正下载校准规则并加入回归测试。
  首次错误影像24 / 任务16虽然业务链与算式比对通过，但不满足科学输入验收，明确废弃。
  影像25 / 任务17是修正后中间验收，最终入口使用影像26 / 任务18。
  原文件和任务保留用于追溯，未删除历史记录。

## 未执行与边界

- 未执行 ArcGIS/QGIS 交叉软件对照，不能声称与专业软件实测一致。
- 未执行浏览器交互、并发压力、大影像性能或崩溃恢复测试。
- NDVI Worker 要求已标准化输入；不是对任意原始卫星产品的自动转换服务。
- 统计范围为裁剪矩形，不是精确行政区、多边形分区或植被覆盖率。
- 旧回调缺少统计仍兼容，旧结果不自动回填。
- RUNNING 崩溃租约问题、NDWI/变化检测原算法仍未处理。
- SCL 和指数不能单独证明作物健康、病虫害或产量。

## 运行状态

rs-backend 与 rs-python-worker 正运行本次本地联调镜像：
rs-ndvi-baseline-backend:local / rs-ndvi-baseline-worker:local。
其余用户启动的基础容器保持运行。前端没有新增常驻进程。
应用服务可用 docker compose stop backend python-worker 停止，不要 down -v。
继续运行备选镜像须合并 shared/baseline/compose.local.yml；正常构建方式见 README。

## 下一步入口

1. 阅读本目录 README.md 与 ndvi-contract.md。
2. 前端启动后查看任务18的统计、下载与地图，完成浏览器交互验收。
3. 学习本次 offset 事故：先验证输入物理含义，再验证公式与软件链路。
4. 后续再安排恢复机制、报告追溯和 Agent，不测试或重构旧 AI 规划来扩大本次范围。
