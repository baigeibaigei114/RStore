package com.remotesensing.platform.resultanalysis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Uses a newly initialized loopback database created explicitly for this test. */
@EnabledIfEnvironmentVariable(named = "RESULT_ANALYSIS_TEST_PORT", matches = "[0-9]+")
class TaskResultAnswerStorePostgresTest {
    @Test
    void migratesSavedTurnsAndReadsOwnerScopedHistoryWithoutMutatingUnfinishedRows() throws Exception {
        String port = System.getenv("RESULT_ANALYSIS_TEST_PORT");
        var source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/postgres",
                "result_analysis_test", System.getenv("RESULT_ANALYSIS_TEST_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE rs_task (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO rs_task VALUES (7)");
        Path upgrades = Path.of("src/main/resources/db/upgrade");
        jdbc.execute(Files.readString(upgrades.resolve("20261111_result_analysis.sql")));
        UUID first = UUID.randomUUID();
        String version = "a".repeat(64);
        jdbc.update("""
                INSERT INTO task_result_answer
                    (owner_id,task_id,request_id,result_version,question,status,answer,expires_at)
                VALUES ('owner',7,?,?,'比例是多少','COMPLETE','{"kind":"VALID_PIXELS"}',CURRENT_TIMESTAMP)
                """, first, version);
        jdbc.execute(Files.readString(upgrades.resolve("20261111_result_analysis_turns.sql")));
        jdbc.execute(Files.readString(upgrades.resolve("20261111_result_analysis_turns.sql")));

        var store = new TaskResultAnswerStore(jdbc);
        assertEquals(1, store.history("owner", 7, version, Integer.MAX_VALUE, 30).get(0).number());
        assertTrue(store.history("another", 7, version, Integer.MAX_VALUE, 30).isEmpty());
        assertTrue(store.versions("another", 7).isEmpty());
        jdbc.update("""
                INSERT INTO task_result_answer
                    (owner_id,task_id,request_id,result_version,question,status,expires_at,turn_number)
                VALUES ('owner',7,?,?,'未完成的历史问题','RUNNING',CURRENT_TIMESTAMP + INTERVAL '1 hour',2)
                """, UUID.randomUUID(), version);
        var page = store.history("owner", 7, version, Integer.MAX_VALUE, 30);
        assertEquals(2, page.size());
        assertEquals(2, page.get(0).number());
        assertEquals("FAILED", page.get(0).status());
        assertTrue(page.get(0).error().contains("历史解读未完成"));
        assertEquals(first, store.history("owner", 7, version, 2, 30).get(0).requestId());
        assertEquals(java.util.List.of(version), store.versions("owner", 7));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM task_result_answer WHERE turn_number=2", String.class));
    }
}
