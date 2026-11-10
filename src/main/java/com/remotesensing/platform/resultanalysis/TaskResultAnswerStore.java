package com.remotesensing.platform.resultanalysis;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class TaskResultAnswerStore {
    private static final String COLUMNS = """
            SELECT request_id, result_version, turn_number, question, status, answer::text AS answer, error_message,
                   expires_at <= CURRENT_TIMESTAMP AS expired
            FROM task_result_answer
            """;

    private static final RowMapper<Turn> TURN = (row, index) -> {
        String status = row.getString("status");
        boolean expired = status.equals("RUNNING") && row.getBoolean("expired");
        return new Turn(row.getObject("request_id", UUID.class), row.getString("result_version"),
                row.getInt("turn_number"), row.getString("question"), expired ? "FAILED" : status, row.getString("answer"),
                expired ? "本次解读已超时，可以重新提问" : row.getString("error_message"));
    };

    private final JdbcTemplate jdbc;

    TaskResultAnswerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Turn find(String owner, long taskId, UUID requestId) {
        return jdbc.query(COLUMNS + " WHERE owner_id=? AND task_id=? AND request_id=?",
                TURN, owner, taskId, requestId).stream().findFirst().orElse(null);
    }

    List<Turn> history(String owner, long taskId, String version, int beforeTurn, int limit) {
        return jdbc.query(COLUMNS + """
                 WHERE owner_id=? AND task_id=? AND result_version=? AND turn_number < ?
                 ORDER BY turn_number DESC LIMIT ?
                """, TURN, owner, taskId, version, beforeTurn, limit);
    }

    List<String> versions(String owner, long taskId) {
        return jdbc.queryForList("""
                SELECT result_version FROM task_result_answer WHERE owner_id=? AND task_id=?
                GROUP BY result_version ORDER BY MAX(created_at) DESC, result_version LIMIT 50
                """, String.class, owner, taskId);
    }

    Turn previousComplete(String owner, long taskId, String version, int expectedTurn) {
        return jdbc.query(COLUMNS + """
                 WHERE owner_id=? AND task_id=? AND result_version=? AND turn_number <= ? AND status='COMPLETE'
                 ORDER BY turn_number DESC LIMIT 1
                """, TURN, owner, taskId, version, expectedTurn).stream().findFirst().orElse(null);
    }

    boolean reserve(String owner, long taskId, UUID requestId, String version, String question, int seconds, int expectedTurn) {
        return jdbc.update("""
                WITH head AS (
                    SELECT turn_number, status, expires_at FROM task_result_answer
                    WHERE owner_id=? AND task_id=? AND result_version=?
                    ORDER BY turn_number DESC LIMIT 1
                )
                INSERT INTO task_result_answer
                    (owner_id, task_id, request_id, result_version, question, status, expires_at, turn_number)
                SELECT ?, ?, ?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP + ? * INTERVAL '1 second', ?
                WHERE COALESCE((SELECT turn_number FROM head), 0)=?
                  AND NOT EXISTS (SELECT 1 FROM head WHERE status='RUNNING' AND expires_at>CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
                """, owner, taskId, version, owner, taskId, requestId, version, question, seconds,
                expectedTurn + 1, expectedTurn) == 1;
    }

    void complete(String owner, long taskId, UUID requestId, String answer) {
        jdbc.update("""
                UPDATE task_result_answer SET status='COMPLETE', answer=CAST(? AS jsonb)
                WHERE owner_id=? AND task_id=? AND request_id=? AND status='RUNNING'
                  AND expires_at > CURRENT_TIMESTAMP
                """, answer, owner, taskId, requestId);
    }

    void fail(String owner, long taskId, UUID requestId, String message) {
        jdbc.update("""
                UPDATE task_result_answer SET status='FAILED', error_message=?
                WHERE owner_id=? AND task_id=? AND request_id=? AND status='RUNNING'
                  AND expires_at > CURRENT_TIMESTAMP
                """, message, owner, taskId, requestId);
    }

    record Turn(UUID requestId, String version, int number, String question, String status, String answer, String error) {}
}
