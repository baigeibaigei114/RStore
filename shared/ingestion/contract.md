# 标准化输入 v1

支持 Sentinel-2 L2A 裁剪反射率 GeoTIFF，4 个 Float32 波段 B02/B03/B04/B08，
nodata=-9999，CRS 完整，非退化仿射变换，尺寸不超过 2048×2048。
输入自身的 scale/offset 必须为 1/0（反射率已写入像素）。

POST /api/images/upload-standardized，multipart：file、manifest、name。
manifest 最大 64 KiB；结构：

- schemaVersion：整数 1
- productType：SENTINEL2_L2A（人工测试为 SYNTHETIC，正式入口拒绝）
- preprocessingVersion：s2-reflectance-v1
- sourceId、captureTime（带时区 ISO8601）
- inputUnits：surface_reflectance
- sha256：TIFF SHA256
- bandOrder：[B02,B03,B04,B08]
- crs、width、height、transform：[a,b,c,d,e,f]（Rasterio 顺序，不是 GDAL 顺序）
- calibration：blue/green/red/nir 各含已应用的 scale > 0、offset，均为有限数
- sclValidClasses：[4,5,6,7]；sclResampling：nearest
- window：[col,row,width,height]，非负整数起点，尺寸与输出相同

脚本生成来源说明；服务器比对文件摘要和实际栅格结构，保留校准来源声明。
通过仅证明符合此契约，不证明声明真实、云掩膜一定正确或影像具有科学真值。
字段不支持下载 URL 或服务器文件引用，服务器不执行声明中的指令。

普通和旧资产无 admission 即 UNVERIFIED。通过包保存 admission：
status=PASSED、validatorVersion=input-v1、checkedAt、manifest。
修改波段配置会置 UNVERIFIED。新 NDVI 必须准入且使用 red=3/nir=4；
任务快照固定 admission、imageId、algorithmVersion=ndvi-v1、denominatorTolerance=1e-6。
历史任务和旧排队消息不回填认证。新消息由 Worker 校验输入 SHA256。

来源 JSON 与影像元数据原子入库；不保存可执行路径，不新增外部资源访问。
失败包不入库，前端展示错误原因。需要更正的旧资产以新包重新上传，不修改原文件。
