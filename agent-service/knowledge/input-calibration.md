---
sourceId: input-calibration
title: 本平台标准化输入与校准
sourceKind: PLATFORM
sourceUrl: null
reviewedAt: '2026-10-12'
---
# 标准化输入
依据仓库shared/ingestion/contract.md，首版支持Sentinel-2 L2A裁剪反射率GeoTIFF，四个Float32波段按B02、B03、B04、B08排列，NoData为-9999，最大2048乘2048像素，CRS和仿射网格完整。新NDVI使用redBand=3、nirBand=4，波段序号从一开始且必须不同。

## 离线校准
原始DN必须先按实际产品元数据的scale和offset转为反射率。输入像素已经是地表反射率时，栅格本身scale/offset应为1/0，Worker不再次校准。重复应用偏移会改变NDVI，不能把重复校准的样本用于业务解释。

## 准入与历史
来源manifest与TIFF摘要及真实结构匹配后，Java保存PASSED准入。普通上传和历史无admission资产仍为UNVERIFIED，不能通过模型声明或修改请求体绕过准入。修改波段使当前准入失效，历史任务保留原输入快照。准入证明符合本平台契约，不证明来源声明真实或具有科学真值。
