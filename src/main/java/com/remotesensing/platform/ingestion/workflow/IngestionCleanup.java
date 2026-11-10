package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable cleanup claims keep slow storage operations outside database transactions. */
@Service
public class IngestionCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(IngestionCleanup.class);
    private static final int BATCH_SIZE = 8;
    private static final String EXPIRED_PRODUCT_OBJECT = """
            o.state IN ('AVAILABLE','DELETE_PENDING') AND o.storage='MINIO'
            AND ((o.role='RAW' AND o.retention='RAW_7D' AND p.raw_object_id=o.id AND p.raw_state=o.state)
                 OR (o.role='PREVIEW' AND o.retention='PREVIEW_7D'))
            AND p.expires_at<=clock_timestamp() AND p.status NOT IN ('INSPECT_QUEUED','INSPECTING')
            AND NOT EXISTS (SELECT 1 FROM ingest_job j WHERE j.owner_id=p.owner_id AND j.active_product_id=p.id
                            AND j.status IN ('QUEUED','RUNNING','VALIDATING'))
            AND NOT EXISTS (SELECT 1 FROM ingest_attempt a WHERE a.owner_id=p.owner_id AND a.input_product_id=p.id
                            AND a.kind IN ('INSPECT','STANDARDIZE') AND a.stopped_at IS NULL
                            AND a.status IN ('RUNNING','FAILED'))
            """;
    private static final String DUE = """
            o.delete_after<=clock_timestamp()
            AND (o.read_lease_until IS NULL OR o.read_lease_until<=clock_timestamp())
            """;
    private static final String PRODUCT_JOIN = """
            JOIN ingest_product p ON p.id=o.product_id AND p.owner_id=o.owner_id
            """;
    private static final String ORPHAN_OBJECT = """
            o.retention='ORPHAN' AND o.state IN ('RESERVED','AVAILABLE','DELETE_PENDING') AND o.storage='MINIO'
            AND o.role IN ('RAW','PREVIEW','TIFF','MANIFEST')
            AND NOT (o.role='RAW' AND p.raw_object_id IS NOT DISTINCT FROM o.id AND p.raw_state IN ('RESERVED','AVAILABLE','DELETE_PENDING'))
            AND EXISTS (
                SELECT 1 FROM ingest_attempt a
                WHERE a.id=o.attempt_id AND a.owner_id=o.owner_id AND a.status='FAILED'
                AND a.finished_at IS NOT NULL AND a.stopped_at IS NOT NULL
                AND GREATEST(a.finished_at,a.stopped_at)+interval '24 hours'<=clock_timestamp()
                AND (o.job_id IS NULL OR (
                    NOT EXISTS (SELECT 1 FROM ingest_job j WHERE j.id=o.job_id AND j.owner_id=o.owner_id
                                AND j.status IN ('QUEUED','RUNNING','VALIDATING') AND j.generation=a.generation)
                    AND NOT EXISTS (SELECT 1 FROM ingest_attempt v WHERE v.job_id=o.job_id AND v.owner_id=o.owner_id
                                    AND v.kind='VALIDATE' AND v.generation=a.generation AND v.stopped_at IS NULL
                                    AND v.status IN ('RUNNING','FAILED'))
                ))
            )
            AND NOT EXISTS (
                SELECT 1 FROM ingest_job j WHERE j.id=o.job_id AND j.owner_id=o.owner_id AND j.status<>'FAILED'
                AND (j.candidate_artifacts->>'standardizeAttemptId'=o.attempt_id::text
                     OR EXISTS (SELECT 1 FROM jsonb_array_elements(
                           CASE WHEN jsonb_typeof(j.candidate_artifacts->'outputs')='array'
                                THEN j.candidate_artifacts->'outputs' ELSE '[]'::jsonb END
                     ) output WHERE output->>'objectId'=o.id::text))
            )
            AND NOT EXISTS (SELECT 1 FROM rs_image i WHERE i.minio_bucket=o.bucket AND i.object_key=o.server_key)
            """;
    private static final String ELIGIBLE = "(" + EXPIRED_PRODUCT_OBJECT + ") OR (" + ORPHAN_OBJECT + ")";

    private final IngestionStore db;
    private final IngestionProperties config;
    private final IngestionService products;
    private final IngestionStorage storage;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ingestion-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    public IngestionCleanup(IngestionStore db, IngestionProperties config, IngestionService products,
                            IngestionStorage storage) {
        this.db = db;
        this.config = config;
        this.products = products;
        this.storage = storage;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void schedule() {
        if (!config.isEnabled() || !running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    sweep();
                } catch (RuntimeException error) {
                    LOG.warn("入库清理扫描失败，后续周期重试 ({})", error.getClass().getSimpleName());
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException closing) {
            running.set(false);
        }
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }

    void sweep() {
        if (!config.isEnabled()) return;
        for (ObjectNode candidate : db.rows("SELECT o.id,o.owner_id,o.product_id,o.job_id FROM ingest_object o " + PRODUCT_JOIN
                + " WHERE " + DUE + " AND (" + ELIGIBLE + ") ORDER BY o.delete_after,o.id LIMIT ?", BATCH_SIZE)) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                Claim claim = claim(candidate);
                if (claim == null) continue;
                String failure = null;
                try {
                    storage.deleteConfirmed(claim.object());
                } catch (IngestionProblem error) {
                    failure = error.reason;
                }
                finish(claim, failure);
            } catch (RuntimeException error) {
                LOG.warn("入库对象清理暂缓，后续周期重试 ({})", error.getClass().getSimpleName());
            }
        }
    }

    private record Claim(ObjectNode object, int attempt) {}

    private Claim claim(ObjectNode candidate) {
        return db.independent(() -> {
            products.quotaLock();
            UUID productId = uuid(candidate, "product_id");
            String owner = candidate.path("owner_id").asText();
            products.product(owner, productId, true);
            UUID jobId = uuid(candidate, "job_id");
            if (jobId != null) db.one("SELECT id FROM ingest_job WHERE id=? AND owner_id=? FOR UPDATE", jobId, owner);
            ObjectNode object = db.one("SELECT o.* FROM ingest_object o " + PRODUCT_JOIN
                    + " WHERE o.id=? AND o.owner_id=? AND o.product_id=? AND o.job_id IS NOT DISTINCT FROM ? AND "
                    + DUE + " AND (" + ELIGIBLE + ") FOR UPDATE OF o", IngestionStore.id(candidate), owner, productId, jobId);
            if (object == null) return null;
            ObjectNode attempt = db.one("SELECT * FROM ingest_attempt WHERE id=? AND owner_id=?", uuid(object, "attempt_id"), owner);
            if (!matchesObject(object, attempt)) {
                defer(object, 3600, "INPUT_HASH_MISMATCH");
                return null;
            }
            int generation = object.path("delete_attempts").asInt() + 1;
            // delete_after doubles as the reclaim time after a crash or unknown storage response.
            db.update("UPDATE ingest_object SET state='DELETE_PENDING',delete_attempts=?,delete_after=clock_timestamp()+interval '2 minutes' WHERE id=?",
                    generation, IngestionStore.id(object));
            if (retainedRaw(object) && "AVAILABLE".equals(object.path("state").asText())) {
                db.update("UPDATE ingest_product SET status='EXPIRED',raw_state='DELETE_PENDING',version=version+1,updated_at=clock_timestamp() WHERE id=?",
                        productId);
            }
            object.put("state", "DELETE_PENDING");
            return new Claim(object, generation);
        });
    }

    private boolean matchesObject(ObjectNode object, ObjectNode attempt) {
        if (attempt == null || !storage.bucket().equals(object.path("bucket").asText())
                || !object.path("publication_id").isNull()) return false;
        String product = object.path("product_id").asText();
        String attemptId = attempt.path("id").asText();
        String kind = attempt.path("kind").asText();
        String role = object.path("role").asText();
        String key = object.path("server_key").asText();
        if (role.equals("RAW") || role.equals("PREVIEW")) {
            if (!object.path("product_id").equals(attempt.path("product_id")) || !object.path("job_id").isNull()) return false;
            return role.equals("RAW")
                    ? kind.equals("UPLOAD") && attemptId.equals(object.path("id").asText())
                        && key.equals("ingestion/raw/" + product + "/" + attemptId + "/product.zip")
                    : kind.equals("INSPECT") && key.equals("ingestion/preview/" + product + "/" + attemptId + "/preview.png");
        }
        return kind.equals("STANDARDIZE") && object.path("product_id").equals(attempt.path("input_product_id"))
                && object.path("job_id").isTextual() && object.path("job_id").equals(attempt.path("job_id"))
                && key.equals("ingestion/artifacts/" + object.path("job_id").asText() + "/" + attemptId
                    + (role.equals("TIFF") ? "/reflectance.tif" : "/reflectance.json"));
    }

    private void finish(Claim claim, String failure) {
        ObjectNode object = claim.object();
        db.independent(() -> {
            products.quotaLock();
            UUID productId = uuid(object, "product_id");
            products.product(object.path("owner_id").asText(), productId, true);
            UUID objectId = IngestionStore.id(object);
            if (failure == null) {
                int changed = db.update("UPDATE ingest_object SET state='DELETED',delete_after=NULL,last_error=NULL"
                        + " WHERE id=? AND state='DELETE_PENDING' AND delete_attempts=?", objectId, claim.attempt());
                if (changed == 1 && retainedRaw(object)) {
                    db.update("UPDATE ingest_product SET raw_state='DELETED',version=version+1,updated_at=clock_timestamp()"
                            + " WHERE id=? AND raw_object_id=? AND raw_state='DELETE_PENDING'", productId, objectId);
                }
            } else {
                int seconds = Math.min(3600, 60 * (1 << Math.min(6, claim.attempt())));
                db.update("UPDATE ingest_object SET delete_after=clock_timestamp()+(? * interval '1 second'),last_error=?::jsonb"
                        + " WHERE id=? AND state='DELETE_PENDING' AND delete_attempts=?",
                        seconds, products.error(failure, "CLEANUP").toString(), objectId, claim.attempt());
            }
            return null;
        });
    }

    private void defer(ObjectNode object, int seconds, String reason) {
        db.update("UPDATE ingest_object SET delete_after=clock_timestamp()+(? * interval '1 second'),last_error=?::jsonb WHERE id=?",
                seconds, products.error(reason, "CLEANUP").toString(), IngestionStore.id(object));
    }

    private static UUID uuid(ObjectNode object, String field) {
        return object.path(field).isTextual() ? UUID.fromString(object.path(field).asText()) : null;
    }

    private static boolean retainedRaw(ObjectNode object) {
        return "RAW".equals(object.path("role").asText()) && "RAW_7D".equals(object.path("retention").asText());
    }
}
