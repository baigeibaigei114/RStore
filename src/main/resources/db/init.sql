-- 遥感影像智能解译与时空资产管理平台 - 第一版数据库结构
-- 数据库：PostgreSQL + PostGIS

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

CREATE TABLE IF NOT EXISTS rs_image (
    id BIGSERIAL PRIMARY KEY,
    image_code VARCHAR(64) NOT NULL UNIQUE,
    image_name VARCHAR(255) NOT NULL,
    sensor_type VARCHAR(100),
    satellite_name VARCHAR(100),
    acquisition_time TIMESTAMPTZ,
    cloud_percent NUMERIC(5, 2),
    resolution_meter NUMERIC(10, 4),
    band_count INTEGER,
    projection VARCHAR(100),
    width INTEGER,
    height INTEGER,
    file_format VARCHAR(50) NOT NULL DEFAULT 'GeoTIFF',
    file_size BIGINT,
    content_type VARCHAR(100),
    metadata_json JSONB,
    minio_bucket VARCHAR(100) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    thumbnail_object_key VARCHAR(500),
    thumbnail_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    thumbnail_error_message TEXT,
    overview_object_key VARCHAR(500),
    footprint geometry(Polygon, 4326),
    center_lon NUMERIC(10, 6),
    center_lat NUMERIC(10, 6),
    status VARCHAR(30) NOT NULL DEFAULT 'READY',
    description TEXT,
    deleted_at TIMESTAMPTZ,
    deleted_by VARCHAR(100),
    deleted_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rs_image_cloud_percent CHECK (cloud_percent IS NULL OR (cloud_percent >= 0 AND cloud_percent <= 100)),
    CONSTRAINT ck_rs_image_band_count CHECK (band_count IS NULL OR band_count > 0),
    CONSTRAINT ck_rs_image_size CHECK (
        (width IS NULL OR width > 0)
        AND (height IS NULL OR height > 0)
        AND (file_size IS NULL OR file_size >= 0)
    ),
    CONSTRAINT ck_rs_image_status CHECK (status IN ('UPLOADING', 'PARSING', 'READY', 'PROCESSING', 'DELETE_LOCKED', 'DELETED', 'FAILED')),
    CONSTRAINT ck_rs_image_thumbnail_status CHECK (thumbnail_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED'))
);

COMMENT ON TABLE rs_image IS '影像资产表，保存 GeoTIFF 影像元数据、对象存储路径和空间范围';
COMMENT ON COLUMN rs_image.image_code IS '影像唯一编码，用于业务侧稳定引用';
COMMENT ON COLUMN rs_image.object_key IS 'MinIO 中原始影像文件对象路径';
COMMENT ON COLUMN rs_image.thumbnail_object_key IS 'MinIO 中 PNG 缩略图对象路径';
COMMENT ON COLUMN rs_image.thumbnail_status IS '缩略图生成状态：PENDING、RUNNING、SUCCESS、FAILED、SKIPPED';
COMMENT ON COLUMN rs_image.thumbnail_error_message IS '缩略图生成失败或跳过原因';
COMMENT ON COLUMN rs_image.content_type IS '文件 MIME 类型';
COMMENT ON COLUMN rs_image.metadata_json IS 'GeoTIFF 解析得到的完整元数据 JSON';
COMMENT ON COLUMN rs_image.overview_object_key IS 'MinIO 中影像缩略图或概览文件对象路径';
COMMENT ON COLUMN rs_image.footprint IS '影像覆盖范围，WGS84 坐标系 Polygon；上传后可为空，解析元数据后补全';
COMMENT ON COLUMN rs_image.status IS '影像资产状态：UPLOADING、PARSING、READY、PROCESSING、DELETE_LOCKED、DELETED、FAILED';
COMMENT ON COLUMN rs_image.deleted_at IS '软删除时间；为空表示资产正常可见';
COMMENT ON COLUMN rs_image.deleted_by IS '执行软删除的用户标识，当前阶段可为空';
COMMENT ON COLUMN rs_image.deleted_reason IS '软删除原因';

CREATE INDEX IF NOT EXISTS idx_rs_image_footprint_gist ON rs_image USING GIST (footprint);
CREATE INDEX IF NOT EXISTS idx_rs_image_acquisition_time ON rs_image (acquisition_time);
CREATE INDEX IF NOT EXISTS idx_rs_image_sensor_type ON rs_image (sensor_type);
CREATE INDEX IF NOT EXISTS idx_rs_image_object_location ON rs_image (minio_bucket, object_key);
CREATE INDEX IF NOT EXISTS idx_rs_image_thumbnail_object_key ON rs_image (thumbnail_object_key);
CREATE INDEX IF NOT EXISTS idx_rs_image_thumbnail_status ON rs_image (thumbnail_status);
CREATE INDEX IF NOT EXISTS idx_rs_image_deleted_at ON rs_image (deleted_at);
CREATE INDEX IF NOT EXISTS idx_rs_image_status ON rs_image (status);

CREATE TABLE IF NOT EXISTS rs_admin_region (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    level VARCHAR(50) NOT NULL,
    parent_id BIGINT,
    geom geometry(MultiPolygon, 4326) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rs_admin_region_parent FOREIGN KEY (parent_id) REFERENCES rs_admin_region (id)
);

COMMENT ON TABLE rs_admin_region IS '行政区表，保存行政区层级和空间范围';
COMMENT ON COLUMN rs_admin_region.name IS '行政区名称';
COMMENT ON COLUMN rs_admin_region.level IS '行政区级别，例如 province、city、district';
COMMENT ON COLUMN rs_admin_region.parent_id IS '上级行政区 ID';
COMMENT ON COLUMN rs_admin_region.geom IS '行政区空间范围，WGS84 坐标系 MultiPolygon';

CREATE INDEX IF NOT EXISTS idx_rs_admin_region_parent_id ON rs_admin_region (parent_id);
CREATE INDEX IF NOT EXISTS idx_rs_admin_region_level ON rs_admin_region (level);
CREATE INDEX IF NOT EXISTS idx_rs_admin_region_geom_gist ON rs_admin_region USING GIST (geom);

CREATE TABLE IF NOT EXISTS rs_task (
    id BIGSERIAL PRIMARY KEY,
    task_code VARCHAR(64) NOT NULL UNIQUE,
    image_id BIGINT NOT NULL,
    task_type VARCHAR(50) NOT NULL,
    task_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    priority INTEGER NOT NULL DEFAULT 5,
    progress INTEGER NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retry_count INTEGER NOT NULL DEFAULT 3,
    output_bucket VARCHAR(100),
    output_object_key VARCHAR(500),
    params JSONB,
    error_message TEXT,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rs_task_image FOREIGN KEY (image_id) REFERENCES rs_image (id),
    CONSTRAINT ck_rs_task_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'RETRYING', 'CANCELED')),
    CONSTRAINT ck_rs_task_priority CHECK (priority >= 0 AND priority <= 10),
    CONSTRAINT ck_rs_task_progress CHECK (progress >= 0 AND progress <= 100),
    CONSTRAINT ck_rs_task_retry CHECK (retry_count >= 0 AND max_retry_count >= 0)
);

