# 运行与数据契约

本目录保存可复现的样本准备、运行步骤、数据契约及验证工具。

- [单期NDVI基线](baseline/README.md)：公开样本、反射率、掩膜与运行验证。
- [NDVI统计契约](baseline/ndvi-contract.md)：有效像素、统计和兼容边界。
- [标准化影像入库](ingestion/README.md)及[输入契约](ingestion/contract.md)：TIFF与来源JSON准入和快照。
- [监测区域与NDVI](regions/README.md)及[区域契约](regions/contract.md)：私有区域、像素中心规则与历史快照。
- [Agent/RAG运行说明](../agent-service/README.md)：内部服务、固定知识索引、确认与权限边界。

迁移和验证脚本应先核实目标、备份及恢复路径，不指向未知业务库。Agent的隔离验证不替代既有业务栈的协调迁移和最终部署验收。
内部交接、评审、任务台账和本机验证材料保留在本地，不随代码发布。
