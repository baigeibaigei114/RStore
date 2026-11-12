#!/bin/sh
set -eu

# This runs only during initialization of the new, isolated demo database.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v demo_password="$DEMO_PASSWORD" -v app_password="$APP_DB_PASSWORD" \
    -v app_database="$POSTGRES_DB" <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE sys_user SET status='DISABLED' WHERE username='admin';
INSERT INTO sys_user(username,password_hash,display_name,role,status)
VALUES ('demo',crypt(:'demo_password',gen_salt('bf',10)),'面试演示','USER','ACTIVE')
ON CONFLICT (username) DO NOTHING;
SELECT format('CREATE ROLE rsapp LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='rsapp') \gexec
GRANT CONNECT ON DATABASE :"app_database" TO rsapp;
GRANT USAGE ON SCHEMA public TO rsapp;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO rsapp;
GRANT USAGE,SELECT,UPDATE ON ALL SEQUENCES IN SCHEMA public TO rsapp;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO rsapp;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT,UPDATE ON SEQUENCES TO rsapp;
SQL
