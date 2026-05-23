ALTER TABLE rs_task
    ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(100);

COMMENT ON COLUMN rs_task.client_request_id IS '客户端幂等请求 ID，同一用户下唯一';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rs_task_owner_client_request
    ON rs_task (owner_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
