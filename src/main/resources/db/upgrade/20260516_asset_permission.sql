ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS owner_id VARCHAR(100);
ALTER TABLE rs_image ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';
UPDATE rs_image SET owner_id = 'dev-user' WHERE owner_id IS NULL OR owner_id = '';
ALTER TABLE rs_image ALTER COLUMN owner_id SET DEFAULT 'dev-user';
ALTER TABLE rs_image ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE rs_image DROP CONSTRAINT IF EXISTS ck_rs_image_visibility;
ALTER TABLE rs_image
    ADD CONSTRAINT ck_rs_image_visibility CHECK (visibility IN ('PRIVATE', 'PUBLIC'));
COMMENT ON COLUMN rs_image.owner_id IS '影像归属用户标识';
COMMENT ON COLUMN rs_image.visibility IS '影像可见性：PRIVATE、PUBLIC';
CREATE INDEX IF NOT EXISTS idx_rs_image_owner_visibility ON rs_image (owner_id, visibility);

ALTER TABLE rs_task ADD COLUMN IF NOT EXISTS owner_id VARCHAR(100);
UPDATE rs_task SET owner_id = 'dev-user' WHERE owner_id IS NULL OR owner_id = '';
ALTER TABLE rs_task ALTER COLUMN owner_id SET DEFAULT 'dev-user';
ALTER TABLE rs_task ALTER COLUMN owner_id SET NOT NULL;
COMMENT ON COLUMN rs_task.owner_id IS '任务归属用户标识';
CREATE INDEX IF NOT EXISTS idx_rs_task_owner_id ON rs_task (owner_id);

ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS owner_id VARCHAR(100);
ALTER TABLE rs_result_file ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';
UPDATE rs_result_file SET owner_id = 'dev-user' WHERE owner_id IS NULL OR owner_id = '';
ALTER TABLE rs_result_file ALTER COLUMN owner_id SET DEFAULT 'dev-user';
ALTER TABLE rs_result_file ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE rs_result_file DROP CONSTRAINT IF EXISTS ck_rs_result_file_visibility;
ALTER TABLE rs_result_file
    ADD CONSTRAINT ck_rs_result_file_visibility CHECK (visibility IN ('PRIVATE', 'PUBLIC'));
COMMENT ON COLUMN rs_result_file.owner_id IS '结果文件归属用户标识';
COMMENT ON COLUMN rs_result_file.visibility IS '结果文件可见性：PRIVATE、PUBLIC';
CREATE INDEX IF NOT EXISTS idx_rs_result_file_owner_visibility ON rs_result_file (owner_id, visibility);
