package com.remotesensing.platform.ingestion.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Parameterized, ingestion-only SQL; callers supply static SQL, never browser SQL identifiers. */
@Repository
public class IngestionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final TransactionTemplate independent;

    public IngestionStore(DataSource source, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(5);
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.independent = new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T transaction(Supplier<T> body) { return tx.execute(status -> body.get()); }
    public <T> T independent(Supplier<T> body) { return independent.execute(status -> body.get()); }
    public int update(String sql, Object... parameters) { return jdbc.update(sql, parameters); }

    public List<ObjectNode> rows(String select, Object... parameters) {
        return jdbc.query("SELECT row_to_json(ingestion_row)::text FROM (" + select + ") ingestion_row",
                (rs, index) -> parse(rs.getString(1)), parameters);
    }

    public ObjectNode one(String select, Object... parameters) {
        List<ObjectNode> result = rows(select, parameters);
        if (result.size() > 1) throw new IllegalStateException("Non-unique ingestion query");
        return result.isEmpty() ? null : result.get(0);
    }

    public long count(String select, Object... parameters) { return jdbc.queryForObject(select, Long.class, parameters); }
    public Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant(); }
    public ObjectNode parse(String value) {
        try { return (ObjectNode) json.readTree(value); }
        catch (java.io.IOException error) { throw new IllegalStateException("Invalid stored ingestion document", error); }
    }
    public ObjectNode object() { return json.createObjectNode(); }
    public JsonNode tree(Object value) { return json.valueToTree(value); }
    public static UUID id(JsonNode row) { return UUID.fromString(row.path("id").asText()); }
    public static Instant time(JsonNode row, String field) {
        return java.time.OffsetDateTime.parse(row.path(field).asText()).toInstant();
    }
}
