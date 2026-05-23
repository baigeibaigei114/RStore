CREATE INDEX IF NOT EXISTS idx_rs_result_file_status_updated_at
    ON rs_result_file (status, updated_at);
