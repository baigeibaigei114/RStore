# 第二步：标准化入库、准入与追溯

先读 [输入契约](contract.md)。普通上传仍可保存和查看影像，但新 NDVI 提交需要输入契约校验通过。
没有数据库迁移：版本化 admission 保存在影像 metadata_json，inputSnapshot 保存在任务 params。
不把 SHA256/单位标签当作科学真实性证明；当前为文件与声明一致性检查。

合法参考：[固定公开样本 v1](example-public-v1.json)，仅匹配已验收的 baseline.tif；
错误参考：[未知版本样例](example-invalid.json)。不要将合法样例的摘要复制给其他影像。

## 如何使用

1. 使用 python-worker/scripts/prepare_ndvi_baseline.py 准备本地波段，真实模式必须提供带时区的 capture-time。
2. 或用 shared/baseline/fetch_public_sample.py 下载固定公开样本。它复用 write_stack，自动输出 v1 来源 JSON。
3. 前端“影像上传”选择“标准化影像包 · NDVI”，选 TIFF 和同名 JSON，填写名称。
4. 详情页检查准入与来源。提交 NDVI 后，任务详情展示创建时的处理依据。
5. 手动修改波段会使准入失效；重新上传正确包获得新的资产，不覆盖历史任务。

人工 fixture 的 productType=SYNTHETIC，正式入口明确拒绝。
旧基线脚本通过普通上传直接提交的端到端示例不再适用于新准入规则；计算级夹具测试继续保留。

### 升级已验收公开样本

新生成样本不需要升级。仅已有、校准正确的固定样本可使用下面的显式迁移：

```powershell
conda run --no-capture-output -n my_gdal_env python python-worker/scripts/upgrade_baseline_manifest.py --input data/ndvi-baseline/poyang-20241129-baseline.tif --manifest data/ndvi-baseline/poyang-20241129-baseline.json --output data/ndvi-baseline/poyang-20241129-v1.json
```

脚本校验固定摘要、产品 ID 和校准参数，不修改 TIFF、不覆盖旧 JSON、不猜测任意旧文件。
本机已生成 poyang-20241129-v1.json，可直接与原 baseline.tif 一起上传。

## 运行与验证

正常部署仍使用根目录 Dockerfile / docker compose 构建。必须同时更新后端 jar、
后端容器中的 python-worker/scripts 和 Worker；只替换 jar 不够。

本机已有运行时的联调备选（不是可移植生产构建）：

```powershell
mvn -o -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'package failed' }
docker build --pull=false -f shared/baseline/Dockerfile.backend --build-arg RUNTIME_IMAGE=rs-ndvi-baseline-backend:local -t rs-ingestion-jar:local target
if ($LASTEXITCODE -ne 0) { throw 'backend jar build failed' }
docker build --pull=false -f shared/ingestion/Dockerfile.backend-scripts -t rs-ingestion-backend:local python-worker/scripts
if ($LASTEXITCODE -ne 0) { throw 'backend scripts build failed' }
docker build --pull=false -f shared/baseline/Dockerfile.worker --build-arg RUNTIME_IMAGE=rs-ndvi-baseline-worker:local -t rs-ingestion-worker:local python-worker
if ($LASTEXITCODE -ne 0) { throw 'worker build failed' }
docker compose -f docker-compose.yml -f shared/ingestion/compose.local.yml up -d --no-build --no-deps backend python-worker
```

凭据通过当前进程环境变量 BASELINE_USERNAME / BASELINE_PASSWORD 提供，不写入文件：

```powershell
conda run --no-capture-output -n my_gdal_env python shared/ingestion/verify_e2e.py --input data/ndvi-baseline/poyang-20241129-baseline.tif --manifest data/ndvi-baseline/poyang-20241129-v1.json --output-dir data/ndvi-baseline/e2e-ingestion-new
```

脚本会创建两个资产和一个任务，最后修改标准化资产的波段配置以验证准入失效。
新任务被阻止，旧任务快照保留；因此该测试资产结束时为 UNVERIFIED。输出目录不能已经存在。
不调用 AI，不自动删除资产。IPv4 8080 曾有冲突，脚本默认使用本机 IPv6 后端。

## 不包含

- 旧资产在线重新认证、审批或自动转换。
- 来源真实性认证、多产品适配、任意原始 DN 自动预处理。
- 区域统计、多期对比、Agent、Worker 崩溃租约与大影像性能改造。
- 新接口不创建 REJECTED 资产；失败包直接报错且不入库。
- 浏览器视觉交互验收仍需手工执行，构建通过不等于浏览器验收。
