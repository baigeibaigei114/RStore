-- 已存在数据库升级脚本：支持 GeoTIFF 先上传入库、后续再解析空间范围。
ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS content_type VARCHAR(100);
ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS metadata_json JSONB;
ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS thumbnail_object_key VARCHAR(500);
ALTER TABLE rs_image ALTER COLUMN footprint DROP NOT NULL;

COMMENT ON COLUMN rs_image.content_type IS '文件 MIME 类型';
COMMENT ON COLUMN rs_image.metadata_json IS 'GeoTIFF 解析得到的完整元数据 JSON';
COMMENT ON COLUMN rs_image.thumbnail_object_key IS 'MinIO 中 PNG 缩略图对象路径';
COMMENT ON COLUMN rs_image.footprint IS '影像覆盖范围，WGS84 坐标系 Polygon；上传后可为空，解析元数据后补全';

CREATE INDEX IF NOT EXISTS idx_rs_image_thumbnail_object_key ON rs_image (thumbnail_object_key);
