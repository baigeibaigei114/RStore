# 待决策事项与共享决定

此文件只记录会影响多个任务、公共模块或架构边界的问题。单个实现细节留在对应 work item 或代码评审中。

## 状态说明

- `OPEN`：等待决定，受影响任务通常应阻塞。
- `DECIDED`：已经形成明确决定，可以继续实施。
- `SUPERSEDED`：已被后续决定替代。

## 当前事项

### DEC-20261002-01：单期监测区域

- 状态：DECIDED；任务：REGION-005；依据：用户要求完成第三步。
- 独立 monitoring_region 表保存用户私有命名 Polygon（EPSG:4326），不复用公共行政区。
- 首版单环、最多 500 顶点、不跨日期变更线；PostGIS 检查几何有效性。支持创建、列表、详情、更新，不做删除、导入或行政区管理。
- tasks 新增可选顶层 monitoringRegionId，只适用于 NDVI；省略保留矩形基线。服务器从自有区域复制 regionSnapshot，客户端同名字段清除。
- 区域快照包含名称、几何、版本和像素规则；后续编辑不改变历史。Worker 用真实输入仿射网格验证完整覆盖，拒绝部分覆盖。
- 区域统计 schemaVersion=2、scope=monitoring_region；totalPixelCount 仅为区域内像素；区域外输出 NoData，保持原输入网格，避免重采样。
- 使用像素中心包含规则（边界中心归内），不使用 all_touched；零中心、全无效明确失败。范围完整覆盖率=1 不代表质量有效率。
- 新增可重复执行迁移，部署前核对目标并备份；不改历史建表脚本、不新增生产依赖。

### DEC-20260917-02：标准化输入契约与准入

- 状态：DECIDED；任务：INGEST-004；依据：用户批准第二步。
- 新增 /images/upload-standardized（file、manifest、name）；原上传保持不变。
- 固定 Sentinel-2 L2A 四波段、反射率 Float32、NoData=-9999、最大 2048×2048。
- 来源 JSON 版本 1，校验服务器读取的属性与文件 SHA256；标签不是来源真实性证明。
- 准入作为 GeoTiffMetadataVO 中服务器管理的 admission 对象存入既有 JSONB，不新增迁移。
- 任务 params.inputSnapshot 保存服务器复制的准入及算法信息；客户端同名字段被清除。
- 元数据创建接口不得注入 admission，波段修改使原准入失效；普通及历史输入未验证，新 NDVI 提交拒绝。
- 暂不提供旧资产重新认证入口，拒绝的新包不创建资产，因此不创建 REJECTED 资产记录；错误直接返回。
- 已排队旧消息兼容；带快照的新消息 Worker 校验输入摘要和单位，缓存结果绑定快照。
- 人工夹具明确 synthetic，不获正式准入；测试使用受控测试样例，不宣称卫星观测。

### DEC-20260917-01：先实施 NDVI 单期基线

- 状态：DECIDED
- 依据：用户批准先完成单期 NDVI 基线，暂不增加 Agent、多期对比或通用导入。
- work item：BASELINE-001。
- 决定：统计契约见 shared/baseline/ndvi-contract.md；使用既有 JSONB 字段。
- 兼容策略：旧回调缺少统计仍可接受，但不算基线通过；NDVI 新 Worker 必须提供统计。
- 数据范围：离线裁剪矩形；真实产品下载、服务联调分别记录实际结果。
- 原 Agent/RAG 规格保留为后续目标，不在本次执行其数据库/服务扩展。

## 记录模板

复制以下内容新增记录：

```markdown
### DEC-YYYYMMDD-01：简短标题

- 状态：OPEN
- 提出时间：
- 提出者：
- 影响的 work item：
- 需要决定的人：

背景与已确认事实：

-

可选方案及影响：

1.

最终决定：

- 尚未决定。

后续动作：

-
```
