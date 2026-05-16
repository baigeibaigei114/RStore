ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'PENDING_PUBLISH';
ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS workspace VARCHAR(100);
ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS store_name VARCHAR(255);
ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS layer_name VARCHAR(255);
ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS wms_url TEXT;
ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS wcs_url TEXT;
ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS publish_error_message TEXT;
ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;
ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE rs_result_file DROP CONSTRAINT IF EXISTS ck_rs_result_file_status;
ALTER TABLE rs_result_file
    ADD CONSTRAINT ck_rs_result_file_status
    CHECK (status IN ('PENDING_PUBLISH', 'PUBLISHING', 'PUBLISHED', 'PUBLISH_FAILED'));

COMMENT ON COLUMN rs_result_file.status IS '结果文件发布状态：PENDING_PUBLISH、PUBLISHING、PUBLISHED、PUBLISH_FAILED';
COMMENT ON COLUMN rs_result_file.layer_name IS 'GeoServer 图层名称';
COMMENT ON COLUMN rs_result_file.wms_url IS 'GeoServer WMS 访问地址';
COMMENT ON COLUMN rs_result_file.wcs_url IS 'GeoServer WCS 访问地址';
COMMENT ON COLUMN rs_result_file.publish_error_message IS 'GeoServer 发布失败原因';

CREATE INDEX IF NOT EXISTS idx_rs_result_file_status ON rs_result_file (status);
