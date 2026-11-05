# 影像入库

平台支持两种入库方式：上传已准备好的标准化 GeoTIFF，或从受支持的 Sentinel-2 L2A SAFE 产品包生成区域影像。Java 负责身份、状态、准入和资产登记；Python Worker 负责确定性的遥感处理。

## 标准化 GeoTIFF

在“影像上传”中选择 TIFF；需要用于 NDVI 时，选择“标准化影像包 · NDVI”，同时提交匹配的来源 JSON。详情页展示准入结果和波段信息，只有通过准入的影像才能提交新的 NDVI 分析。

格式与科学口径见[输入契约](contract.md)，参考[公开样本声明](example-public-v1.json)。修改波段映射会使原准入失效；应重新上传正确文件和声明，不覆盖已有任务的输入快照。来源声明和 SHA 校验不等于对卫星数据真实性的独立认证。

## SAFE 产品包

“产品入库”页面 `/ingestion` 的流程为：

1. 上传官方 SAFE ZIP，等待产品检查和定位预览。
2. 绘制并保存监测区域，或选择本人已有区域，执行裁剪预检。
3. 核对区域、固定像素窗口和影像名称，明确确认标准化。
4. 跟踪作业；Worker生成反射率TIFF，Java校验实际文件后登记PRIVATE影像。

目前仅接受PSD15 / PB05.13、官方紧凑目录结构、单产品/单granule的Sentinel-2 L2A。使用10m B02/B03/B04/B08和20m SCL，按原始网格与逐波段校准处理；JP2直接读取，PNG只作定位预览。ZIP上限2GiB，裁剪每边最多2048像素，不自动裁边、降采样或拼接。

输出为四波段Float32 GeoTIFF，保持10m分辨率，NoData=-9999，red=3/nir=4，并保留来源与处理声明。区域必须完整落在产品内；区域内没有有效反射率时会提示限制，不能据此保证该区域NDVI可计算。

已登记影像可以通过既有详情/下载入口使用。作业历史在资产删除或准入变化后仍保留；该资产不能再被复用。失败标准化需要用户明确重试，原包到期后可重传同一ZIP再绑定重试；相同处理意图不会重复创建成功资产。此流程不自动调用Agent或提交NDVI。

启用地图发布后，作业独立显示发布状态。影像通过准入且发布成功后，所有者可查看真彩色地图并获取原生 WCS。发布失败可单独重试，无需重传原包或重跑标准化。完整到期与孤儿清理仍属后续工作。

## 启用与运行

SAFE流程默认关闭。启用前应核实目标数据库和备份，并执行新增升级脚本 `src/main/resources/db/upgrade/20261029_ingestion_workflow.sql`；它不会在应用启动时自动迁移。已有GeoTIFF上传仍使用原入口。

同时更新后端、其Python解析脚本和所有Worker，再按已核实环境配置：

| 配置 | 用途 |
| --- | --- |
| `INGESTION_ENABLED=true` | Java与Worker启用入库协议及共享计算槽 |
| `INGESTION_STANDARDIZATION_ENABLED=true` | Java接受标准化确认；还要求LOCAL验证运行时就绪及Worker声明`standardize-v1` |
| `INGESTION_BUCKET_NAME` | 根Compose中供双方使用的独立私有桶，不能等于旧资产主桶；Worker单独运行时用`INGESTION_BUCKET` |
| `INGESTION_TEMP_DIRECTORY` | Java可写临时目录；Worker单独运行时用`WORKER_TEMP_DIR` |

Worker和Java本地原生验证需要Linux运行时；使用根Dockerfile/Compose构建时包含相关模块。启用协议前先排空并确认旧Worker/旧原生进程停止，不混跑不支持共享槽的执行器。凭据通过部署环境安全注入，不写进来源JSON或仓库。

处理使用持久工作意图、尝试租约与共享COMPUTE槽。租约过期不代表进程已经停止；无法确认停止或事务结果时保留恢复状态，不直接释放名额。原包有效期按7天校验，已确认活动工作保留引用；磁盘、临时空间、资产配额和执行时间均有有界拒绝限制，应在启用目标上另行核实容量。

## 启用私有地图发布

1. 沿用上述数据库迁移。GeoServer 只接入内部地图网络，Java 与 GeoServer 共享栅格目录，分别可写和只读。启用前核对硬链接、目录权限、实际网络与磁盘容量。
2. 通过部署环境配置独立的 `GEOSERVER_INGESTION_READ_USERNAME/PASSWORD` 和 `GEOSERVER_INGESTION_PUBLISH_USERNAME/PASSWORD`。读取账号仅使用 `ROLE_INGEST_PROXY`，发布账号使用受控管理权限。全新隔离实例可参考 [ACL 初始化工具](../../docker/geoserver/ingestion/configure.py)。现有实例应先备份并审查已有 ACL。
3. 核实匿名及普通用户无法读取私有图层，专用读账号无法访问管理 REST，旧图层代理正常后，将 `INGESTION_ENABLED`、`INGESTION_PUBLICATION_ENABLED` 和 `INGESTION_MAP_BOUNDARY_VERIFIED` 设为 `true`。发布与边界开关默认关闭。
