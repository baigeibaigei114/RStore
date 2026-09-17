# NDVI 单期基线契约 v1

范围：标准化反射率 GeoTIFF 的整个裁剪矩形，不宣称行政区或任意多边形统计。
原始 Sentinel-2 DN 必须先按产品元数据转换为反射率；Worker 不再次缩放。
输入波段序号从 1 开始，红光与近红外必须不同，按任务实际参数记录。
输入 NoData/掩膜、非有限数值、abs(NIR+RED)<=1e-6 的像素均无效。
有效像素计算 (NIR-RED)/(NIR+RED)，不向分母加 epsilon，不裁剪异常值到 [-1,1]。
输出 Float32，NaN 为 NoData，继承 CRS/transform；均值以 Float64 累加。
没有有效像素时抛出 ValueError，消费者按不可重试错误尝试回调 FAILED 并拒绝消息；
不走普通计算重试，回调失败的处理仍遵循现有消费者逻辑。

SUCCESS 回调新增可选 resultMetadata 对象：
- schemaVersion: 1
- algorithm: NDVI
- scope: cropped_raster
- bandMapping: {redBand: 正整数, nirBand: 正整数}
- statistics: {totalPixelCount, validPixelCount, invalidPixelCount, validPixelRatio, min, max, mean}

数量为整数；total=valid+invalid；valid>0；ratio=valid/total，范围 (0,1]。
min/mean/max 为有限数值且 min<=mean<=max。不强制 [-1,1]，避免隐藏输入问题。
总像素包含裁剪矩形中的无效像素；云掩膜由离线准备阶段写入 NoData。
Java 校验传入的 v1 元数据并保存既有 result_metadata JSONB，不新增表。
兼容旧 Worker：缺少元数据仍接受回调，页面明确显示缺失，不能声称其为通过基线的结果。
NDWI/CHANGE_DETECTION 不采用此契约，不改变它们的算法。

NDVI GeoTIFF 保存基线版本及实际波段标签。重试读取已存在输出并重新统计；
无标签旧输出拒绝复用，提示新建任务，避免给旧错误掩膜结果添加 v1 背书。
本契约不解决已有 RUNNING 任务的崩溃租约恢复问题。
