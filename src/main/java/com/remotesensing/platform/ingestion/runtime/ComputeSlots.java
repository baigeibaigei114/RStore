package com.remotesensing.platform.ingestion.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** DB leases fence results; only a stopped-process receipt releases an executing holder. */
@Service
public class ComputeSlots {
    public record Lease(long fence, Instant hardDeadline) {}
    private final IngestionStore db;
    private final IngestionProperties config;
    public ComputeSlots(IngestionStore db, IngestionProperties config) { this.db = db; this.config = config; }
    public boolean enabled() { return config.isEnabled(); }
    public String requestExecutor() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            throw new BusinessException(503, "Worker protocol required");
        HttpServletRequest request = attributes.getRequest();
        if (!"ingestion-v1".equals(request.getHeader("X-Worker-Protocol")))
            throw new BusinessException(503, "Worker protocol upgrade required");
        try { return UUID.fromString(request.getHeader("X-Worker-Id")).toString(); }
        catch (RuntimeException error) { throw new BusinessException(400, "Invalid worker identity"); }
    }
    public Lease reserve(String key, String holder, String executor, int deadlineSeconds) {
        return db.independent(() -> {
            ObjectNode slot = db.one("SELECT * FROM ingest_runtime_slot WHERE slot_key=? FOR UPDATE", key);
            if (slot == null) throw new BusinessException(503, "Ingestion migration required");
            if (!"IDLE".equals(slot.path("state").asText())) return null;
            if ("COMPUTE".equals(key) && db.count("SELECT count(*) FROM rs_task WHERE status='RUNNING'") > 0) {
                db.update("UPDATE ingest_runtime_slot SET state='RECOVERY_REQUIRED',updated_at=clock_timestamp() WHERE slot_key=?", key);
                return null;
            }
            db.update("UPDATE ingest_runtime_slot SET state='HELD',holder_ref=?,executor_id=?,fence=fence+1,"
                    + "lease_until=clock_timestamp()+(? * interval '1 second'),hard_deadline=clock_timestamp()+(? * interval '1 second'),"
                    + "updated_at=clock_timestamp() WHERE slot_key=?", holder, executor, Math.min(config.getLeaseSeconds(), deadlineSeconds), deadlineSeconds, key);
            ObjectNode result = db.one("SELECT * FROM ingest_runtime_slot WHERE slot_key=?", key);
            return new Lease(result.path("fence").asLong(), IngestionStore.time(result, "hard_deadline"));
        });
    }
    public boolean renew(String key, String holder, String executor, long fence) {
        return db.update("UPDATE ingest_runtime_slot SET lease_until=LEAST(hard_deadline,clock_timestamp()+(? * interval '1 second')),"
                + "updated_at=clock_timestamp() WHERE slot_key=? AND state='HELD' AND holder_ref=? AND executor_id=? AND fence=?"
                + " AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()", config.getLeaseSeconds(), key, holder, executor, fence) == 1;
    }
    public boolean valid(String key, String holder, String executor, long fence) {
        return db.count("SELECT count(*) FROM ingest_runtime_slot WHERE slot_key=? AND state='HELD' AND holder_ref=? AND executor_id=? AND fence=?"
                + " AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()", key, holder, executor, fence) == 1;
    }
    public void stopped(String key, String holder, String executor, long fence) {
        db.independent(() -> {
            db.update("UPDATE ingest_runtime_slot SET state='IDLE',holder_ref=NULL,executor_id=NULL,lease_until=NULL,hard_deadline=NULL,"
                    + "updated_at=clock_timestamp() WHERE slot_key=? AND state IN ('HELD','RECOVERY_REQUIRED') AND holder_ref=? AND executor_id=? AND fence=?",
                    key, holder, executor, fence);
            return null;
        });
    }
    public void recovery(String key, String holder, String executor, long fence) {
        db.independent(() -> { db.update("UPDATE ingest_runtime_slot SET state='RECOVERY_REQUIRED',updated_at=clock_timestamp()"
                + " WHERE slot_key=? AND holder_ref=? AND executor_id=? AND fence=?", key, holder, executor, fence); return null; });
    }
    public void guardNdvi(long taskId, Long fence) {
        if (!enabled()) return;
        if (fence == null || !renew("COMPUTE", "NDVI:" + taskId, requestExecutor(), fence))
            throw new BusinessException(409, "Compute lease lost");
    }
    @Scheduled(fixedDelay = 10000)
    public void expire() {
        if (!enabled()) return;
        db.update("UPDATE ingest_runtime_slot SET state='RECOVERY_REQUIRED',updated_at=clock_timestamp() WHERE state='HELD'"
                + " AND (lease_until<=clock_timestamp() OR hard_deadline<=clock_timestamp())");
    }
}
