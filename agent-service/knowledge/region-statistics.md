---
sourceId: region-statistics
title: 本平台单期区域统计口径
sourceKind: PLATFORM
sourceUrl: null
reviewedAt: '2026-10-12'
---
# 区域像素归属
依据shared/regions/contract.md，监测区域是用户保存的私有EPSG:4326单外环Polygon。任务复制区域名称、几何和版本，后来编辑区域不会改写历史结果。像素中心在多边形内部或边界上才计入，采用center_covered，不使用all_touched。

## 完整覆盖
整个区域必须被输入栅格范围覆盖，coverageRatio=1表示范围完整覆盖，不代表云质量或植被覆盖率。部分覆盖、区域内没有像素中心、区域内全无效均明确失败。区域外输出NoData，但保留输入尺寸、CRS和仿射网格，不为统计自动重采样。

## 数量与有效比例
区域统计schemaVersion=2、scope=monitoring_region。totalPixelCount只计算区域内像素；validPixelCount+invalidPixelCount=totalPixelCount。validPixelRatio是区域内有效像素数除以区域内总数，是质量有效比例，不是植被覆盖率。矩形旧任务采用v1统计，不伪造区域信息。

## 均值与追溯
min、max、mean仅使用区域内有效输出像素。Java保存真实resultMetadata并校验区域快照与任务params一致。任务SUCCESS和图层PUBLISHED是两个状态；解释计算结果不必等待图层发布。引用方法资料不能取代taskId及resultMetadata作为数值来源。