COMMENT ON TABLE rs_task IS '遥感处理任务表，记录影像处理、智能解译等异步任务';
COMMENT ON COLUMN rs_task.task_type IS '任务类型，例如 PREPROCESS、CLASSIFICATION、DETECTION、CHANGE_DETECTION';
COMMENT ON COLUMN rs_task.status IS '任务状态：PENDING、RUNNING、SUCCESS、FAILED、RETRYING、CANCELED';
COMMENT ON COLUMN rs_task.output_bucket IS '任务结果文件输出 bucket';
COMMENT ON COLUMN rs_task.output_object_key IS '任务结果文件输出对象路径';
COMMENT ON COLUMN rs_task.params IS '任务参数，使用 JSONB 保存模型参数、裁剪范围等扩展配置';

CREATE INDEX IF NOT EXISTS idx_rs_task_image_id ON rs_task (image_id);
CREATE INDEX IF NOT EXISTS idx_rs_task_status ON rs_task (status);
CREATE INDEX IF NOT EXISTS idx_rs_task_type ON rs_task (task_type);
CREATE INDEX IF NOT EXISTS idx_rs_task_submitted_at ON rs_task (submitted_at);
CREATE INDEX IF NOT EXISTS idx_rs_task_output_object_key ON rs_task (output_object_key);

