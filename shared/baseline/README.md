# 单期 NDVI 基线

先读 [统计契约](ndvi-contract.md)，再读 [交接与实际验证](handoff.md)。
本阶段不测试旧 AI 规划，不自动调用模型，不引入 Agent、不改数据库结构。

## 交付内容

- Worker：掩膜感知 NDVI、零分母过滤、无有效像素报错、统计和缓存结果恢复。
- Java：回调接收统计对象、更新状态前校验、保存既有 result_metadata。
- 页面：NDVI 统计、缺失提示、正确的发布状态、发布期间继续轮询。
- 数据：人工样本及公开 Sentinel-2 小范围样本；数据存入根 data/，不提交 Git。
- 工具：数据准备、公开数据下载和真实服务端到端验证脚本。

## 环境

依赖现有 python-worker/requirements.txt，不增加生产依赖。
Windows 上 Rasterio/GDAL 可能需要 Conda DLL 搜索路径，优先 conda run 而非直接启动解释器。
以下示例均在项目根目录执行，环境名可替换为已安装 Worker 依赖的项目环境。
如遇输出编码异常，仅在当前 PowerShell 设置：

```powershell
$env:PYTHONUTF8='1'
```

## 数据准备

人工样本（输出已存在会拒绝覆盖）：

```powershell
conda run --no-capture-output -n my_gdal_env python python-worker/scripts/prepare_ndvi_baseline.py --fixture --output data/ndvi-baseline/synthetic.tif
```

公开样本：

```powershell
conda run --no-capture-output -n my_gdal_env python shared/baseline/fetch_public_sample.py --output data/ndvi-baseline/poyang-20241129-baseline.tif
```

- 数据源：Copernicus Sentinel-2 L2A，经 Element 84 Earth Search 提供 COG。
- 固定产品：S2B_50RLT_20241129_0_L2A。
- 中心：经度 116.025、纬度 28.925 附近；500×500 个 10 米像素。
- 波段：B02/B03/B04/B08；结合 STAC asset 和 earthsearch:boa_offset_applied 转换反射率。
- 本固定旧版 COG 已执行偏移，只应用 scale=0.0001；不能再次应用 asset 中遗留的 -0.1。
- SCL 最近邻对齐，保留 4/5/6/7 类；不是植被分类真值。
- 输出 .json 保存来源、日期、转换和 SHA256；.stac.json 保存完整原始公开元数据。
- 景级云量不等于裁剪范围的有效像素比例。
- 网络断流时脚本失败，不用 RGB 预览或虚构数据替代真实波段。

本地原始波段也可通过 prepare_ndvi_baseline.py 准备，使用 --help 查看参数。
其中 scale/offset 必须来自所使用产品的实际元数据；已转换反射率不能重复转换。
矩形裁剪包含水体等地物，不能直接把整体均值解释为农田健康程度。

来源：
- https://earth-search.aws.element84.com/v1/collections/sentinel-2-l2a/items/S2B_50RLT_20241129_0_L2A
- https://sentiwiki.copernicus.eu/web/s2-products
- https://github.com/Element84/earth-search/discussions/26

注意：旧集合的预处理规则不可无条件推广到 sentinel-2-c1-l2a 或原始 JP2。
首次试验的 poyang-20241129.tif 重复应用偏移，已废弃，任务 16 不能用于业务解释。
当前验收样本为 poyang-20241129-baseline.tif，任务 18。
原件未覆盖：recover_sample_offset.py 对首次样本校验 SHA256，反解并验证整数 DN 后重新转换，
记录 recoveredFromSha256；正常重新下载使用已修正的 fetch_public_sample.py，不需要执行恢复。

## 启动新代码

优先使用原有构建流程，仅重建本次相关服务：

```powershell
docker compose build backend python-worker
docker compose up -d --no-deps backend python-worker
```

不要执行 down -v，不删除旧数据。运行中的容器不会自动加载源码修改。

如果 Docker Hub 不可用，但本机已有该项目的可用运行镜像，可采用以下联调备选。
这不是可移植的生产构建，需先用 docker inspect 确认当前镜像名：

