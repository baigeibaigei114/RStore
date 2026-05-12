ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS thumbnail_status VARCHAR(30) NOT NULL DEFAULT 'PENDING';
ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS thumbnail_error_message TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_rs_image_thumbnail_status'
    ) THEN
        ALTER TABLE rs_image
            ADD CONSTRAINT ck_rs_image_thumbnail_status
            CHECK (thumbnail_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED'));
    END IF;
END $$;

COMMENT ON COLUMN rs_image.thumbnail_status IS '缩略图生成状态：PENDING、RUNNING、SUCCESS、FAILED、SKIPPED';
COMMENT ON COLUMN rs_image.thumbnail_error_message IS '缩略图生成失败或跳过原因';

CREATE INDEX IF NOT EXISTS idx_rs_image_thumbnail_status ON rs_image (thumbnail_status);