CREATE TABLE IF NOT EXISTS rs_task_log (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    log_level VARCHAR(20) NOT NULL DEFAULT 'INFO',
    message TEXT NOT NULL,
    detail JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rs_task_log_task FOREIGN KEY (task_id) REFERENCES rs_task (id) ON DELETE CASCADE,
    CONSTRAINT ck_rs_task_log_level CHECK (log_level IN ('DEBUG', 'INFO', 'WARN', 'ERROR'))
);

COMMENT ON TABLE rs_task_log IS '任务日志表，按时间记录任务执行过程、错误和关键节点';
COMMENT ON COLUMN rs_task_log.detail IS '结构化日志详情，例如耗时、处理进度、异常堆栈摘要';

CREATE INDEX IF NOT EXISTS idx_rs_task_log_task_id ON rs_task_log (task_id);
CREATE INDEX IF NOT EXISTS idx_rs_task_log_created_at ON rs_task_log (created_at);

CREATE TABLE IF NOT EXISTS rs_result_file (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    image_id BIGINT,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(50) NOT NULL,
    minio_bucket VARCHAR(100) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    file_size BIGINT,
    mime_type VARCHAR(100),
    checksum VARCHAR(128),
    result_metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rs_result_file_task FOREIGN KEY (task_id) REFERENCES rs_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_rs_result_file_image FOREIGN KEY (image_id) REFERENCES rs_image (id),
    CONSTRAINT ck_rs_result_file_size CHECK (file_size IS NULL OR file_size >= 0)
);

COMMENT ON TABLE rs_result_file IS '处理结果文件表，记录任务输出文件及其对象存储位置';
COMMENT ON COLUMN rs_result_file.file_type IS '结果文件类型，例如 GEOTIFF、SHAPEFILE、GEOJSON、PNG、JSON';
COMMENT ON COLUMN rs_result_file.object_key IS 'MinIO 中结果文件对象路径';
COMMENT ON COLUMN rs_result_file.result_metadata IS '结果文件扩展元数据，例如类别统计、空间范围、模型版本';

CREATE INDEX IF NOT EXISTS idx_rs_result_file_task_id ON rs_result_file (task_id);
CREATE INDEX IF NOT EXISTS idx_rs_result_file_image_id ON rs_result_file (image_id);
CREATE INDEX IF NOT EXISTS idx_rs_result_file_object_location ON rs_result_file (minio_bucket, object_key);

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
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_message_outbox_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_message_outbox_retry CHECK (retry_count >= 0 AND max_retry_count > 0)
);

COMMENT ON TABLE message_outbox IS '可靠消息 outbox 表，用于补偿数据库提交后消息未发送的任务消息';
COMMENT ON COLUMN message_outbox.aggregate_type IS '业务聚合类型，当前任务消息固定为 RS_TASK';
COMMENT ON COLUMN message_outbox.aggregate_id IS '业务聚合 ID，当前对应 rs_task.id';
COMMENT ON COLUMN message_outbox.payload IS '待投递到 RabbitMQ 的 JSON 消息体';

CREATE UNIQUE INDEX IF NOT EXISTS idx_message_outbox_aggregate ON message_outbox (aggregate_type, aggregate_id);
CREATE INDEX IF NOT EXISTS idx_message_outbox_status_retry ON message_outbox (status, next_retry_at);
