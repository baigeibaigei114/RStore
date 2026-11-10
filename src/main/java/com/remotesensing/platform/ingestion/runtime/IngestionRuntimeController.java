package com.remotesensing.platform.ingestion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Internal Worker-token endpoints; never an operator/browser force-release API. */
@RestController
@RequestMapping("/internal/ingestion/runtime")
public class IngestionRuntimeController {
    private final ComputeSlots slots;
    private final IngestionProperties config;
    private final IngestionStore db;
    public IngestionRuntimeController(ComputeSlots slots, IngestionProperties config, IngestionStore db) {
        this.slots = slots; this.config = config; this.db = db;
    }
    @PostMapping("/ready")
    public ResponseEntity<?> ready(@RequestBody JsonNode body) {
        slots.requestExecutor();
        boolean capability=body.path("capabilities").isArray() && body.path("capabilities").size()==1
                && "standardize-v1".equals(body.path("capabilities").get(0).asText());
        if (!config.isEnabled() || !(body.size()==2 || (body.size()==3 && capability)) || body.path("schemaVersion").asInt()!=1
                || (config.isStandardizationEnabled() && !capability)
                || !config.getProfileVersion().equals(body.path("profileVersion").asText()))
            return ResponseEntity.status(503).body(Result.fail(503, "Ingestion protocol/profile unavailable"));
        return ResponseEntity.ok(Result.success(db.object().put("schemaVersion", 1).put("profileVersion", config.getProfileVersion())
                .set("compute", db.one("SELECT slot_key,state FROM ingest_runtime_slot WHERE slot_key='COMPUTE'"))));
    }
    @PostMapping("/ndvi/{taskId}/stopped")
    public ResponseEntity<?> stopped(@PathVariable long taskId, @RequestBody JsonNode body) {
        String executor = slots.requestExecutor();
        if (!config.isEnabled() || !body.isObject() || body.size()!=2 || !body.path("slotFence").canConvertToLong()
                || !("EXITED".equals(body.path("termination").asText()) || "KILLED".equals(body.path("termination").asText())))
            return ResponseEntity.badRequest().body(Result.fail(400, "Invalid stopped receipt"));
        slots.stopped("COMPUTE", "NDVI:" + taskId, executor, body.path("slotFence").asLong());
        return ResponseEntity.ok(Result.success());
    }
}
