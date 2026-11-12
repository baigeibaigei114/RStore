BEGIN;

ALTER TABLE task_result_answer ADD COLUMN IF NOT EXISTS turn_number INTEGER;

WITH ordered AS (
    SELECT owner_id, task_id, request_id,
           row_number() OVER (
               PARTITION BY owner_id, task_id, result_version
               ORDER BY created_at, request_id
           ) AS position
    FROM task_result_answer
)
UPDATE task_result_answer AS answer
SET turn_number = ordered.position
FROM ordered
WHERE answer.owner_id = ordered.owner_id AND answer.task_id = ordered.task_id
  AND answer.request_id = ordered.request_id AND answer.turn_number IS NULL;

ALTER TABLE task_result_answer ALTER COLUMN turn_number SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_task_result_answer_turn
    ON task_result_answer (owner_id, task_id, result_version, turn_number);

COMMIT;
