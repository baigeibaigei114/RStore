# 第三步：单期监测区域

入口：[契约](contract.md)、[实际验证与交接](handoff.md)。

## 使用

1. 按第二步上传标准化影像包，确保 NDVI 准入通过。
2. 地图浏览 → 监测区域 → 新建区域，沿影像点击，双击结束，填写名称并保存。
3. 选择已保存区域 → 创建区域 NDVI → 选择 READY 影像 → 提交。
4. 任务详情查看区域内总数、有效数、比例、NDVI 统计，以及提交时的完整区域快照。
5. 成功后可下载 GeoTIFF；地图发布完成后可加载结果，区域外为 NoData。
6. 修改区域名称或重绘边界会增加版本；旧任务继续显示原区域快照。

未选择区域时仍可执行整幅裁剪矩形基线。首版仅单外环 Polygon，不含洞、多面、行政区选择或导入。
区域必须完整在输入栅格内，否则 Worker 明确失败。总像素为区域内像素中心数，不是矩形文件总像素。
区域有效比例不是植被覆盖率；覆盖完整不意味着没有云或 NoData。

注意：当前高德底图可能与 WGS84 影像发生坐标偏移，绘制以影像为准，不沿底图道路做精确勾画。
若尚无已发布影像，先跑一个整幅 NDVI 并在地图加载结果，再绘制区域。

## 数据库升级

脚本：src/main/resources/db/upgrade/20261002_monitoring_region.sql。
只新增 monitoring_region 表及索引，不重写历史表。新库、旧库均需执行；当前没有自动迁移框架。
执行前核对连接到本项目数据库，并做备份。Docker 示例（在仓库根目录）：

```powershell
docker exec rs-postgres psql -U postgres -d rs_image_asset -Atc "SELECT current_database(), current_user;"
docker exec rs-postgres pg_dump -U postgres -d rs_image_asset -Fc -f /tmp/before-regions.dump
docker cp rs-postgres:/tmp/before-regions.dump ./data/before-regions.dump
docker cp ./src/main/resources/db/upgrade/20261002_monitoring_region.sql rs-postgres:/tmp/monitoring-region.sql
docker exec rs-postgres psql -U postgres -d rs_image_asset -v ON_ERROR_STOP=1 -f /tmp/monitoring-region.sql
```

先检查实际容器名，示例名不应当作健康检查。备份含数据库业务数据，应仅本地保留，不提交。
回退应用代码不需删表；不要为了回退删除已保存的区域数据。

## 更新运行程序

正常环境可用根项目的构建方式重新构建后端与 Worker。已有本地依赖镜像时可用：

```powershell
mvn -o -DskipTests package
docker build --build-arg RUNTIME_IMAGE=rs-ingestion-backend:local -f shared/baseline/Dockerfile.backend -t rs-regions-backend:local target
docker build --build-arg RUNTIME_IMAGE=rs-ingestion-worker:local -f shared/baseline/Dockerfile.worker -t rs-regions-worker:local python-worker
docker compose -f docker-compose.yml -f shared/regions/compose.local.yml up -d --no-build --no-deps backend python-worker
```

基底镜像须存在；不存在时用根 Dockerfile，不自动下载替代来源。不要删除持久化卷。
前端在 frontend 执行 npm run build；开发服务器启动按 frontend/README.md。

## 验证

```powershell
mvn -o '-Dtest=MonitoringRegionServiceTest,NdviMetadataValidatorTest,RsTaskServiceImplTest,InputAdmissionTest' test
# 在 python-worker 目录、已有 GDAL 环境执行
python -m unittest discover -s tests -v
# 在 frontend 目录执行
npm run build
```

真实服务脚本在仓库根目录执行，凭据仅通过当前进程的 BASELINE_USERNAME / BASELINE_PASSWORD 传入：

```powershell
python shared/regions/verify_e2e.py --input data/ndvi-baseline/poyang-20241129-baseline.tif --manifest data/ndvi-baseline/poyang-20241129-v1.json --output-dir data/regions-e2e
```

脚本创建一个影像、两个区域、成功与失败各一个任务；保留数据用于排查。
原区域在验证结束时已变为 v2，成功任务继续引用 v1。每次运行使用新的输出目录。
默认后端为 IPv6 localhost:8080；可传 --base-url 指定已确认的服务地址。
遇到限流等待自然窗口，不清 Redis、不改限流配置。

浏览器验收：加载图层并定位 → 收起/展开面板 → 绘制/取消 → 保存/重绘 → 创建任务 →
区域统计与快照 → 发布后地图 → 刷新页面确认区域持久化。权限还应使用第二账号检查区域读写和任务提交。

部署后不想新增业务数据时，可使用同样的进程凭据执行
`python shared/regions/verify_readonly.py --task-id 24`（替换为实际成功发布的区域任务 ID），
核验认证、区域持久化与结果快照；不会创建影像、区域或任务。
