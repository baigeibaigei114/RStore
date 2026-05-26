CREATE TABLE IF NOT EXISTS rs_analysis_report (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    image_id BIGINT,
    owner_id VARCHAR(100) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    summary TEXT,
    report_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rs_analysis_report_task
        FOREIGN KEY (task_id) REFERENCES rs_task(id),
    CONSTRAINT fk_rs_analysis_report_image
        FOREIGN KEY (image_id) REFERENCES rs_image(id),
    CONSTRAINT ck_rs_analysis_report_type
        CHECK (report_type IN ('NDVI', 'NDWI', 'CHANGE_DETECTION', 'GENERAL'))
);

CREATE INDEX IF NOT EXISTS idx_rs_analysis_report_task_id
    ON rs_analysis_report(task_id);

CREATE INDEX IF NOT EXISTS idx_rs_analysis_report_owner_id
    ON rs_analysis_report(owner_id);

CREATE INDEX IF NOT EXISTS idx_rs_analysis_report_created_at
    ON rs_analysis_report(created_at);

COMMENT ON TABLE rs_analysis_report IS 'AI 生成的遥感任务分析报告表';
COMMENT ON COLUMN rs_analysis_report.task_id IS '关联处理任务 ID';
COMMENT ON COLUMN rs_analysis_report.image_id IS '关联影像 ID，可为空';
COMMENT ON COLUMN rs_analysis_report.owner_id IS '报告所属用户 ID';
COMMENT ON COLUMN rs_analysis_report.report_type IS '报告类型：NDVI、NDWI、CHANGE_DETECTION、GENERAL';
COMMENT ON COLUMN rs_analysis_report.summary IS '报告摘要文本';
COMMENT ON COLUMN rs_analysis_report.report_json IS 'AI 生成的结构化报告 JSON';
