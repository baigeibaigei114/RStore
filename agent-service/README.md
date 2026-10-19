# 单期区域 NDVI Agent/RAG 内部服务

独立 FastAPI 服务承载受控 JSON 动作与内置知识检索，不提交遥感任务。浏览器只访问 Java；Java负责JWT/owner、白名单工具、草稿确认、预算、幂等与Task/Outbox关联，Worker保持确定性计算。
支持已有标准化影像和已保存区域的单期NDVI；不提供作物诊断、产量预测、多期分析或任意文档上传。

## 依赖

Python 3.11。`pyproject.toml`和`uv.lock`锁定依赖，`requirements.txt`由同一锁导出，包含hash；torch使用官方CPU源。

```sh
cd agent-service
uv sync --frozen
```

镜像：在仓库根目录运行 `docker build -t ndvi-agent-service agent-service`。镜像不在启动时下载模型、迁移或建立索引，默认关闭外部tracing和access log，以非root用户运行。

## 配置

`.env.example`只包含变量名与空凭据。Python不自动读取.env文件；配置通过进程环境或容器注入。

| 变量 | 用途 |
| --- | --- |
| AGENT_ENABLED | 显式开启内部服务；默认false |
| AGENT_INTERNAL_TOKEN | 独立内部认证token，至少32字符，Java与Python一致 |
| AGENT_DATABASE_URL | 知识只读角色DSN，运行API使用 |
| AGENT_INDEX_DATABASE_URL | 仅知识同步CLI使用的写角色DSN，不传给API容器 |
| AGENT_MODEL_PATH | 下述固定revision的本地snapshot目录 |
| DEEPSEEK_API_KEY | 外部Provider密钥，通过环境注入 |
| DEEPSEEK_BASE_URL | 官方基址，默认https://api.deepseek.com/v1 |
| DEEPSEEK_MODEL | 默认deepseek-v4-flash，需确认账户可用性及费用 |

模型为BAAI/bge-small-zh-v1.5，revision `7999e1d3359715c523056ef9478215996d62a620`，512维CPU归一化向量。应预先下载并只读挂载这个snapshot；目录末级必须为该revision，不兼容的模型或切片索引会拒绝就绪。

## 数据库与知识索引

先核实目标、备份和恢复方式，再由管理员按已有输入/区域表迁移基础执行业务库增量SQL：

1. `src/main/resources/db/upgrade/20261003_task_lease.sql`
2. `src/main/resources/db/upgrade/20261014_agent_ndvi.sql`

不要重建业务库或清理历史表。知识库使用PostgreSQL 16/PostGIS/pgvector，显式执行`agent-service/sql/001_knowledge.sql`，独立配置最小权限的索引写角色与运行只读角色；迁移不包含生产凭据。
确认配置及模型就绪后，在agent-service目录显式同步：

```sh
uv run python -m app.knowledge.sync --manifest knowledge/manifest.json
```

只读取manifest登记的五份内置Markdown；文档/模型/切片变化生成新版本并原子激活，相同版本同步no-op。知识Markdown是运行输入，不应当作文档清理。
片段目标500字符、重叠80字符，包含特殊token不超过512，超长片段再次切分保留尾部；查询超限明确拒绝。检索使用精确cosine Top5，命中不等于证据充分。

## 启动与接口

```sh
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000 --no-access-log
```

内部接口位于`/internal/v1/`：GET health/ready，POST decisions/answers/explanations；均要求`X-Internal-Token`和UUID `X-Request-Id`，请求及响应各限64KiB。ready不调用收费Provider，无公开Swagger页面。
Java启用`agent` profile并设置`AGENT_ENABLED=true`、`AGENT_BASE_URL`和同一个内部token；具体基础设施配置通过外部环境提供。`compose.agent.yml`可与根Compose合并，无宿主端口映射，模型snapshot只读挂载。

模型无自动重试。返回动作必须通过严格schema、预算和白名单；引用必须来自当次检索，原文quote匹配且摘要逐字对应主张。解释数值来自Java保存的真实统计，不由模型生成；安全错误不回显原始异常或敏感请求。

## 本地验证与边界

```sh
uv run pytest -q
uv run ruff check app tests
uv run mypy app
```

默认跳过需要真实embedding/隔离PG的集成节点；显式配置`AGENT_RUN_INTEGRATION=1`和专用测试库后才能执行，禁止指向日常业务库。测试不自动发起收费模型调用。
前端在`frontend/`按现有npm锁安装后构建，入口`/agent`；进度查询不调用模型，提交前需确认，重复确认返回相同任务。

当前有隔离环境验证；既有业务环境的协调迁移/部署、真实MQ/MinIO/Redis故障演练、生产负载及科学真值评估尚未最终验收。不要将单期均值当作作物健康结论，也不要将引用合法性等同于开放问题普遍正确率。
