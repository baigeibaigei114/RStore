-- Java execution state belongs in the same database/transaction manager as rs_task/outbox.
BEGIN;
CREATE TABLE IF NOT EXISTS ai_ndvi_execution (
    id UUID PRIMARY KEY, owner_id VARCHAR(100) NOT NULL,
    create_request_id UUID NOT NULL, create_request_hash CHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL CHECK (status IN ('RUNNING','NEEDS_INPUT','WAITING_CONFIRMATION','EXPIRED',
      'WAITING_TASK','READY_TO_EXPLAIN','EXPLAINING','SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','UNSUPPORTED','CANCELED')),
    version INT NOT NULL CHECK(version>=1), intent_json JSONB NOT NULL, candidate_json JSONB NOT NULL,
    draft_version INT NOT NULL DEFAULT 0 CHECK(draft_version>=0), draft_json JSONB, draft_hash CHAR(64),
    draft_expires_at TIMESTAMPTZ, confirmed_version INT, confirmed_hash CHAR(64),
    task_id BIGINT UNIQUE REFERENCES rs_task(id),
    operation_id UUID, operation_kind VARCHAR(30), operation_deadline TIMESTAMPTZ, operation_version INT,
    model_call_count INT NOT NULL DEFAULT 0 CHECK(model_call_count BETWEEN 0 AND 12),
    tool_call_count INT NOT NULL DEFAULT 0 CHECK(tool_call_count BETWEEN 0 AND 8),
    explanation_json JSONB, explanation_key CHAR(64), result_metadata_sha256 CHAR(64), knowledge_index_id CHAR(64),
    error_code VARCHAR(80), safe_error_message VARCHAR(500), retryable BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(owner_id,create_request_id),
    CHECK(task_id IS NULL OR (confirmed_version IS NOT NULL AND confirmed_hash IS NOT NULL)),
    CHECK(operation_id IS NULL OR (operation_kind IS NOT NULL AND operation_deadline IS NOT NULL AND operation_version IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_ai_ndvi_owner_updated ON ai_ndvi_execution(owner_id,updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_ndvi_operation_deadline ON ai_ndvi_execution(status,operation_deadline);
CREATE TABLE IF NOT EXISTS ai_ndvi_step (
    id UUID PRIMARY KEY, execution_id UUID NOT NULL REFERENCES ai_ndvi_execution(id),
    sequence INT NOT NULL CHECK(sequence>0), operation_id UUID NOT NULL, client_request_id UUID, request_hash CHAR(64),
    kind VARCHAR(30) NOT NULL, name VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK(status IN ('RUNNING','SUCCESS','FAILED','INTERRUPTED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), ended_at TIMESTAMPTZ, duration_ms BIGINT,
    safe_input_json JSONB CHECK(octet_length(safe_input_json::text)<=4096),
    safe_output_json JSONB CHECK(octet_length(safe_output_json::text)<=4096),
    error_code VARCHAR(80), safe_error_message VARCHAR(500), provider VARCHAR(40),
    model_requested VARCHAR(128), model_returned VARCHAR(128), usage_json JSONB,
    attempt INT NOT NULL DEFAULT 1 CHECK(attempt>=1), UNIQUE(execution_id,sequence)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_ndvi_operation_request
    ON ai_ndvi_step(execution_id,client_request_id) WHERE client_request_id IS NOT NULL;
-- A linked task/confirmation is immutable even under accidental application updates.
CREATE OR REPLACE FUNCTION ai_ndvi_guard_task_link() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.task_id IS NOT NULL AND (NEW.task_id IS DISTINCT FROM OLD.task_id
        OR NEW.confirmed_version IS DISTINCT FROM OLD.confirmed_version OR NEW.confirmed_hash IS DISTINCT FROM OLD.confirmed_hash) THEN
        RAISE EXCEPTION 'Confirmed task link is immutable';
    END IF;
    IF NEW.task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM rs_task WHERE id=NEW.task_id AND owner_id=NEW.owner_id) THEN
        RAISE EXCEPTION 'Task owner differs from execution owner';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS ai_ndvi_guard_task_link ON ai_ndvi_execution;
CREATE TRIGGER ai_ndvi_guard_task_link BEFORE UPDATE OR INSERT ON ai_ndvi_execution
    FOR EACH ROW EXECUTE FUNCTION ai_ndvi_guard_task_link();
COMMIT;
