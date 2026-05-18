ALTER TABLE rs_admin_region
    ADD COLUMN IF NOT EXISTS adcode VARCHAR(20),
    ADD COLUMN IF NOT EXISTS source VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_version VARCHAR(50);

CREATE UNIQUE INDEX IF NOT EXISTS uk_rs_admin_region_adcode
    ON rs_admin_region (adcode)
    WHERE adcode IS NOT NULL;

