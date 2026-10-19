---
sourceId: nodata-mask
title: 本平台NoData与质量掩膜
sourceKind: PLATFORM
sourceUrl: null
reviewedAt: '2026-10-12'
---
# 有效像素规则
依据shared/baseline/ndvi-contract.md和shared/ingestion/contract.md，NoData、掩膜排除值、非有限值以及abs(NIR+RED)<=1e-6的像素均无效，不参与NDVI均值、最小值和最大值。对有效像素直接使用归一化公式，不往分母加epsilon，不把异常结果裁剪到[-1,1]来掩盖输入问题。

## 云与质量
首版云和质量掩膜在离线准备阶段写成NoData。标准化Sentinel-2样本采用SCL类别4、5、6、7，SCL重采样使用nearest；这是一项平台处理约定，不保证所有云或阴影都被正确识别。

## 全无效与输出
没有有效像素时计算明确FAILED，不返回伪造的零均值。有效均值恰好为零与统计缺失不同。输出Float32 GeoTIFF使用NaN表示NoData，保留原输入网格；均值使用Float64累加。
