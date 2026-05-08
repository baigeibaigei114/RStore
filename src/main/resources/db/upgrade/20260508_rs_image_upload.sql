-- 已存在数据库升级脚本：支持 GeoTIFF 先上传入库、后续再解析空间范围。
ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS content_type VARCHAR(100);
ALTER TABLE rs_image ALTER COLUMN footprint DROP NOT NULL;

COMMENT ON COLUMN rs_image.content_type IS '文件 MIME 类型';
COMMENT ON COLUMN rs_image.footprint IS '影像覆盖范围，WGS84 坐标系 Polygon；上传后可为空，解析元数据后补全';
