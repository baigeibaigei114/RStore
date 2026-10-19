"""Exercise actual mapper SQL in PostgreSQL TEMP tables; never migrate real tables."""
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MAPPERS = ROOT / "src/main/java/com/remotesensing/platform/mapper"


def xml_sql(name):
    tree = ET.parse(ROOT / "src/main/resources/mapper/RsTaskMapper.xml")
    return "".join(tree.find(f"./update[@id='{name}']").itertext())


def annotation_sql(file, method):
    source = (MAPPERS / file).read_text(encoding="utf-8")
    pattern = r'@org\.apache\.ibatis\.annotations\.(?:Select|Update)\("""(.*?)"""\)\s+[\w.<>]+\s+(\w+)\('
    return next(sql for sql, name in re.findall(pattern, source, re.S) if name == method)


def bind(sql, **values):
    def literal(match):
        value = values[match.group(1)]
        return str(value) if isinstance(value, int) else "'" + value.replace("'", "''") + "'"
    return re.sub(r"#\{(\w+)(?:,[^}]*)?\}", literal, sql).strip().rstrip(";")


def check_count(sql, expected):
    return f"""
    DO $test$ DECLARE affected integer; BEGIN
      {sql};
      GET DIAGNOSTICS affected = ROW_COUNT;
      IF affected <> {expected} THEN RAISE EXCEPTION 'unexpected affected rows: %', affected; END IF;
    END $test$;
    """


def assert_true(expression):
    return f"DO $test$ BEGIN IF NOT ({expression}) THEN RAISE EXCEPTION 'assertion failed'; END IF; END $test$;"


def main():
    claim = xml_sql("claimForRunning")
    fail = xml_sql("markFailedIfActive")
    renew = annotation_sql("RsTaskMapper.java", "renewLease")
    expired = annotation_sql("RsTaskMapper.java", "selectExpiredTasks")
    exhausted = annotation_sql("MessageOutboxMapper.java", "selectExhausted")
    exhaust = annotation_sql("MessageOutboxMapper.java", "exhaustSending")
    statements = ["""
    BEGIN;
    SET LOCAL lock_timeout = '2s';
    SET LOCAL statement_timeout = '10s';
    CREATE TEMP TABLE rs_task (
      id bigint PRIMARY KEY, status text, attempt_id text, attempt_count integer DEFAULT 0,
      max_retry_count integer DEFAULT 2, retry_count integer DEFAULT 0, lease_until timestamptz,
      output_object_key text, started_at timestamptz, finished_at timestamptz,
      updated_at timestamptz, error_message text
    ) ON COMMIT DROP;
    CREATE TEMP TABLE message_outbox (
      id bigint PRIMARY KEY, aggregate_id bigint, payload jsonb DEFAULT '{}',
      status text, retry_count integer, max_retry_count integer,
      next_retry_at timestamptz, updated_at timestamptz, error_message text
    ) ON COMMIT DROP;
    """, assert_true("(SELECT relnamespace = pg_my_temp_schema() FROM pg_class WHERE oid='rs_task'::regclass)"),
    "INSERT INTO rs_task(id,status) VALUES (1,'PENDING'),(2,'SUCCESS'),(3,'PENDING');"]
    statements += [
        check_count(bind(claim, id=1, attemptId="a", outputObjectKey="result/a.tif", leaseSeconds=120), 1),
        check_count(bind(claim, id=1, attemptId="b", outputObjectKey="result/b.tif", leaseSeconds=120), 0),
        check_count(bind(renew, id=1, attemptId="old", seconds=120), 0),
        check_count(bind(renew, id=1, attemptId="a", seconds=120), 1),
        check_count(bind(fail, id=1, errorMessage="late publish failure"), 0),
        "UPDATE rs_task SET status='RETRYING' WHERE id=1;",
        check_count(bind(claim, id=1, attemptId="b", outputObjectKey="result/b.tif", leaseSeconds=120), 1),
        assert_true("(SELECT attempt_count=2 AND attempt_id='b' AND output_object_key='result/b.tif' FROM rs_task WHERE id=1)"),
        check_count(bind(renew, id=1, attemptId="a", seconds=120), 0),
        "UPDATE rs_task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=1;",
        check_count(bind(renew, id=1, attemptId="b", seconds=120), 0),
        f"CREATE TEMP TABLE expired_ids ON COMMIT DROP AS {expired};",
        assert_true("(SELECT count(*)=1 AND min(id)=1 FROM expired_ids)"),
        check_count(bind(fail, id=1, errorMessage="lease expired"), 1),
        check_count(bind(fail, id=2, errorMessage="late failure"), 0),
        "UPDATE rs_task SET status='RETRYING' WHERE id=1;",
        check_count(bind(claim, id=1, attemptId="c", outputObjectKey="result/c.tif", leaseSeconds=120), 0),
        """INSERT INTO message_outbox(id,aggregate_id,status,retry_count,max_retry_count,next_retry_at)
        VALUES (1,1,'SENDING',2,2,clock_timestamp()-interval '1 second'),
               (2,2,'SENT',2,2,clock_timestamp()-interval '1 second'),
               (3,3,'FAILED',2,2,clock_timestamp()-interval '1 second');""",
        f"CREATE TEMP TABLE exhausted_rows ON COMMIT DROP AS {exhausted};",
        assert_true("(SELECT count(*)=2 FROM exhausted_rows)"),
        check_count(bind(exhaust, id=1), 1),
        check_count(bind(exhaust, id=1), 0),
        check_count(bind(fail, id=3, errorMessage="outbox exhausted"), 1),
        "ROLLBACK;",
    ]
    subprocess.run(
        ["docker", "exec", "-i", "rs-postgres", "psql", "-U", "postgres",
         "-d", "rs_image_asset", "-v", "ON_ERROR_STOP=1"],
        input="\n".join(statements), text=True, encoding="utf-8", check=True,
    )
    print("Lease SQL checks passed (TEMP tables only; rolled back).")


if __name__ == "__main__":
    main()
