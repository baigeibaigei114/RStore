CREATE TABLE IF NOT EXISTS task_result_answer (
    owner_id VARCHAR(100) NOT NULL,
    task_id BIGINT NOT NULL REFERENCES rs_task(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    result_version CHAR(64) NOT NULL,
    question VARCHAR(500) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETE', 'FAILED')),
    answer JSONB,
    error_message VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_id, task_id, request_id),
    CHECK ((status = 'COMPLETE') = (answer IS NOT NULL)),
    CHECK ((status = 'FAILED') = (error_message IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_task_result_answer_version
    ON task_result_answer (owner_id, task_id, result_version, created_at DESC, request_id DESC);
