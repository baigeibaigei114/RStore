ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'READY';
ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(100);
ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS deleted_reason TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_rs_image_status'
    ) THEN
        ALTER TABLE rs_image
            ADD CONSTRAINT ck_rs_image_status
            CHECK (status IN ('UPLOADING', 'PARSING', 'READY', 'PROCESSING', 'DELETE_LOCKED', 'DELETED', 'FAILED'));
    END IF;
END $$;

COMMENT ON COLUMN rs_image.status IS '影像资产状态：UPLOADING、PARSING、READY、PROCESSING、DELETE_LOCKED、DELETED、FAILED';
COMMENT ON COLUMN rs_image.deleted_at IS '软删除时间；为空表示资产正常可见';
COMMENT ON COLUMN rs_image.deleted_by IS '执行软删除的用户标识，当前阶段可为空';
COMMENT ON COLUMN rs_image.deleted_reason IS '软删除原因';

CREATE INDEX IF NOT EXISTS idx_rs_image_deleted_at ON rs_image (deleted_at);
CREATE INDEX IF NOT EXISTS idx_rs_image_status ON rs_image (status);
