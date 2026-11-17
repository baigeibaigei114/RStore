package com.remotesensing.platform.resultanalysis;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class TaskResultAnswerStore {
    private static final String COLUMNS = """
            SELECT request_id, result_version, turn_number, question, status, answer::text AS answer, error_message
            FROM task_result_answer
            """;

    private static final RowMapper<Turn> TURN = (row, index) -> {
        String status = row.getString("status");
        boolean unfinished = status.equals("RUNNING");
        return new Turn(row.getObject("request_id", UUID.class), row.getString("result_version"),
                row.getInt("turn_number"), row.getString("question"), unfinished ? "FAILED" : status, row.getString("answer"),
                unfinished ? "这条历史解读未完成，可查看任务统计" : row.getString("error_message"));
    };

    private final JdbcTemplate jdbc;

    TaskResultAnswerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    record Turn(UUID requestId, String version, int number, String question, String status, String answer, String error) {}
}
