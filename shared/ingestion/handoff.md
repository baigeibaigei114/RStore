# INGEST-004 交接

- 状态：done；负责人：codex-ingestion；时间：2026-09-17。
- 范围：标准化影像包、NDVI 准入、任务输入快照，不包含 Agent。

## 实现

- InputAdmission：限制来源 JSON 64 KiB、严格解析、版本和范围检查、SHA256 流式比对、实际栅格属性对照。
- 新接口 /images/upload-standardized 复用现有上传暂存、存储补偿和短事务；普通上传不授予准入。
- metadata_json.admission 保存服务器检查记录；params.inputSnapshot 保存创建时快照，无数据库迁移。
- 修改波段置 UNVERIFIED；客户端 inputSnapshot 被清除，由服务器重新生成。
- 新 Worker 消息校验输入 SHA256 和单位，输出记录快照摘要；重用输出必须快照一致。
- 老排队消息保持兼容，历史结果不自动补认证。新 NDVI 提交必须通过准入。
- 准备脚本统一 v1 manifest，人工夹具标记 SYNTHETIC；固定公开旧样本可显式升级来源 JSON。
- 前端新增标准化上传模式、准入来源卡片、任务快照展示和创建页拦截；使用 frontend-design 技能沿用既有样式。

主要文件：common/InputAdmission.java、RsImageController、RsImageService/Impl、
RsTaskServiceImpl、GeoTiffMetadataVO、parse_metadata.py、prepare_ndvi_baseline.py、
upgrade_baseline_manifest.py、ndvi_baseline.py、InputProvenance.vue、
ImageUploadView/ImageDetailView/TaskCreateView/TaskDetailView、对应 API/types 与测试。
完整范围见 shared/work-items.yaml；此前地图 UI 与用户 main.py 改动保留。

## 实际验证

- Maven 定向测试：
  mvn -o '-Dtest=InputAdmissionTest,RsTaskServiceImplTest,RsImageServiceImplTest,RsImageServiceImplPermissionTest,RsImageControllerUploadTest,ImageBandCapabilityServiceImplTest' test
  最终 57 项通过。包括未知版本/超大整数版本、摘要错误、实际属性不符、普通输入及伪造快照拒绝、
  任务快照写入、上传失败补偿与临时文件清理。
- my_gdal_env / 容器 rs-python-worker：python -m unittest discover -s tests -v，均 16 项通过。
- 公开样本校准回归：1 项通过。
- Python compileall：通过。
- frontend npm run build：类型检查和构建通过，仍有 >500 kB chunk 警告。
- Docker 镜像构建、Compose config --quiet、应用容器启动：通过。
- YAML/JSON 解析、git diff --check：通过。
- 真实 E2E 第一轮：普通影像27、标准化影像28、任务20。
- 更新最终输入边界校验后重复验收先触发 5次/300秒上传限流，未修改规则或清空 Redis。
- 窗口恢复后最终 E2E：普通影像29、标准化影像30、任务21，全部通过。
  记录：data/ndvi-baseline/e2e-ingestion-final-2/verification.json。
  测试覆盖错误包拒绝、普通输入无法绕过、标准化入库、幂等提交、真实 Worker 数值比对、
  输出绑定快照、修改波段使准入失效但保留历史快照。
  NDVI 均值 0.21487394715856648，有效248174，无效1826。

## 运行与数据

- rs-backend / rs-python-worker 仍运行：rs-ingestion-backend:local / rs-ingestion-worker:local。
- 后端访问 http://[::1]:8080/api；本机 IPv4 8080 曾有其他服务冲突，未终止未知进程。
- 本次未启动前端服务器；已生成前端构建产物。
- 启动继续使用 shared/ingestion/compose.local.yml；停止应用可执行 docker compose stop backend python-worker。
- 未删除数据或卷。测试资产30结束时为 UNVERIFIED（主动修改波段的反例），任务21快照仍为当时 PASSED。
- 可上传 data/ndvi-baseline/poyang-20241129-baseline.tif 与 poyang-20241129-v1.json 建立新的准入资产。

## 限制

- 未执行浏览器交互、全量 Java 测试、ArcGIS 对照、压力测试或旧 AI 测试。
- 不提供旧资产在线重新认证；失败的新包不创建 REJECTED 资产，返回明确错误。
- 仅验证声明与文件结构/摘要一致，不证明来源真实，也无法识别刻意伪造的完整一致声明。
- 质量类别原因没有逐像素保留，规则在 manifest 中追溯。
- 已有任务崩溃租约问题不在本次修复范围。
- 未暂存、提交或推送代码。

下一步先执行浏览器上传/详情/创建任务流程，再考虑区域多边形统计。
