# 第三步交接：单期监测区域

Work item：REGION-005；状态：done；实施：codex-region；日期：2026-10-02。

## 已完成

- 新增 monitoring_region 私有区域表、增量迁移、创建/列表/详情/更新 API。
- Java 校验有限二维坐标、闭合单环、顶点数与经纬范围；PostGIS 检查拓扑、非空与面积。
- 任务顶层 monitoringRegionId 仅适用于 NDVI；提交前校验归属。客户端区域快照清除，
  从区域记录复制完整几何、名称、版本到任务与队列消息，不在执行时读取可变区域。
- Worker 将区域顶点变换到真实影像像素坐标，检查完整覆盖，按像素中心归属生成掩膜。
  区域外不计入 totalPixelCount；输出保持原网格、外部为 NaN。无中心/全无效明确失败。
- v2 结果绑定输入和区域快照，缓存重用校验；Java 回调比对持久化区域快照。
- 地图区域面板支持新建、重绘、取消、保存、选择并定位、跳转任务；
  创建任务可选择区域或保留整幅基线；详情展示区域口径和历史快照。
- 创建页区域加载期间或链接区域不可访问时阻止提交；旧区域不在最近 100 条时读取详情，
  不静默改成整幅统计。该追加保护通过最终类型/构建检查，未单独执行异常路由浏览器测试。
- 不新增生产依赖，不修改旧 AI 模块，不提交或推送 Git，不删除原有数据。

## 主要文件

- Java：MonitoringRegionController / MonitoringRegionService / MonitoringRegionMapper，
  MonitoringRegionDTO / VO，RsTaskSubmitDTO、RsTaskServiceImpl、NdviMetadataValidator。
- 迁移：src/main/resources/db/upgrade/20261002_monitoring_region.sql。
- Worker：utils/region_mask.py、utils/ndvi_baseline.py、tests/test_region_ndvi.py。
- 前端：api/monitoringRegion.ts、components/MonitoringRegionPanel.vue、
  MapViewerView / TaskCreateView / TaskDetailView、types/task.ts。
- 配套：[契约](contract.md)、[使用与升级](README.md)、verify_e2e.py、compose.local.yml。

## 实际验证

| 验证 | 2026-10-02 结果 |
| --- | --- |
| Maven 定向：MonitoringRegionServiceTest、NdviMetadataValidatorTest、InputAdmissionTest、RsTaskServiceImplTest、RsImageServiceImplTest、RsImageServiceImplPermissionTest、RsImageControllerUploadTest、ImageBandCapabilityServiceImplTest、AuthInterceptorTest | 77 项通过 |
| python -m unittest discover -s tests -v（python-worker） | 本机 22 项通过 |
| docker exec rs-python-worker python -m unittest discover -s tests -v | 22 项通过 |
| Python compileall（Worker、区域验收脚本） | 通过 |
| npm run build | Vue/TS 与构建通过，保留已有大 chunk 警告 |
| Maven package | 初次跳过重复测试打包；最终 package 再执行上述 77 项定向测试通过 |
| docker compose -f docker-compose.yml -f shared/regions/compose.local.yml config --quiet | 通过 |
| 数据库备份、增量迁移 | 已完成，新表创建成功；重复执行迁移通过 |
| shared/regions/verify_e2e.py | 通过，下列记录可追溯 |
| Playwright 桌面浏览器 | 登录、WMS 加载、绘制、保存、区域任务提交、结果统计、取消绘制、面板开关、重新进入后选择持久化区域通过 |
| 最终部署后 verify_readonly.py --task-id 24 | 未登录访问拒绝、区域持久化、任务 SUCCESS/PUBLISHED、v2 快照一致通过 |

真实服务记录：data/regions-e2e-20261002/verification.json。
影像 31、区域 1、任务 22 成功，区域 2 的任务 23 部分覆盖失败。
区域 1 验收中更新到 v2，任务 22 仍固定 v1。自交区域创建被拒绝，幂等提交返回同一任务。

任务 22：

- 区域总像素 62500，有效 62428，无效 72，有效比例 0.998848。
- min=-0.7177700400352478，max=0.905451238155365，mean=0.22246078740638。
- 使用输入左上四分之一的数组索引生成独立预期掩膜，与实际 TIFF 的掩膜、数值、统计、
  CRS、仿射网格一致，不调用生产区域掩膜来充当验证预期。

浏览器创建区域 3“浏览器验收样区”，任务 24 成功：
9447 个区域内像素全部有效，页面均值 0.261301；结果发布完成，控制台未见错误。
截图保留在 output/playwright/。没有调用旧 AI 规划或报告。
临时 .playwright-cli 快照清理被执行策略拒绝，保留在本机并通过该目录 .gitignore 排除，
不可将其强制加入 Git；交付截图不含登录表单。没有保存认证 storage state。

权限验证：服务单元测试覆盖非属主读/改拒绝及越权区域不能锁影像或创建任务；
认证拦截器回归通过。未执行真实双账号浏览器权限验收。

## 部署与本地状态

- 首次检查 Docker daemon 未运行，经 docker desktop start --detach 启动。
- 迁移前确认数据库 rs_image_asset / postgres、现有任务数 11，区域表不存在。
- 备份：data/before-regions-20261002.dump，pg_restore --list 校验可读取；未进行恢复演练。
- 后端镜像 rs-regions-backend:local，Worker 镜像 rs-regions-worker:local，
  复用既有 ingestion 镜像依赖，仅更新项目程序。原数据卷保留。
- 本轮数据库中留下影像 31、区域 1/2/3、任务 22/23/24 与对应结果，未清理。
- 后端与 Worker 保留运行，服务访问默认 http://localhost:8080/api；
  IPv4 8080 有其他服务占用，本轮 API 脚本用 http://[::1]:8080/api。
- 停止本轮应用可在仓库根目录执行 docker compose stop backend python-worker，不使用 down -v。
- 浏览器验收临时前端使用 http://127.0.0.1:5173，验收结束后停止；测试浏览器关闭。
- 已同步 Obsidian 项目概览、变更记录和风险待办，位于 D:/develop/ObsidianVault/20-项目文档/ 下同名仓库目录。

工具问题记录：一次使用 conda run 传递多行 python -c 参数被 Conda 拒绝（退出 1，
NotImplementedError: arguments contain newlines），归类命令封装限制，不是项目失败。
改为 verify_readonly.py 文件后退出 0，没有更换环境、安装依赖或修改全局配置。

## 限制与下一步

- 首版单外环 Polygon；无洞、多面、跨日期变更线、导入和区域删除，不支持任意行政区统计。
- 几何边按变换后的相邻顶点直线解释；仅适用于现有小裁剪影像，不是全球大区域测地处理。
- 当前高德底图存在坐标系统偏移风险，界面已提示以影像定位；不把屏幕绘制当作精密测绘。
- 桌面流程已验收；390px 窄屏默认主侧栏会挤压内容，收起侧栏后可显示地图和滚动面板。
  本轮没有扩大为全站移动端重构，也未验证触屏绘制。
- 未做 ArcGIS/QGIS 对照、并发压力测试、备份恢复演练和真实双账号权限测试。
- 原有 Worker RUNNING 租约/崩溃恢复问题未处理；地图配色仍为现有 GeoServer 样式。
- 后续优先统一网格、质量与日期口径做同一区域多期对比，再接受控 Agent 与报告。

下一位 Agent 先读本交接、contract.md 和 shared/work-items.yaml，检查当前 Git 脏状态。
此前 main.py、AGENTS.md、postman/、shared/specs/、shared/templates/ 和
RsTaskSubmitAutomationTest.java 是已有内容，本轮未改写。
