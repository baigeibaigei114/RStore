# 项目命名与 README 同步交接

## 基本信息

- Work item：DOCS-002
- 状态：done
- 交接 Agent：codex-docs
- 日期：2026-09-17

## 已完成与修改文件

- 根 README：聚焦植被监测，增加当前能力、NDVI 口径、业务流程、历史验收与后续计划；修正 Outbox、结果恢复描述；不再列出默认密码。
- frontend/README.md、frontend/index.html、AppHeader.vue、router/index.ts：统一产品展示名称。
- pom.xml：仅更新 name / description，不修改构件坐标。
- shared/README.md：增加当前基线入口，明确原 Agent 规格是后续目标。
- shared/baseline/README.md、ndvi-contract.md：纠正无有效像素的不可重试异常描述。
- shared/work-items.yaml：记录任务范围和验证。

## 实际验证

- 前端 npm run build：通过；存在大于 500 kB 的 chunk 警告。
- Markdown 本地相对链接检查：通过。
- pom.xml XML 解析：通过。
- git diff --check：通过，仅存在 Git 换行转换提示。
- 展示入口旧全称检索：无匹配（rg 退出码 1 表示未找到）。

未重跑 Java/Worker 测试与端到端测试：本次仅修改文档、展示文案及 Maven 描述信息。README 中的 83/13 项测试是 BASELINE-001 的历史记录，不是本次重跑。

## 风险与遗留事项

- 未修改项目目录、包名、artifactId、数据库、容器及对象路径。
- 未修改历史数据库脚本中的旧名称注释，也未实施新的 Agent 规格。
- 未启动、停止或重新部署服务，未做浏览器交互验证。
- 用户已有工作区修改全部保留；未暂存、提交或推送。

## 提交说明建议

仅提交本次文档与名称变更：

```text
docs: 同步植被监测项目定位与 NDVI 基线说明

- 统一 README、页面标题与 Maven 项目展示名称
- 补充 NDVI 输入标准、统计口径、业务流程与验收边界
- 修正 Outbox、结果恢复和无有效像素处理说明
- 区分已完成基线与后续 Agent、区域统计和报告能力
```

若与 BASELINE-001 一并提交：

```text
feat(ndvi): 建立单期植被监测基线并同步项目定位

- 实现掩膜感知 NDVI、无效像素过滤和版本化统计
- 增加 Java 统计校验持久化及前端统计与发布状态展示
- 提供影像标准化、公开样本准备与端到端验证脚本
- 统一项目展示名称并补充运行、验证及能力边界文档
```

下一位维护者先阅读根 README 和 shared/baseline/handoff.md；提交前逐项审查暂存范围，不将测试影像、凭据或无关改动加入提交。
