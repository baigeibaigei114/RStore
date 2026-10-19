---
sourceId: ndvi-method
title: NDVI方法与资料来源
sourceKind: EXTERNAL_SCIENTIFIC
sourceUrl: https://www.usgs.gov/landsat-missions/landsat-normalized-difference-vegetation-index
reviewedAt: '2026-10-12'
---
# NDVI方法
NDVI是归一化植被指数。USGS资料说明，NDVI用红光与近红外反射率构造归一化比值，用于描述植被绿度。公式为 (NIR - RED) / (NIR + RED)，其中NIR是近红外，RED是红光。算法公式不等于数据质量保证，也不是本次任务的统计数值来源。

## 传感器与波段
红光与近红外波段的位置取决于传感器，不能把某个传感器的波段编号直接套用到另一个传感器。USGS网页给出Landsat实例；本平台Sentinel-2标准化四波段输入的redBand=3、nirBand=4属于平台输入契约，应另查平台资料，不是USGS的Landsat编号。

## 来源性质
本文为对USGS公开资料的简短中文概述，保留原始链接供核查，并非复制完整文章。外部方法资料解释计算原理；真实任务数值必须回查Java保存的resultMetadata。
