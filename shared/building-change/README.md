# 建筑物变化检测

任务类型为 `BUILDING_CHANGE_DETECTION`，使用独立 RabbitMQ 路由和 CPU Worker。两期影像使用采集时间排序，需要相同的米制投影、仿射网格和尺寸。每景均为单边不超过 2400 像素的三波段 `uint8` RGB GeoTIFF。

## 运行

在现有平台栈基础上挂载 TinyCD 源码与权重。固定源码版本为 `1696827ffb7473c448591aaaa4434d4d4934a1ba`，权重文件为 `pretrained_models/levir_best.pth`，SHA-256 为 `743645b87199c96bd0c3271ae5df6f0918b3f1d84618dfb9396843ca08ee4a3e`。镜像使用 Python 3.11、PyTorch 2.12.0 CPU 和 torchvision 0.27.0 CPU。仓库不包含上游源码和权重。 [TinyCD 作者说明](https://github.com/AndreaCodegoni/Tiny_model_4_CD#license)

| 环境变量 | 用途 |
| --- | --- |
| `TINYCD_SOURCE_DIR` | 固定 TinyCD 版本的项目根目录，包含 `models/` |
| `TINYCD_WEIGHTS_FILE` | 固定权重的绝对路径 |
| `TINYCD_ENABLED` | 默认 `false`，Worker 就绪后设为 `true` 开启提交 |
| `TINYCD_SAMPLE_BEFORE`、`TINYCD_SAMPLE_AFTER` | 可选的示例影像 ID，当前账号有权限访问两景时显示「使用示例」 |
| `TINYCD_WORKER_TOKEN`、`TINYCD_MINIO_ACCESS_KEY`、`TINYCD_MINIO_SECRET_KEY` | 与同一栈的业务后端和对象存储配置对应 |

```powershell
docker compose -f docker-compose.yml -f shared/building-change/compose.yml --profile tinycd config -q
docker compose -f docker-compose.yml -f shared/building-change/compose.yml --profile tinycd up -d --build backend tinycd-worker
```

Worker 使用 2 CPU、2 GiB，模型启动时加载一次。任务沿用原 Outbox、租约和回调。成功状态允许读取统计与签名下载，GeoServer 随后发布前期、后期和变化图层。前后期 WMS 请求实时检查当前账号对原影像的访问权限。

## 输出口径

输入以 ImageNet RGB 归一化。反射填充后按 256 × 256 推理，取中央 192 × 192 拼接。共同无效位置的 RGB 在推理前置零，并在结果中标记为 255。阈值严格取大于 0.5。

| 值 | 含义 |
| --- | --- |
| 0 | 未标记变化 |
| 1 | 预测变化 |
| 255 | 不可比较，地图透明显示 |

预测变化面积为变化像元数乘仿射网格像元面积。变化比例使用两期共同有效像元数作为分母。共同有效覆盖使用全网格像元数作为分母。结果属于模型预测。

生产分块可用 `python-worker/scripts/verify_tinycd.py` 在固定权重、两期 Altadena TIFF 和 128 对 LEVIR-CD 测试集上复测。LEVIR-CD 图像和标注限学术使用。Altadena 样例应保留每景的来源标识及作者署名，核对两期素材授权后再开放公开展示。 [LEVIR-CD 数据说明](https://justchenhao.github.io/LEVIR/) · [Vantor 洛杉矶山火开放数据说明](https://vantor.com/blog/vantors-spatial-insights-support-response-to-the-los-angeles-wildfires/)
