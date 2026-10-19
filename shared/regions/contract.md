# 单期监测区域契约

本阶段仅实现单期区域 NDVI，不包含多期、Agent 或植被健康诊断。

## 区域 API

需要登录，所有查询及修改按当前用户隔离：

- POST /api/monitoring-regions：{name, geometry}。
- GET /api/monitoring-regions：当前用户最新 100 个区域。
- GET /api/monitoring-regions/{id}：详情。
- PUT /api/monitoring-regions/{id}：{name, geometry}，每次更新 version + 1。

geometry 为 EPSG:4326 GeoJSON Polygon 几何对象（不是 Feature），仅一个闭合外环，
4 至 501 个坐标（含重复闭合点），二维有限经纬度，纬度限制 ±85，跨度不超过 180°。
PostGIS 校验非空、非自交和非零面积；名称 1 至 100 字符。
不执行隐式拓扑修复，不支持洞、多面或跨日期变更线。

## 任务与输出

POST /api/tasks 顶层可选 monitoringRegionId（正整数），仅 NDVI 可使用。
params.regionSnapshot 始终由服务器清除后重建；保存 schemaVersion、regionId、name、
version、geometry、crs、pixelRule=center_covered。区域读取与复制使用同一行版本。

Worker 投影区域到输入 CRS 后，再变换到像素坐标系。完整区域必须在栅格边界内；
允许 1e-7 像素的浮点投影误差，不允许实际部分覆盖。几何边按相邻顶点直线解释。
像素中心在多边形内或边界上即计入，不按接触面积计入。边界判断容差 1e-9 像素。
零中心或没有有效 NDVI 像素时 FAILED。质量有效规则沿用 v1。

区域输出保持输入尺寸、CRS 和仿射网格，区域外为 NaN NoData。输出绑定输入与区域快照摘要，
缓存恢复重新计算区域掩膜和统计，防止不同区域误复用。区域统计：

- schemaVersion=2、scope=monitoring_region、algorithm=NDVI。
- regionSnapshot 与任务参数完全对应。
- coverageRatio=1，表示整区被栅格范围覆盖，不是质量有效率。
- statistics.totalPixelCount 只计算区域内像素；
  validPixelCount + invalidPixelCount = totalPixelCount。
- validPixelRatio = 区域内有效 / 区域内总数；不是植被覆盖率。
- 最小、最大、均值仅使用区域内有效输出 Float32 像素，均值用 Float64 累加。

Java 回调同时校验版本、统计自洽、区域快照与已持久化任务的一致性。
旧矩形任务沿用 v1，不伪造区域信息。
