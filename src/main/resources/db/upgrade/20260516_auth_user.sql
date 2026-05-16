CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    role VARCHAR(50) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_user_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_sys_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

COMMENT ON TABLE sys_user IS '系统用户表，当前阶段用于登录和最小用户身份识别';
COMMENT ON COLUMN sys_user.password_hash IS 'BCrypt 加密后的密码哈希';

INSERT INTO sys_user (username, password_hash, display_name, role, status)
VALUES ('admin', '$2a$10$tw5K59OqDbfnjUkHIwUoZ..J5XW2S6nHLvsZ6TPUPupViAsg8dAZa', '管理员', 'ADMIN', 'ACTIVE')
ON CONFLICT (username) DO NOTHING;
