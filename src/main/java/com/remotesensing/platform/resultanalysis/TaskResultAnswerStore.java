package com.remotesensing.platform.resultanalysis;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class TaskResultAnswerStore {
    private static final String COLUMNS = """
            SELECT request_id, result_version, question, status, answer::text, error_message,
                   expires_at <= CURRENT_TIMESTAMP AS expired
            FROM task_result_answer
            """;

    private static final RowMapper<Turn> TURN = (row, index) -> {
        String status = row.getString("status");
        boolean expired = status.equals("RUNNING") && row.getBoolean("expired");
        return new Turn(row.getObject("request_id", UUID.class), row.getString("result_version"),
                row.getString("question"), expired ? "FAILED" : status, row.getString("answer"),
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

    Turn latest(String owner, long taskId, String version) {
        return jdbc.query(COLUMNS + """
                 WHERE owner_id=? AND task_id=? AND result_version=?
                 ORDER BY created_at DESC, request_id DESC LIMIT 1
                """, TURN, owner, taskId, version).stream().findFirst().orElse(null);
    }

    boolean reserve(String owner, long taskId, UUID requestId, String version, String question, int seconds) {
        return jdbc.update("""
                INSERT INTO task_result_answer
                    (owner_id, task_id, request_id, result_version, question, status, expires_at)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP + ? * INTERVAL '1 second')
                ON CONFLICT (owner_id, task_id, request_id) DO NOTHING
                """, owner, taskId, requestId, version, question, seconds) == 1;
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

    record Turn(UUID requestId, String version, String question, String status, String answer, String error) {}
}
