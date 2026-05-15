CREATE TABLE IF NOT EXISTS message_outbox (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    exchange_name VARCHAR(100) NOT NULL,
    routing_key VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retry_count INTEGER NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE message_outbox DROP CONSTRAINT IF EXISTS ck_message_outbox_status;
ALTER TABLE message_outbox
    ADD CONSTRAINT ck_message_outbox_status
    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED'));

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_message_outbox_retry'
    ) THEN
        ALTER TABLE message_outbox
            ADD CONSTRAINT ck_message_outbox_retry
            CHECK (retry_count >= 0 AND max_retry_count > 0);
    END IF;
END $$;

COMMENT ON TABLE message_outbox IS '可靠消息 outbox 表，用于补偿数据库提交后消息未发送的任务消息';
COMMENT ON COLUMN message_outbox.aggregate_type IS '业务聚合类型，当前任务消息固定为 RS_TASK';
COMMENT ON COLUMN message_outbox.aggregate_id IS '业务聚合 ID，当前对应 rs_task.id';
COMMENT ON COLUMN message_outbox.payload IS '待投递到 RabbitMQ 的 JSON 消息体';

CREATE UNIQUE INDEX IF NOT EXISTS idx_message_outbox_aggregate ON message_outbox (aggregate_type, aggregate_id);
CREATE INDEX IF NOT EXISTS idx_message_outbox_status_retry ON message_outbox (status, next_retry_at);
