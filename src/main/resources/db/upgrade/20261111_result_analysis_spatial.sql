CREATE TABLE IF NOT EXISTS task_result_spatial_evidence (
    owner_id VARCHAR(100) NOT NULL,
    task_id BIGINT NOT NULL REFERENCES rs_task(id) ON DELETE CASCADE,
    result_version CHAR(64) NOT NULL,
    object_etag VARCHAR(128) NOT NULL,
    evidence JSONB NOT NULL,
    PRIMARY KEY (owner_id, task_id, result_version)
);
