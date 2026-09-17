# 共享协作目录

本目录保存多个开发 Agent 需要共同读取的项目规格和协作状态。

当前产品名称为“面向区域植被监测的遥感分析与报告平台”。近期已完成的是 [单期 NDVI 基线](baseline/README.md)；原 Agent/RAG 规格保留为后续目标，不表示已经实现，也不替代当前基线验收口径。

## 内容

- [单期 NDVI 基线](baseline/README.md)：数据准备、运行和测试入口。
- [NDVI 统计契约](baseline/ndvi-contract.md)：输入、掩膜、统计和兼容边界。
- [基线交接](baseline/handoff.md)：实际执行的验证与已知限制。

- [受控 Agent、LangChain 与向量知识库规格](specs/agent-rag-spec.md)：描述产品目标、边界和验收标准。
- [实施里程碑与任务台账](work-items.yaml)：记录任务领取、依赖、文件范围、验证和交接。
- [待决策事项](decision-log.md)：记录会影响多个任务、不能由单个 Agent自行决定的问题。
- [任务模板](templates/work-item-template.yaml)：把里程碑拆成可独立领取任务时使用。
- [交接模板](templates/handoff-template.md)：任务完成、暂停或换人时使用。

## 使用顺序

1. 完整阅读相关规格，不根据任务标题猜测实现。
2. 从里程碑拆出范围清晰、可独立验证的 work item。
3. 在台账中领取任务并声明准备修改的路径。
4. 实施期间只修改已声明范围；遇到跨模块歧义时登记决策问题。
5. 完成后记录真实验证结果和交接信息。

本目录第一阶段只解决开发 Agent 的协作效率，不预先定义产品接口、消息或业务 Schema。相应契约应在具体实施任务中按需创建和评审。
