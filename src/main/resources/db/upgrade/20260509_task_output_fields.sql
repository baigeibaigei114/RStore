-- 遥感处理任务结果输出位置字段。
ALTER TABLE rs_task ADD COLUMN IF NOT EXISTS output_bucket VARCHAR(100);
ALTER TABLE rs_task ADD COLUMN IF NOT EXISTS output_object_key VARCHAR(500);

COMMENT ON COLUMN rs_task.output_bucket IS '任务结果文件输出 bucket';
COMMENT ON COLUMN rs_task.output_object_key IS '任务结果文件输出对象路径';

CREATE INDEX IF NOT EXISTS idx_rs_task_output_object_key ON rs_task (output_object_key);
