CREATE TABLE IF NOT EXISTS ai_plan (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    user_input TEXT NOT NULL,
    title VARCHAR(255),
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    plan_json JSONB NOT NULL,
    validation_errors JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_plan_status CHECK (status IN ('DRAFT', 'VALID', 'INVALID', 'CONFIRMED', 'CANCELED'))
);

CREATE INDEX IF NOT EXISTS idx_ai_plan_user_id ON ai_plan (user_id);
CREATE INDEX IF NOT EXISTS idx_ai_plan_status ON ai_plan (status);
CREATE INDEX IF NOT EXISTS idx_ai_plan_created_at ON ai_plan (created_at);
