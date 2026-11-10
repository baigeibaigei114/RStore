"""Explicit additive knowledge provisioning, run once after the target backup and PG extension upgrade."""
import json
from pathlib import Path

import psycopg
from psycopg import sql


def main():
    base = Path('/run/provision')
    backup = json.loads((base / 'backup.json').read_text())
    assert backup['database'] == 'rs_demo' and backup['status'] == 'complete'
    admin = json.loads((base / 'feature-admin.json').read_text())
    credentials = dict(line.split('=', 1) for line in (base / 'secrets.env').read_text().splitlines() if '=' in line)
    with psycopg.connect(host='postgres', dbname='rs_demo', user='rsdemo',
                         password=credentials['POSTGRES_PASSWORD'], autocommit=True) as conn:
        assert conn.execute('SELECT current_database(),current_user').fetchone() == ('rs_demo', 'rsdemo')
        assert conn.execute("SELECT to_regnamespace('agent_knowledge')").fetchone()[0] is None
        roles = [('rs_agent_reader', 'readerPassword', 'SELECT'),
                 ('rs_agent_indexer', 'indexerPassword', 'SELECT,INSERT,UPDATE')]
        for name, _, _ in roles:
            assert not conn.execute('SELECT 1 FROM pg_roles WHERE rolname=%s', (name,)).fetchone()
        conn.execute(Path('/app/sql/001_knowledge.sql').read_text())
        for name, key, permissions in roles:
            conn.execute(sql.SQL('CREATE ROLE {} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD {}')
                         .format(sql.Identifier(name), sql.Literal(admin[key])))
            conn.execute(sql.SQL('GRANT CONNECT ON DATABASE rs_demo TO {}').format(sql.Identifier(name)))
            conn.execute(sql.SQL('GRANT USAGE ON SCHEMA agent_knowledge TO {}').format(sql.Identifier(name)))
            conn.execute(sql.SQL('GRANT {} ON ALL TABLES IN SCHEMA agent_knowledge TO {}')
                         .format(sql.SQL(permissions), sql.Identifier(name)))
            assert not conn.execute("SELECT has_table_privilege(%s,'public.rs_image','SELECT')", (name,)).fetchone()[0]
            assert not conn.execute("SELECT has_table_privilege(%s,'public.rs_task','INSERT')", (name,)).fetchone()[0]
        assert not conn.execute("SELECT has_table_privilege('rs_agent_reader','agent_knowledge.knowledge_index','UPDATE')").fetchone()[0]
        vector_version = conn.execute("SELECT extversion FROM pg_extension WHERE extname='vector'").fetchone()[0]
        assert vector_version == '0.8.6'
    print(json.dumps({'database': 'rs_demo', 'vectorVersion': vector_version,
                      'knowledgeRolesCreated': 2, 'businessAccessDenied': True, 'paidCalls': 0}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit('Knowledge provisioning failed: ' + type(error).__name__) from None
