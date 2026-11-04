package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.ComputeSlots;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** S2 consumes INSPECT/PREFLIGHT only. Completion and receipt commit atomically. */
@Service
public class IngestionWorkService {
    private final IngestionStore db;
    private final IngestionProperties config;
    private final IngestionService products;
    private final IngestionStorage storage;
    private final ComputeSlots slots;
    public IngestionWorkService(IngestionStore db, IngestionProperties config, IngestionService products, IngestionStorage storage, ComputeSlots slots) {
        this.db=db;this.config=config;this.products=products;this.storage=storage;this.slots=slots;
    }
    public ObjectNode work(UUID id, boolean lock) {
        ObjectNode row=db.one("SELECT * FROM ingest_outbox WHERE id=?"+(lock?" FOR UPDATE":""),id);
        require(row!=null,404,"NOT_FOUND");
        require(List.of("INSPECT","PREFLIGHT").contains(row.path("kind").asText()),400,"INVALID_REQUEST");
        return row;
    }
    private boolean inspect(ObjectNode work) { return "INSPECT".equals(work.path("kind").asText()); }
    private ObjectNode parent(ObjectNode work, boolean lock) {
        UUID target=UUID.fromString(work.path("target_id").asText());
        if(inspect(work)) return db.one("SELECT * FROM ingest_product WHERE id=?"+(lock?" FOR UPDATE":""),target);
        ObjectNode c=db.one("SELECT * FROM ingest_confirmation WHERE id=?",target);
        require(c!=null,404,"NOT_FOUND");
        if(lock) db.one("SELECT * FROM ingest_product WHERE id=? FOR UPDATE",UUID.fromString(c.path("product_id").asText()));
        return lock?db.one("SELECT * FROM ingest_confirmation WHERE id=? FOR UPDATE",target):c;
    }
    private ObjectNode product(ObjectNode work,ObjectNode parent) {
        return inspect(work)?parent:products.product(parent.path("owner_id").asText(),UUID.fromString(parent.path("product_id").asText()),false);
    }
    private ObjectNode existing(UUID work) { return db.one("SELECT * FROM ingest_attempt WHERE work_id=?",work); }
    private boolean queued(ObjectNode work,ObjectNode parent) {
        return parent!=null && (inspect(work)?"INSPECT_QUEUED":"PENDING").equals(parent.path("status").asText())
                && (inspect(work)?parent.path("inspect_generation").asInt():parent.path("generation").asInt())==work.path("generation").asInt()
                && parent.path("current_attempt_id").isNull();
    }
    public ObjectNode claim(UUID id,JsonNode body,String messageDigest) {
        products.enabled(); String executor=slots.requestExecutor();
        require(integer(body,"schemaVersion",1,1)==1 && uuid(body,"workerId").toString().equals(executor),400,"INVALID_REQUEST");
        UUID request=uuid(body,"claimRequestId"); ObjectNode snapshot=work(id,false);
        require(sha(snapshot.path("payload")).equals(messageDigest),409,"STATE_CONFLICT");
        ObjectNode old=existing(id);
        if(old!=null) {
            if(old.path("claim_request_id").asText().equals(request.toString()) && old.path("executor_id").asText().equals(executor)
                    && "RUNNING".equals(old.path("status").asText()) && IngestionStore.time(old,"lease_until").isAfter(db.now())
                    && slots.valid("COMPUTE","WORK:"+id,executor,old.path("slot_fence").asLong())) return response(snapshot,parent(snapshot,false),old);
            return db.object().put("action","SKIP");
        }
        if(!queued(snapshot,parent(snapshot,false)) || "FAILED".equals(snapshot.path("status").asText())) return db.object().put("action","SKIP");
        if(!IngestionStore.time(snapshot,"created_at").plusSeconds(config.getQueueDeadlineSeconds()).isAfter(db.now())) {
            failQueued(id,"HARD_TIMEOUT"); return db.object().put("action","SKIP");
        }
        int limit=inspect(snapshot)?config.getInspectDeadlineSeconds():config.getPreflightDeadlineSeconds();
        ComputeSlots.Lease lease=slots.reserve("COMPUTE","WORK:"+id,executor,limit);
        if(lease==null) return db.object().put("action","BUSY");
        try {
            ObjectNode result=db.transaction(() -> {
                products.quotaLock(); ObjectNode p=parent(snapshot,true); ObjectNode w=work(id,true);
                if(!queued(w,p) || existing(id)!=null) return db.object().put("action","SKIP");
                ObjectNode source=product(w,p); String owner=p.path("owner_id").asText(); UUID attempt=UUID.randomUUID();
                if(inspect(w)) products.quota(owner,config.getPreviewMaxBytes());
                String column=inspect(w)?"product_id":"confirmation_id";
                db.update("INSERT INTO ingest_attempt(id,owner_id,kind,"+column+",generation,work_id,claim_request_id,slot_fence,input_product_id,executor_id,lease_until,hard_deadline)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,LEAST(?::timestamptz,clock_timestamp()+(? * interval '1 second')),?::timestamptz)",
                        attempt,owner,w.path("kind").asText(),IngestionStore.id(p),w.path("generation").asInt(),id,request,lease.fence(),IngestionStore.id(source),executor,
                        lease.hardDeadline().toString(),config.getLeaseSeconds(),lease.hardDeadline().toString());
                if(inspect(w)) {
                    db.update("INSERT INTO ingest_object(id,owner_id,product_id,attempt_id,role,storage,server_key,bucket,reserved_bytes,retention,delete_after)"
                            + " VALUES (?,?,?,?,'PREVIEW','MINIO',?,?,?,'PREVIEW_7D',?::timestamptz)",UUID.randomUUID(),owner,IngestionStore.id(p),attempt,
                            "ingestion/preview/"+p.path("id").asText()+"/"+attempt+"/preview.png",storage.bucket(),config.getPreviewMaxBytes(),p.path("expires_at").asText());
                    db.update("UPDATE ingest_product SET status='INSPECTING',current_attempt_id=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",attempt,IngestionStore.id(p));
                } else db.update("UPDATE ingest_confirmation SET current_attempt_id=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",attempt,IngestionStore.id(p));
                return response(w,parent(w,false),existing(id));
            });
            if(!"EXECUTE".equals(result.path("action").asText())) slots.stopped("COMPUTE","WORK:"+id,executor,lease.fence());
            return result;
        } catch(RuntimeException error) {
            if(existing(id)==null) {
                slots.stopped("COMPUTE","WORK:"+id,executor,lease.fence());
                if(error instanceof IngestionProblem problem && problem.status==429) failQueued(id,"QUOTA_EXCEEDED");
            } else slots.recovery("COMPUTE","WORK:"+id,executor,lease.fence());
            throw error;
        }
    }
    private ObjectNode response(ObjectNode work,ObjectNode parent,ObjectNode attempt) {
        ObjectNode source=product(work,parent);
        ObjectNode document=db.object().put("schemaVersion",1).put("productId",source.path("id").asText())
                .put("profileVersion",config.getProfileVersion()).put("archiveSha256",source.path("archive_sha256").asText());
        if(!inspect(work)) { document.set("descriptor",source.path("descriptor")); document.set("regionSnapshot",parent.path("region_snapshot")); }
        String canonical=canonical(document);
        ObjectNode context=db.object().put("profileVersion",config.getProfileVersion()).put("canonicalDocument",canonical)
                .put("canonicalHash",sha(canonical.getBytes(StandardCharsets.UTF_8)));
        context.set("work",work.path("payload"));
        context.set("descriptor",source.path("descriptor"));
        if(inspect(work)) {
            ObjectNode raw=products.object(UUID.fromString(source.path("raw_object_id").asText()));
            context.set("input",db.object().put("bucket",raw.path("bucket").asText()).put("key",raw.path("server_key").asText())
                    .put("sha256",source.path("archive_sha256").asText()).put("bytes",raw.path("actual_bytes").asLong()));
            ObjectNode preview=db.one("SELECT * FROM ingest_object WHERE attempt_id=? AND role='PREVIEW'",IngestionStore.id(attempt));
            context.set("outputs",db.object().set("PREVIEW",db.object().put("bucket",preview.path("bucket").asText()).put("key",preview.path("server_key").asText()).put("maxBytes",preview.path("reserved_bytes").asLong())));
        } else {
            context.set("regionSnapshot",parent.path("region_snapshot"));
            context.put("regionSnapshotHash",sha(parent.path("region_snapshot")));
            context.set("outputs",db.object());
        }
        context.set("resourceBudget",db.object().put("archiveMaxBytes",Math.min(config.getArchiveMaxBytes(),2L<<30)).put("tempDiskMaxBytes",10L<<30)
                .put("diskSafetyBytes",config.getDiskSafetyBytes()).put("deadlineAt",attempt.path("hard_deadline").asText()));
        return db.object().put("action","EXECUTE").put("attemptId",attempt.path("id").asText()).put("leaseUntil",attempt.path("lease_until").asText())
                .put("hardDeadline",attempt.path("hard_deadline").asText()).put("slotFence",attempt.path("slot_fence").asLong()).set("context",context);
    }
    private ObjectNode active(UUID id,JsonNode body,ObjectNode work,ObjectNode parent,String executor) {
        UUID attemptId=uuid(body,"attemptId"); long fence=integer(body,"slotFence",1,Long.MAX_VALUE);
        ObjectNode attempt=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=? FOR UPDATE",attemptId,id);
        require(attempt!=null && "RUNNING".equals(attempt.path("status").asText()) && attempt.path("executor_id").asText().equals(executor)
                && attempt.path("slot_fence").asLong()==fence && parent.path("current_attempt_id").asText().equals(attemptId.toString())
                && work.path("generation").asInt()==attempt.path("generation").asInt()
                && (inspect(work)?parent.path("inspect_generation").asInt():parent.path("generation").asInt())==attempt.path("generation").asInt()
                && (inspect(work)?"INSPECTING":"PENDING").equals(parent.path("status").asText())
                && IngestionStore.time(attempt,"lease_until").isAfter(db.now()) && IngestionStore.time(attempt,"hard_deadline").isAfter(db.now())
                && slots.valid("COMPUTE","WORK:"+id,executor,fence),409,"ATTEMPT_STALE");
        return attempt;
    }
    public ObjectNode heartbeat(UUID id,JsonNode body) {
        products.enabled(); String executor=slots.requestExecutor();
        return db.transaction(() -> {
            ObjectNode w=work(id,false), p=parent(w,true); work(id,true); ObjectNode a=active(id,body,w,p,executor);
            long sequence=integer(body,"sequence",0,Long.MAX_VALUE); require(sequence>=a.path("heartbeat_seq").asLong(),409,"ATTEMPT_STALE");
            if(sequence>a.path("heartbeat_seq").asLong()) {
                require(slots.renew("COMPUTE","WORK:"+id,executor,a.path("slot_fence").asLong()),409,"ATTEMPT_STALE");
                db.update("UPDATE ingest_attempt SET heartbeat_seq=?,lease_until=LEAST(hard_deadline,clock_timestamp()+(? * interval '1 second')) WHERE id=?",sequence,config.getLeaseSeconds(),IngestionStore.id(a));
                a=existing(id);
            }
            return db.object().put("continue",true).put("leaseUntil",a.path("lease_until").asText());
        });
    }
    public ObjectNode complete(UUID id,JsonNode body) {
        products.enabled(); String executor=slots.requestExecutor(); UUID attemptId=uuid(body,"attemptId");
        JsonNode result=body.path("result"); String digest=body.path("resultDigest").asText();
        require(digest.matches("[0-9a-f]{64}") && digest.equals(sha(result)),400,"INVALID_REQUEST");
        ObjectNode prior=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=? AND executor_id=?",attemptId,id,executor);
        require(prior!=null && prior.path("slot_fence").asLong()==integer(body,"slotFence",1,Long.MAX_VALUE),409,"ATTEMPT_STALE");
        if(!prior.path("result_receipt").isNull()) { require(digest.equals(prior.path("result_digest").asText()),409,"STATE_CONFLICT"); return (ObjectNode)prior.path("result_receipt"); }
        ObjectNode snapshot=work(id,false); ObjectNode parent=parent(snapshot,false);
        // Verify actual bytes before taking parent/attempt locks; only current attempts may even read outputs.
        require("RUNNING".equals(prior.path("status").asText()) && slots.valid("COMPUTE","WORK:"+id,executor,prior.path("slot_fence").asLong()),409,"ATTEMPT_STALE");
        if(inspect(snapshot)) validateInspect(attemptId,result);
        else validatePreflight(parent,product(snapshot,parent),result);
        return db.transaction(() -> {
            ObjectNode w=work(id,false), p=parent(w,true); work(id,true);
            ObjectNode receiptCheck=existing(id);
            if(!receiptCheck.path("result_receipt").isNull()) {
                require(digest.equals(receiptCheck.path("result_digest").asText()),409,"STATE_CONFLICT"); return (ObjectNode)receiptCheck.path("result_receipt");
            }
            ObjectNode a=active(id,body,w,p,executor);
            require(slots.renew("COMPUTE","WORK:"+id,executor,a.path("slot_fence").asLong()),409,"ATTEMPT_STALE");
            ObjectNode receipt=db.object().put("accepted",true).put("receiptId",attemptId.toString()).put("targetStatus","READY");
            int changed=db.update("UPDATE ingest_attempt SET status='SUCCEEDED',result_digest=?,result_receipt=?::jsonb,finished_at=clock_timestamp()"
                    + " WHERE id=? AND slot_fence=? AND status='RUNNING' AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()",digest,receipt.toString(),attemptId,a.path("slot_fence").asLong());
            require(changed==1,409,"ATTEMPT_STALE");
            if(inspect(w)) {
                changed=db.update("UPDATE ingest_product SET status='READY',descriptor=?::jsonb,descriptor_version=1,parser_version='safe-l2a-probe-v1',profile_version=?,last_error=NULL,version=version+1,updated_at=clock_timestamp()"
                        + " WHERE id=? AND current_attempt_id=? AND inspect_generation=? AND status='INSPECTING'",result.path("descriptor").toString(),config.getProfileVersion(),IngestionStore.id(p),attemptId,w.path("generation").asInt());
                db.update("UPDATE ingest_object SET state='AVAILABLE',actual_bytes=?,sha256=? WHERE attempt_id=? AND role='PREVIEW' AND state='RESERVED'",result.path("preview").path("bytes").asLong(),result.path("preview").path("sha256").asText(),attemptId);
            } else {
                ObjectNode source=product(w,p), descriptor=(ObjectNode)source.path("descriptor");
                ObjectNode doc=db.object().put("schemaVersion",1).put("productId",source.path("id").asText()).put("archiveSha256",source.path("archive_sha256").asText())
                        .put("descriptorVersion",source.path("descriptor_version").asInt()).put("parserVersion",source.path("parser_version").asText())
                        .put("profileVersion",config.getProfileVersion()).put("imageName",p.path("image_name").asText());
                doc.set("regionSnapshot",p.path("region_snapshot")); doc.set("sourceGrid",descriptor.path("grid")); doc.set("calibration",descriptor.path("calibration"));
                doc.set("rawCalibration",descriptor.path("rawCalibration")); doc.set("window",result.path("window"));
                doc.set("qualityRules",db.object().put("dnZeroInvalid",true).put("sclResampling","nearest").put("validatorVersion","input-v1")
                        .put("preprocessingVersion","s2-reflectance-v1").set("sclValidClasses",db.tree(List.of(4,5,6,7))));
                String canonical=canonical(doc);
                changed=db.update("UPDATE ingest_confirmation SET status='READY',\"window\"=?::jsonb,source_grid=?::jsonb,canonical_document=?,confirmation_hash=?,"
                        + "expires_at=clock_timestamp()+(? * interval '1 second'),last_error=NULL,version=version+1,updated_at=clock_timestamp()"
                        + " WHERE id=? AND current_attempt_id=? AND generation=? AND status='PENDING'",result.path("window").toString(),result.path("sourceGrid").toString(),canonical,sha(canonical.getBytes(StandardCharsets.UTF_8)),config.getConfirmationSeconds(),IngestionStore.id(p),attemptId,w.path("generation").asInt());
            }
            require(changed==1,409,"ATTEMPT_STALE"); return receipt;
        });
    }
    private void validateInspect(UUID attempt,JsonNode result) {
        fields(result,"descriptor","preview"); fields(result.path("preview"),"bytes","sha256");
        DescriptorValidation.check(result.path("descriptor"),config.getProfileVersion());
        long size=integer(result.path("preview"),"bytes",1,config.getPreviewMaxBytes()); String hash=result.path("preview").path("sha256").asText();
        require(hash.matches("[0-9a-f]{64}"),400,"INVALID_REQUEST");
        ObjectNode object=db.one("SELECT * FROM ingest_object WHERE attempt_id=? AND role='PREVIEW'",attempt); require(object!=null,409,"ATTEMPT_STALE");
        storage.ensurePrivate(); byte[] bytes=storage.preview(object);
        require(bytes.length==size && sha(bytes).equals(hash),409,"INPUT_HASH_MISMATCH");
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(stream); require(readers.hasNext(),400,"INVALID_REQUEST"); var reader=readers.next();
            try {
                require("png".equalsIgnoreCase(reader.getFormatName()),400,"INVALID_REQUEST"); reader.setInput(stream,true,true);
                JsonNode grid=result.path("descriptor").path("previewGrid");
                require(reader.getWidth(0)==grid.path("width").asInt() && reader.getHeight(0)==grid.path("height").asInt(),400,"INVALID_GRID");
                require(reader.read(0)!=null,400,"INVALID_REQUEST");
            } finally { reader.dispose(); }
        } catch(java.io.IOException error) { throw new IngestionProblem(400,"INVALID_REQUEST"); }
    }
    private void validatePreflight(ObjectNode parent,ObjectNode source,JsonNode result) {
        fields(result,"window","sourceGrid","regionSnapshotHash");
        require(sha(parent.path("region_snapshot")).equals(result.path("regionSnapshotHash").asText())
                && canonical(source.path("descriptor").path("grid")).equals(canonical(result.path("sourceGrid"))),409,"INPUT_HASH_MISMATCH");
        JsonNode window=result.path("window"); require(window.isArray() && window.size()==4,400,"INVALID_GRID");
        for(JsonNode n:window) require(n.isIntegralNumber() && n.canConvertToInt() && n.asInt()>=0,400,"INVALID_GRID");
        require(window.get(2).asInt()>=1 && window.get(3).asInt()>=1 && window.get(2).asInt()<=2048 && window.get(3).asInt()<=2048
                && (long)window.get(0).asInt()+window.get(2).asInt()<=result.path("sourceGrid").path("width").asInt()
                && (long)window.get(1).asInt()+window.get(3).asInt()<=result.path("sourceGrid").path("height").asInt(),400,"WINDOW_TOO_LARGE");
    }
    private static final Set<String> REASONS=Set.of("INVALID_REQUEST","INVALID_ARCHIVE","UNSUPPORTED_PRODUCT","INVALID_GRID","NO_VALID_PIXELS","REGION_OUTSIDE_PRODUCT","WINDOW_TOO_LARGE","RESOURCE_LIMIT","INPUT_HASH_MISMATCH","HARD_TIMEOUT","LEASE_LOST","SERVICE_UNAVAILABLE");
    public ObjectNode fail(UUID id,JsonNode body) {
        products.enabled(); String executor=slots.requestExecutor(); String reason=body.path("reason").asText(); require(REASONS.contains(reason),400,"INVALID_REQUEST");
        return db.transaction(() -> { ObjectNode w=work(id,false),p=parent(w,true); work(id,true); ObjectNode a=active(id,body,w,p,executor); failed(w,p,a,reason); return db.object().put("accepted",true); });
    }
    private void failed(ObjectNode w,ObjectNode p,ObjectNode attempt,String reason) {
        ObjectNode error=products.error(reason,w.path("kind").asText());
        if(attempt!=null) {
            db.update("UPDATE ingest_attempt SET status='FAILED',error=?::jsonb,finished_at=clock_timestamp() WHERE id=? AND status='RUNNING'",error.toString(),IngestionStore.id(attempt));
            db.update("UPDATE ingest_object SET retention='ORPHAN',delete_after=clock_timestamp()+interval '24 hours' WHERE attempt_id=? AND state='RESERVED'",IngestionStore.id(attempt));
        }
        db.update("UPDATE "+(inspect(w)?"ingest_product":"ingest_confirmation")+" SET status='FAILED',last_error=?::jsonb,version=version+1,updated_at=clock_timestamp() WHERE id=?",error.toString(),IngestionStore.id(p));
    }
    public void failQueued(UUID id,String reason) {
        db.transaction(() -> {
            ObjectNode w=work(id,false),p=parent(w,true); work(id,true);
            if(queued(w,p) && existing(id)==null) { failed(w,p,null,reason); db.update("UPDATE ingest_outbox SET status='FAILED',last_error=?::jsonb,updated_at=clock_timestamp() WHERE id=?",products.error(reason,w.path("kind").asText()).toString(),id); }
            return null;
        });
    }
    public ObjectNode stopped(UUID id,JsonNode body) {
        products.enabled(); String executor=slots.requestExecutor(); UUID attempt=uuid(body,"attemptId"); long fence=integer(body,"slotFence",1,Long.MAX_VALUE);
        require(uuid(body,"executorId").toString().equals(executor) && List.of("EXITED","KILLED").contains(body.path("termination").asText()),400,"INVALID_REQUEST");
        db.transaction(() -> {
            ObjectNode w=work(id,false),p=parent(w,true); work(id,true);
            ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=? AND executor_id=? AND slot_fence=? FOR UPDATE",attempt,id,executor,fence);
            require(a!=null,409,"ATTEMPT_STALE");
            if("RUNNING".equals(a.path("status").asText()) && p.path("current_attempt_id").asText().equals(attempt.toString())) failed(w,p,a,"LEASE_LOST");
            db.update("UPDATE ingest_attempt SET stopped_at=clock_timestamp() WHERE id=?",attempt); return null;
        });
        slots.stopped("COMPUTE","WORK:"+id,executor,fence);
        return db.object().put("accepted",true);
    }
    @Scheduled(fixedDelay=10000)
    public void expire() {
        if(!config.isEnabled()) return;
        for(ObjectNode w:db.rows("SELECT * FROM ingest_outbox WHERE kind IN ('INSPECT','PREFLIGHT') AND created_at<clock_timestamp()-(? * interval '1 second')"
                + " AND status<>'FAILED' AND NOT EXISTS(SELECT 1 FROM ingest_attempt a WHERE a.work_id=ingest_outbox.id) ORDER BY created_at LIMIT 10",config.getQueueDeadlineSeconds())) failQueued(IngestionStore.id(w),"HARD_TIMEOUT");
        for(ObjectNode snapshot:db.rows("SELECT * FROM ingest_attempt WHERE kind IN ('INSPECT','PREFLIGHT') AND status='RUNNING'"
                + " AND (lease_until<=clock_timestamp() OR hard_deadline<=clock_timestamp()) ORDER BY lease_until LIMIT 10")) {
            UUID id=UUID.fromString(snapshot.path("work_id").asText());
            db.transaction(() -> {
                ObjectNode w=work(id,false),p=parent(w,true); work(id,true); ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? FOR UPDATE",IngestionStore.id(snapshot));
                if("RUNNING".equals(a.path("status").asText()) && p.path("current_attempt_id").asText().equals(a.path("id").asText())
                        && (!IngestionStore.time(a,"lease_until").isAfter(db.now()) || !IngestionStore.time(a,"hard_deadline").isAfter(db.now()))) failed(w,p,a,"LEASE_LOST");
                return null;
            });
            slots.recovery("COMPUTE","WORK:"+id,snapshot.path("executor_id").asText(),snapshot.path("slot_fence").asLong());
        }
    }
}
