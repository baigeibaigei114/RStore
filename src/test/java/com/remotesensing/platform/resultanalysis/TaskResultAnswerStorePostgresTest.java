package com.remotesensing.platform.resultanalysis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Uses a newly initialized loopback database created explicitly for this test. */
@EnabledIfEnvironmentVariable(named = "RESULT_ANALYSIS_TEST_PORT", matches = "[0-9]+")
class TaskResultAnswerStorePostgresTest {
    @Test
    void migratesExistingAnswerAndReservesOnlyOneConcurrentNextTurn() throws Exception {
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
        assertEquals(1, store.find("owner", 7, first).number());
        assertTrue(store.history("another", 7, version, Integer.MAX_VALUE, 30).isEmpty());
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        Callable<Boolean> send = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return store.reserve("owner", 7, UUID.randomUUID(), version, "它的分母是多少", 90, 1);
        };
        try {
            var one = pool.submit(send);
            var two = pool.submit(send);
            assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        var page = store.history("owner", 7, version, Integer.MAX_VALUE, 30);
        assertEquals(2, page.size());
        assertEquals(2, page.get(0).number());
        assertEquals("RUNNING", page.get(0).status());
        assertFalse(store.reserve("owner", 7, UUID.randomUUID(), version, "继续", 90, 2));
        assertEquals(first, store.previousComplete("owner", 7, version, 2).requestId());
    }
}
