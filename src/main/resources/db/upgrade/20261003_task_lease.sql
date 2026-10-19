-- 先停止旧 Worker 并备份；不修改历史结果，也不自动执行此脚本。
BEGIN;
ALTER TABLE rs_task ADD COLUMN IF NOT EXISTS attempt_id VARCHAR(36);
ALTER TABLE rs_task ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rs_task ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;
UPDATE rs_task SET lease_until = clock_timestamp() + INTERVAL '120 seconds'
WHERE status = 'RUNNING' AND lease_until IS NULL;
CREATE INDEX IF NOT EXISTS idx_rs_task_running_lease
    ON rs_task(lease_until) WHERE status = 'RUNNING';
COMMIT;
