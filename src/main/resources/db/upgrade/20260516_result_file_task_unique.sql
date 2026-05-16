DROP INDEX IF EXISTS idx_rs_result_file_task_id;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rs_result_file_task_id ON rs_result_file (task_id);
