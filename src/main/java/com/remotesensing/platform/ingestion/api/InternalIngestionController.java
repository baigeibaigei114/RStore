package com.remotesensing.platform.ingestion.api;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.ingestion.workflow.IngestionWorkService;
import com.remotesensing.platform.ingestion.workflow.IngestionJobWorkService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.read;

@RestController
@RequestMapping("/internal/ingestion/work/{id}")
public class InternalIngestionController {
    private final IngestionWorkService service;
    private final IngestionJobWorkService jobs;
    public InternalIngestionController(IngestionWorkService service,IngestionJobWorkService jobs) {this.service=service;this.jobs=jobs;}
    @PostMapping("/claim") public Result<?> claim(@PathVariable UUID id,HttpServletRequest r) {
        if(jobs.standardize(id))return Result.success(jobs.claim(id,read(r,"schemaVersion","workerId","claimRequestId"),r.getHeader("X-Ingestion-Work-Digest"),r.getHeader("X-Ingestion-Capability")));
        return Result.success(service.claim(id,read(r,"schemaVersion","workerId","claimRequestId"),r.getHeader("X-Ingestion-Work-Digest")));
    }
    @PostMapping("/heartbeat") public Result<?> heartbeat(@PathVariable UUID id,HttpServletRequest r) {
        if(jobs.standardize(id))return Result.success(jobs.heartbeat(id,read(r,"attemptId","slotFence","sequence")));
        return Result.success(service.heartbeat(id,read(r,"attemptId","slotFence","sequence")));
    }
    @PostMapping("/complete") public Result<?> complete(@PathVariable UUID id,HttpServletRequest r) {
        if(jobs.standardize(id))return Result.success(jobs.complete(id,read(r,"attemptId","slotFence","resultDigest","result")));
        return Result.success(service.complete(id,read(r,"attemptId","slotFence","resultDigest","result")));
    }
    @PostMapping("/fail") public Result<?> fail(@PathVariable UUID id,HttpServletRequest r) {
        if(jobs.standardize(id))return Result.success(jobs.fail(id,read(r,"attemptId","slotFence","reason")));
        return Result.success(service.fail(id,read(r,"attemptId","slotFence","reason")));
    }
    @PostMapping("/stopped") public Result<?> stopped(@PathVariable UUID id,HttpServletRequest r) {
        if(jobs.standardize(id))return Result.success(jobs.stopped(id,read(r,"attemptId","slotFence","executorId","termination")));
        return Result.success(service.stopped(id,read(r,"attemptId","slotFence","executorId","termination")));
    }
}
