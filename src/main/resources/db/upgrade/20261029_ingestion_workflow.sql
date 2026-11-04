-- SAFE ingestion v1. Apply once to an explicitly verified target; no historical data changes.
BEGIN;
CREATE TABLE ingest_product (
    id UUID PRIMARY KEY, owner_id VARCHAR(100) NOT NULL, version BIGINT NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_request_id UUID NOT NULL, name VARCHAR(100) NOT NULL, file_name VARCHAR(255) NOT NULL,
    expected_bytes BIGINT NOT NULL CHECK(expected_bytes BETWEEN 1 AND 2147483648),
    status TEXT NOT NULL CHECK(status IN ('AWAITING_UPLOAD','UPLOADING','INSPECT_QUEUED','INSPECTING','READY','FAILED','EXPIRED')),
    raw_state TEXT NOT NULL DEFAULT 'ABSENT' CHECK(raw_state IN ('ABSENT','RESERVED','AVAILABLE','DELETE_PENDING','DELETED')),
    upload_expires_at TIMESTAMPTZ NOT NULL, upload_generation INTEGER NOT NULL DEFAULT 1 CHECK(upload_generation BETWEEN 1 AND 3),
    inspect_generation INTEGER NOT NULL DEFAULT 0 CHECK(inspect_generation BETWEEN 0 AND 3),
    raw_object_id UUID, archive_sha256 CHAR(64) CHECK(archive_sha256 ~ '^[0-9a-f]{64}$'),
    uploaded_at TIMESTAMPTZ, expires_at TIMESTAMPTZ, descriptor JSONB, descriptor_version INTEGER,
    parser_version TEXT, profile_version TEXT, current_attempt_id UUID, last_error JSONB,
    request_receipts JSONB NOT NULL DEFAULT '{}', UNIQUE(id,owner_id), UNIQUE(owner_id,create_request_id)
);
CREATE TABLE ingest_confirmation (
    id UUID PRIMARY KEY, owner_id VARCHAR(100) NOT NULL, version BIGINT NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_request_id UUID NOT NULL, product_id UUID NOT NULL, monitoring_region_id BIGINT NOT NULL REFERENCES monitoring_region(id),
    region_version BIGINT NOT NULL, region_snapshot JSONB NOT NULL, image_name VARCHAR(100) NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','READY','CONSUMED','FAILED','EXPIRED')),
    generation INTEGER NOT NULL DEFAULT 1 CHECK(generation=1), "window" JSONB, source_grid JSONB,
    canonical_document TEXT, confirmation_hash CHAR(64) CHECK(confirmation_hash ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ, current_attempt_id UUID, confirm_request_id UUID, job_id UUID, last_error JSONB,
    UNIQUE(id,owner_id), UNIQUE(owner_id,create_request_id), UNIQUE(owner_id,confirm_request_id),
    FOREIGN KEY(product_id,owner_id) REFERENCES ingest_product(id,owner_id),
    CHECK(status <> 'CONSUMED' OR job_id IS NOT NULL),
    CHECK(status <> 'READY' OR ("window" IS NOT NULL AND confirmation_hash IS NOT NULL AND expires_at IS NOT NULL AND canonical_document IS NOT NULL))
);
CREATE TABLE ingest_job (
    id UUID PRIMARY KEY, owner_id VARCHAR(100) NOT NULL, version BIGINT NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    origin_product_id UUID NOT NULL, active_product_id UUID NOT NULL,
    archive_sha256 CHAR(64) NOT NULL CHECK(archive_sha256 ~ '^[0-9a-f]{64}$'),
    intent_hash CHAR(64) NOT NULL CHECK(intent_hash ~ '^[0-9a-f]{64}$'), intent_document TEXT NOT NULL,
    region_snapshot JSONB NOT NULL, "window" JSONB NOT NULL, profile_version TEXT NOT NULL, image_name VARCHAR(100) NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('QUEUED','RUNNING','VALIDATING','SUCCEEDED','FAILED')),
    generation INTEGER NOT NULL DEFAULT 1 CHECK(generation BETWEEN 1 AND 3), current_attempt_id UUID,
    candidate_artifacts JSONB, image_id BIGINT UNIQUE REFERENCES rs_image(id), request_receipts JSONB NOT NULL DEFAULT '{}', last_error JSONB,
    UNIQUE(id,owner_id), UNIQUE(owner_id,intent_hash), CHECK((status='SUCCEEDED') = (image_id IS NOT NULL)),
    FOREIGN KEY(origin_product_id,owner_id) REFERENCES ingest_product(id,owner_id),
    FOREIGN KEY(active_product_id,owner_id) REFERENCES ingest_product(id,owner_id)
);
ALTER TABLE ingest_confirmation ADD FOREIGN KEY(job_id,owner_id) REFERENCES ingest_job(id,owner_id);
CREATE TABLE ingest_publication (
    id UUID PRIMARY KEY, owner_id VARCHAR(100) NOT NULL, version BIGINT NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    job_id UUID NOT NULL UNIQUE, image_id BIGINT NOT NULL UNIQUE REFERENCES rs_image(id),
    source_kind TEXT NOT NULL DEFAULT 'STANDARDIZED_IMAGE' CHECK(source_kind='STANDARDIZED_IMAGE'),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PUBLISHING','AVAILABLE','FAILED')),
    generation INTEGER NOT NULL DEFAULT 1 CHECK(generation BETWEEN 1 AND 3), current_attempt_id UUID,
    workspace TEXT NOT NULL, store_name TEXT NOT NULL, layer_name TEXT NOT NULL, style_version TEXT NOT NULL,
    verified_at TIMESTAMPTZ, last_error JSONB, request_receipts JSONB NOT NULL DEFAULT '{}',
    UNIQUE(id,owner_id), UNIQUE(workspace,layer_name), FOREIGN KEY(job_id,owner_id) REFERENCES ingest_job(id,owner_id),
    CHECK(status <> 'AVAILABLE' OR verified_at IS NOT NULL)
);
CREATE TABLE ingest_outbox (
    id UUID PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('INSPECT','PREFLIGHT','STANDARDIZE','VALIDATE','PUBLISH')),
    target_id UUID NOT NULL, generation INTEGER NOT NULL CHECK(generation BETWEEN 1 AND 3),
    dispatch_mode TEXT NOT NULL CHECK(dispatch_mode IN ('MQ','LOCAL')),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENDING','SENT','FAILED')),
    payload JSONB NOT NULL, send_count INTEGER NOT NULL DEFAULT 0 CHECK(send_count >= 0),
    max_sends INTEGER NOT NULL DEFAULT 5 CHECK(max_sends BETWEEN 1 AND 5), next_send_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sending_until TIMESTAMPTZ, sent_at TIMESTAMPTZ, last_error JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(kind,target_id,generation),
    CHECK((kind IN ('INSPECT','PREFLIGHT','STANDARDIZE') AND dispatch_mode='MQ') OR
          (kind IN ('VALIDATE','PUBLISH') AND dispatch_mode='LOCAL'))
);
CREATE TABLE ingest_attempt (
    id UUID PRIMARY KEY, owner_id VARCHAR(100) NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('UPLOAD','INSPECT','PREFLIGHT','STANDARDIZE','VALIDATE','PUBLISH')),
    product_id UUID, confirmation_id UUID, job_id UUID, publication_id UUID,
    generation INTEGER NOT NULL CHECK(generation BETWEEN 1 AND 3), work_id UUID UNIQUE REFERENCES ingest_outbox(id),
    claim_request_id UUID NOT NULL, slot_fence BIGINT NOT NULL CHECK(slot_fence > 0), heartbeat_seq BIGINT NOT NULL DEFAULT 0 CHECK(heartbeat_seq >= 0),
    input_product_id UUID, status TEXT NOT NULL DEFAULT 'RUNNING' CHECK(status IN ('RUNNING','RESULT_REPORTED','SUCCEEDED','FAILED')),
    executor_id TEXT NOT NULL, lease_until TIMESTAMPTZ NOT NULL, hard_deadline TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ, result_digest CHAR(64) CHECK(result_digest ~ '^[0-9a-f]{64}$'),
    result_receipt JSONB, error JSONB, stopped_at TIMESTAMPTZ, UNIQUE(id,owner_id),
    CHECK(num_nonnulls(product_id,confirmation_id,job_id,publication_id)=1),
    CHECK((kind IN ('UPLOAD','INSPECT') AND product_id IS NOT NULL) OR (kind='PREFLIGHT' AND confirmation_id IS NOT NULL) OR
          (kind IN ('STANDARDIZE','VALIDATE') AND job_id IS NOT NULL) OR (kind='PUBLISH' AND publication_id IS NOT NULL)),
    CHECK((kind='UPLOAD') = (work_id IS NULL)),
    FOREIGN KEY(product_id,owner_id) REFERENCES ingest_product(id,owner_id),
    FOREIGN KEY(confirmation_id,owner_id) REFERENCES ingest_confirmation(id,owner_id),
    FOREIGN KEY(job_id,owner_id) REFERENCES ingest_job(id,owner_id),
    FOREIGN KEY(publication_id,owner_id) REFERENCES ingest_publication(id,owner_id),
    FOREIGN KEY(input_product_id,owner_id) REFERENCES ingest_product(id,owner_id)
);
CREATE TABLE ingest_object (
    id UUID PRIMARY KEY, owner_id VARCHAR(100) NOT NULL, product_id UUID, job_id UUID, publication_id UUID, attempt_id UUID,
    role TEXT NOT NULL CHECK(role IN ('RAW','PREVIEW','TIFF','MANIFEST','MAP_COPY')),
    storage TEXT NOT NULL CHECK(storage IN ('MINIO','SHARED_MAP')), server_key TEXT NOT NULL, bucket TEXT,
    state TEXT NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','AVAILABLE','DELETE_PENDING','DELETED')),
    reserved_bytes BIGINT NOT NULL CHECK(reserved_bytes >= 0), actual_bytes BIGINT CHECK(actual_bytes >= 0 AND actual_bytes <= reserved_bytes),
    sha256 CHAR(64) CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    retention TEXT NOT NULL CHECK(retention IN ('RAW_7D','PREVIEW_7D','PERMANENT_ASSET','ORPHAN')),
    delete_after TIMESTAMPTZ, read_lease_until TIMESTAMPTZ, delete_attempts INTEGER NOT NULL DEFAULT 0 CHECK(delete_attempts >= 0), last_error JSONB,
    CHECK(num_nonnulls(product_id,job_id,publication_id) >= 1),
    CHECK((storage='MINIO' AND bucket IS NOT NULL) OR (storage='SHARED_MAP' AND bucket IS NULL)),
    CHECK(state <> 'AVAILABLE' OR (actual_bytes IS NOT NULL AND sha256 IS NOT NULL)),
    UNIQUE(id,owner_id), FOREIGN KEY(product_id,owner_id) REFERENCES ingest_product(id,owner_id),
    FOREIGN KEY(job_id,owner_id) REFERENCES ingest_job(id,owner_id),
    FOREIGN KEY(publication_id,owner_id) REFERENCES ingest_publication(id,owner_id),
    FOREIGN KEY(attempt_id,owner_id) REFERENCES ingest_attempt(id,owner_id)
);
CREATE UNIQUE INDEX ingest_object_location ON ingest_object(storage,COALESCE(bucket,''),server_key);
ALTER TABLE ingest_product ADD FOREIGN KEY(raw_object_id,owner_id) REFERENCES ingest_object(id,owner_id);
ALTER TABLE ingest_product ADD FOREIGN KEY(current_attempt_id,owner_id) REFERENCES ingest_attempt(id,owner_id);
ALTER TABLE ingest_confirmation ADD FOREIGN KEY(current_attempt_id,owner_id) REFERENCES ingest_attempt(id,owner_id);
ALTER TABLE ingest_job ADD FOREIGN KEY(current_attempt_id,owner_id) REFERENCES ingest_attempt(id,owner_id);
ALTER TABLE ingest_publication ADD FOREIGN KEY(current_attempt_id,owner_id) REFERENCES ingest_attempt(id,owner_id);
CREATE TABLE ingest_runtime_slot (
    slot_key TEXT PRIMARY KEY CHECK(slot_key IN ('COMPUTE','UPLOAD','QUOTA')),
    state TEXT NOT NULL DEFAULT 'IDLE' CHECK(state IN ('IDLE','HELD','RECOVERY_REQUIRED')),
    holder_ref TEXT, executor_id TEXT, lease_until TIMESTAMPTZ, hard_deadline TIMESTAMPTZ,
    fence BIGINT NOT NULL DEFAULT 0 CHECK(fence >= 0), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK(state <> 'HELD' OR (holder_ref IS NOT NULL AND executor_id IS NOT NULL AND lease_until IS NOT NULL AND hard_deadline IS NOT NULL))
);
INSERT INTO ingest_runtime_slot(slot_key) VALUES ('COMPUTE'),('UPLOAD'),('QUOTA');
CREATE INDEX ingest_product_owner_updated ON ingest_product(owner_id,updated_at DESC);
CREATE INDEX ingest_product_active ON ingest_product(status,upload_expires_at,expires_at);
CREATE INDEX ingest_confirmation_owner_updated ON ingest_confirmation(owner_id,updated_at DESC);
CREATE INDEX ingest_confirmation_active ON ingest_confirmation(status,expires_at);
CREATE INDEX ingest_job_owner_updated ON ingest_job(owner_id,updated_at DESC);
CREATE INDEX ingest_job_active ON ingest_job(status,updated_at);
CREATE INDEX ingest_publication_active ON ingest_publication(status,updated_at);
CREATE INDEX ingest_outbox_pending ON ingest_outbox(status,next_send_at,sending_until);
CREATE INDEX ingest_attempt_active ON ingest_attempt(status,lease_until,hard_deadline);
CREATE INDEX ingest_object_cleanup ON ingest_object(state,delete_after);
CREATE INDEX ingest_object_owner ON ingest_object(owner_id,role,state);
COMMIT;