```powershell
mvn -o -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'package failed' }
docker build --pull=false -f shared/baseline/Dockerfile.backend --build-arg RUNTIME_IMAGE=intelligentremotesensingimageinterpretationandspatiotemporalassetmanagementplatform-backend -t rs-ndvi-baseline-backend:local target
if ($LASTEXITCODE -ne 0) { throw 'backend image build failed' }
docker build --pull=false -f shared/baseline/Dockerfile.worker --build-arg RUNTIME_IMAGE=intelligentremotesensingimageinterpretationandspatiotemporalassetmanagementplatform-python-worker -t rs-ndvi-baseline-worker:local python-worker
if ($LASTEXITCODE -ne 0) { throw 'worker image build failed' }
docker compose -f docker-compose.yml -f shared/baseline/compose.local.yml up -d --no-build --no-deps backend python-worker
```

备选后端镜像复用旧运行时中的上传解析脚本；本次没有修改这些脚本。
之后若修改它们，应恢复常规构建，不能仅复制 jar。

## 端到端验收

使用现有测试账号，将 BASELINE_USERNAME / BASELINE_PASSWORD 设置为当前进程环境变量。
不将账号、密码、JWT 或预签名 URL 写入记录。脚本会真实创建影像、任务并保留结果。

```powershell
conda run --no-capture-output -n my_gdal_env python shared/baseline/verify_e2e.py --input data/ndvi-baseline/synthetic.tif --output-dir data/ndvi-baseline/e2e-synthetic
conda run --no-capture-output -n my_gdal_env python shared/baseline/verify_e2e.py --input data/ndvi-baseline/poyang-20241129-baseline.tif --output-dir data/ndvi-baseline/e2e-public-final
```

输出目录必须不存在，防止覆盖历史记录。重复测试使用新的目录。
脚本验证登录、上传、幂等提交、真实 Worker、统计回查、下载、独立 Float64 公式比对及 WMS PNG。
不代表通过 ArcGIS 对照，也不代表完成了浏览器交互测试。
公开固定样本另检查预期值域；公式一致性不能替代输入预处理正确性。
运行后手工打开任务详情，检查统计卡片、下载按钮和地图入口。

失败样本：

```powershell
conda run --no-capture-output -n my_gdal_env python python-worker/scripts/prepare_ndvi_baseline.py --fixture --fixture-case all-nodata --output data/ndvi-baseline/all-nodata.tif
conda run --no-capture-output -n my_gdal_env python shared/baseline/verify_e2e.py --input data/ndvi-baseline/all-nodata.tif --output-dir data/ndvi-baseline/e2e-all-nodata --expect-no-valid-pixels
```

本机曾发现 IPv4 8080 被 httpd 占用，而 Docker 在 IPv6 可达：
可在命令追加 --base-url 'http://[::1]:8080/api'。不要结束来源不明的进程。
若前端请求出现 404，应先检查代理连接到哪个地址，不要直接修改业务代码。

## 自动化测试

```powershell
Push-Location python-worker
conda run --no-capture-output -n my_gdal_env python -m unittest discover -s tests -v
Pop-Location
conda run --no-capture-output -n my_gdal_env python -m unittest discover -s shared/baseline -p 'test_*.py' -v
mvn -o '-Dtest=RsTask*,RsImage*,ImageBand*,MessageOutbox*,GeoServer*,AuthInterceptorTest,TaskStatusTest' test
Push-Location frontend
npm run build
Pop-Location
```

## 边界

- 旧 Worker 无统计回调仍兼容，但旧结果不自动补数据，不算基线通过。
- 已存在、无 v1 标记的 NDVI 输出不复用；应新建任务。
- 没有有效像素抛出 ValueError，消费者按不可重试错误尝试回调 FAILED 并拒绝消息；不走普通计算重试。回调失败的处理仍遵循现有消费者逻辑。
- RUNNING 崩溃恢复、全量遥感产品适配、多边形分区统计和性能压力测试不属于本次完成项。
- 保留 NDWI 和 CHANGE_DETECTION 原行为，不能把 NDVI 验证结论推广到它们。
- 容器测试环境继续运行；停止本次应用服务可用 docker compose stop backend python-worker，
  不要停止或删除共享基础设施和数据卷。
