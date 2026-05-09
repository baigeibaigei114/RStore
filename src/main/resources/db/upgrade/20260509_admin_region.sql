-- 行政区空间范围表，用于按行政区查询相交影像。
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
