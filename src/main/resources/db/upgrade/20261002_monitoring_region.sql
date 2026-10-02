-- 增量迁移：不修改原有行政区、影像或任务数据。
BEGIN;
CREATE TABLE IF NOT EXISTS monitoring_region (
    id BIGSERIAL PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    geom geometry(Polygon, 4326) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT monitoring_region_valid CHECK (
        ST_IsValid(geom) AND NOT ST_IsEmpty(geom) AND ST_Area(geom) > 0
    )
);
CREATE INDEX IF NOT EXISTS idx_monitoring_region_owner ON monitoring_region(owner_id, id);
COMMIT;
