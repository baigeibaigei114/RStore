package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.ComputeSlots;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** STANDARDIZE goes through Worker-token HTTP; VALIDATE is callable only by the Java LOCAL dispatcher. */
@Service
public class IngestionJobWorkService {
    private final IngestionStore db;
    private final IngestionProperties config;
    private final IngestionService products;
    private final IngestionJobService jobs;
    private final IngestionStorage storage;
    private final ComputeSlots slots;
    public IngestionJobWorkService(IngestionStore db,IngestionProperties config,IngestionService products,
                                   IngestionJobService jobs,IngestionStorage storage,ComputeSlots slots) {
        this.db=db;this.config=config;this.products=products;this.jobs=jobs;this.storage=storage;this.slots=slots;
    }
    public boolean standardize(UUID id) {
        ObjectNode w=db.one("SELECT kind FROM ingest_outbox WHERE id=?",id);
        return w!=null && "STANDARDIZE".equals(w.path("kind").asText());
    }
    public ObjectNode work(UUID id,boolean local,boolean lock) {
        ObjectNode w=db.one("SELECT * FROM ingest_outbox WHERE id=?"+(lock?" FOR UPDATE":""),id);
        require(w!=null,404,"NOT_FOUND");
        require((local?"VALIDATE":"STANDARDIZE").equals(w.path("kind").asText()) && (local?"LOCAL":"MQ").equals(w.path("dispatch_mode").asText()),400,"INVALID_REQUEST");return w;
    }
    private ObjectNode parent(ObjectNode w,boolean lock) {
        ObjectNode j=db.one("SELECT * FROM ingest_job WHERE id=?",uuid(w,"target_id"));require(j!=null,404,"NOT_FOUND");
        if(lock) { products.product(j.path("owner_id").asText(),uuid(j,"active_product_id"),true); return jobs.job(j.path("owner_id").asText(),IngestionStore.id(j),true); }
        return j;
    }
    private ObjectNode attempt(UUID work) { return db.one("SELECT * FROM ingest_attempt WHERE work_id=?",work); }
    private boolean queued(ObjectNode w,ObjectNode j,boolean local) {
        return (local?"VALIDATING":"QUEUED").equals(j.path("status").asText()) && j.path("current_attempt_id").isNull()
                && w.path("generation").asInt()==j.path("generation").asInt() && !"FAILED".equals(w.path("status").asText());
    }
    public ObjectNode claim(UUID id,JsonNode body,String digest,String capability) {
        products.enabled();require("standardize-v1".equals(capability),503,"SERVICE_UNAVAILABLE");
        String executor=slots.requestExecutor();require(integer(body,"schemaVersion",1,1)==1 && uuid(body,"workerId").toString().equals(executor),400,"INVALID_REQUEST");
        ObjectNode w=work(id,false,false);require(sha(w.path("payload")).equals(digest),409,"STATE_CONFLICT");
        return claim(id,uuid(body,"claimRequestId"),executor,false);
    }
    public ObjectNode claimLocal(UUID id,UUID request,String executor) {
        products.enabled(); UUID.fromString(executor);return claim(id,request,executor,true);
    }
    private ObjectNode claim(UUID id,UUID request,String executor,boolean local) {
        ObjectNode snapshot=work(id,local,false),j=parent(snapshot,false),old=attempt(id);
        if(old!=null) {
            if(old.path("executor_id").asText().equals(executor) && old.path("claim_request_id").asText().equals(request.toString())
                    && "RUNNING".equals(old.path("status").asText()) && j.path("current_attempt_id").asText().equals(old.path("id").asText())
                    && old.path("generation").asInt()==j.path("generation").asInt() && IngestionStore.time(old,"lease_until").isAfter(db.now())
                    && slots.valid("COMPUTE","WORK:"+id,executor,old.path("slot_fence").asLong()))return response(snapshot,j,old,local);
            return db.object().put("action","SKIP");
        }
        if(!queued(snapshot,j,local))return db.object().put("action","SKIP");
        if(!IngestionStore.time(snapshot,"created_at").plusSeconds(config.getQueueDeadlineSeconds()).isAfter(db.now())) {
            failQueued(id,local,"HARD_TIMEOUT");return db.object().put("action","SKIP");
        }
        require(config.isStandardizationEnabled(),503,"SERVICE_UNAVAILABLE");
        int seconds=local?config.getValidateDeadlineSeconds():config.getStandardizeDeadlineSeconds();
        ComputeSlots.Lease lease=slots.reserve("COMPUTE","WORK:"+id,executor,seconds);
        if(lease==null)return db.object().put("action","BUSY");
        boolean own=!TransactionSynchronizationManager.isActualTransactionActive(); boolean[] rolledBack={false};
        try {
            ObjectNode result=db.transaction(() -> {
                if(own && TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { rolledBack[0]=status==STATUS_ROLLED_BACK; }
                });
                products.quotaLock();ObjectNode job=parent(snapshot,true),w=work(id,local,true);
                if(!queued(w,job,local) || attempt(id)!=null)return db.object().put("action","SKIP");
                ObjectNode source=products.product(job.path("owner_id").asText(),uuid(job,"active_product_id"),false);
                require("AVAILABLE".equals(source.path("raw_state").asText()) && "AVAILABLE".equals(products.object(uuid(source,"raw_object_id")).path("state").asText()),410,"RAW_EXPIRED");
                // An accepted active job protects its original raw reference across the seven-day boundary.
                require(source.path("archive_sha256").asText().equals(job.path("archive_sha256").asText()),409,"INPUT_HASH_MISMATCH");
                UUID aid=UUID.randomUUID();String owner=job.path("owner_id").asText();
                if(!local)jobs.assetQuota(owner,tiffLimit()+manifestLimit());
                db.update("INSERT INTO ingest_attempt(id,owner_id,kind,job_id,generation,work_id,claim_request_id,slot_fence,input_product_id,executor_id,lease_until,hard_deadline)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,LEAST(?::timestamptz,clock_timestamp()+(? * interval '1 second')),?::timestamptz)",
                        aid,owner,w.path("kind").asText(),IngestionStore.id(job),w.path("generation").asInt(),id,request,lease.fence(),IngestionStore.id(source),executor,lease.hardDeadline().toString(),config.getLeaseSeconds(),lease.hardDeadline().toString());
                if(!local)for(String role:List.of("TIFF","MANIFEST")) {
                    db.update("INSERT INTO ingest_object(id,owner_id,product_id,job_id,attempt_id,role,storage,server_key,bucket,reserved_bytes,retention) VALUES (?,?,?,?,?,?,'MINIO',?,?,?,'ORPHAN')",
                            UUID.randomUUID(),owner,IngestionStore.id(source),IngestionStore.id(job),aid,role,
                            "ingestion/artifacts/"+job.path("id").asText()+"/"+aid+"/reflectance."+(role.equals("TIFF")?"tif":"json"),storage.bucket(),role.equals("TIFF")?tiffLimit():manifestLimit());
                }
                require(db.update("UPDATE ingest_job SET status=?,current_attempt_id=?,version=version+1,updated_at=clock_timestamp() WHERE id=? AND generation=? AND current_attempt_id IS NULL",local?"VALIDATING":"RUNNING",aid,IngestionStore.id(job),w.path("generation").asInt())==1,409,"ATTEMPT_STALE");
                if(local)db.update("UPDATE ingest_outbox SET status='SENT',sent_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",id);
                return response(w,parent(w,false),attempt(id),local);
            });
            if(!"EXECUTE".equals(result.path("action").asText()))slots.stopped("COMPUTE","WORK:"+id,executor,lease.fence());
            return result;
        } catch(RuntimeException error) {
            if(rolledBack[0]) {
                slots.stopped("COMPUTE","WORK:"+id,executor,lease.fence());
                if(error instanceof IngestionProblem problem && (problem.status==429 || problem.status==410))failQueued(id,local,problem.reason);
            } else slots.recovery("COMPUTE","WORK:"+id,executor,lease.fence());
            throw error;
        }
    }
    public long tiffLimit() {return Math.min(config.getTiffMaxBytes(),256L<<20);}
    public long manifestLimit() {return Math.min(config.getManifestMaxBytes(),65536);}
    private ObjectNode response(ObjectNode w,ObjectNode j,ObjectNode a,boolean local) {
        ObjectNode source=products.product(j.path("owner_id").asText(),uuid(a,"input_product_id"),false);
        String canonical=j.path("intent_document").asText();
        ObjectNode context=db.object().put("profileVersion",config.getProfileVersion()).put("canonicalDocument",canonical)
                .put("canonicalHash",sha(canonical.getBytes(StandardCharsets.UTF_8)));
        context.set("work",w.path("payload"));context.set("descriptor",source.path("descriptor"));context.set("window",j.path("window"));
        context.set("regionSnapshot",j.path("region_snapshot"));context.put("regionSnapshotHash",sha(j.path("region_snapshot")));
        ObjectNode raw=products.object(uuid(source,"raw_object_id"));
        context.set("input",db.object().put("productId",source.path("id").asText()).put("bucket",raw.path("bucket").asText()).put("key",raw.path("server_key").asText())
                .put("sha256",j.path("archive_sha256").asText()).put("bytes",raw.path("actual_bytes").asLong()));
        ObjectNode outputs=context.putObject("outputs");
        if(!local)for(ObjectNode o:db.rows("SELECT * FROM ingest_object WHERE attempt_id=? AND role IN ('TIFF','MANIFEST')",IngestionStore.id(a)))
            outputs.set(o.path("role").asText(),db.object().put("bucket",o.path("bucket").asText()).put("key",o.path("server_key").asText()).put("maxBytes",o.path("reserved_bytes").asLong()));
        else context.set("candidateArtifacts",j.path("candidate_artifacts"));
        context.set("resourceBudget",db.object().put("archiveMaxBytes",Math.min(config.getArchiveMaxBytes(),2L<<30)).put("tempDiskMaxBytes",10L<<30)
                .put("diskSafetyBytes",config.getDiskSafetyBytes()).put("deadlineAt",a.path("hard_deadline").asText()));
        return db.object().put("action","EXECUTE").put("attemptId",a.path("id").asText()).put("leaseUntil",a.path("lease_until").asText())
                .put("hardDeadline",a.path("hard_deadline").asText()).put("slotFence",a.path("slot_fence").asLong()).set("context",context);
    }
    public ObjectNode active(UUID id,JsonNode identity,ObjectNode w,ObjectNode j,String executor,boolean local) {
        ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=? FOR UPDATE",uuid(identity,"attemptId"),id);
        require(a!=null && "RUNNING".equals(a.path("status").asText()) && executor.equals(a.path("executor_id").asText())
                && a.path("slot_fence").asLong()==integer(identity,"slotFence",1,Long.MAX_VALUE)
                && j.path("current_attempt_id").asText().equals(a.path("id").asText()) && j.path("generation").asInt()==a.path("generation").asInt()
                && w.path("generation").asInt()==a.path("generation").asInt() && (local?"VALIDATING":"RUNNING").equals(j.path("status").asText())
                && IngestionStore.time(a,"lease_until").isAfter(db.now()) && IngestionStore.time(a,"hard_deadline").isAfter(db.now())
                && slots.valid("COMPUTE","WORK:"+id,executor,a.path("slot_fence").asLong()),409,"ATTEMPT_STALE");return a;
    }
    public ObjectNode heartbeat(UUID id,JsonNode body) {products.enabled();return heartbeat(id,body,slots.requestExecutor(),false);}
    public ObjectNode heartbeatLocal(UUID id,JsonNode body,String executor) {return heartbeat(id,body,executor,true);}
    private ObjectNode heartbeat(UUID id,JsonNode body,String executor,boolean local) {
        return db.transaction(() -> {
            ObjectNode w=work(id,local,false),j=parent(w,true);work(id,local,true);ObjectNode a=active(id,body,w,j,executor,local);
            long seq=integer(body,"sequence",0,Long.MAX_VALUE);require(seq>=a.path("heartbeat_seq").asLong(),409,"ATTEMPT_STALE");
            if(seq>a.path("heartbeat_seq").asLong()) {
                require(slots.renew("COMPUTE","WORK:"+id,executor,a.path("slot_fence").asLong()),409,"ATTEMPT_STALE");
                require(db.update("UPDATE ingest_attempt SET heartbeat_seq=?,lease_until=LEAST(hard_deadline,clock_timestamp()+(? * interval '1 second')) WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()",seq,config.getLeaseSeconds(),IngestionStore.id(a))==1,409,"ATTEMPT_STALE");a=attempt(id);
            }
            return db.object().put("continue",true).put("leaseUntil",a.path("lease_until").asText());
        });
    }
    public ObjectNode receipt(UUID id,JsonNode body,String executor) {
        ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=? AND executor_id=?",uuid(body,"attemptId"),id,executor);
        require(a!=null && a.path("slot_fence").asLong()==integer(body,"slotFence",1,Long.MAX_VALUE),409,"ATTEMPT_STALE");
        if(!a.path("result_receipt").isNull()) { require(a.path("result_digest").asText().equals(body.path("resultDigest").asText()),409,"STATE_CONFLICT");return (ObjectNode)a.path("result_receipt"); }
        return null;
    }
    public ObjectNode complete(UUID id,JsonNode body) {
        products.enabled();String executor=slots.requestExecutor();work(id,false,false);
        JsonNode result=body.path("result");String digest=body.path("resultDigest").asText();require(digest.matches("[0-9a-f]{64}") && digest.equals(sha(result)),400,"INVALID_REQUEST");
        ObjectNode replay=receipt(id,body,executor);if(replay!=null)return replay;
        ObjectNode snapshot=attempt(id);require(snapshot!=null && "RUNNING".equals(snapshot.path("status").asText())
                && slots.valid("COMPUTE","WORK:"+id,executor,snapshot.path("slot_fence").asLong()),409,"ATTEMPT_STALE");
        ObjectNode candidate=validateCandidate(snapshot,result);
        return db.transaction(() -> {
            ObjectNode w=work(id,false,false),j=parent(w,true);work(id,false,true);ObjectNode prior=receipt(id,body,executor);if(prior!=null)return prior;
            ObjectNode a=active(id,body,w,j,executor,false);
            require(slots.renew("COMPUTE","WORK:"+id,executor,a.path("slot_fence").asLong()),409,"ATTEMPT_STALE");
            ObjectNode response=db.object().put("accepted",true).put("receiptId",a.path("id").asText()).put("targetStatus","VALIDATING");
            require(db.update("UPDATE ingest_attempt SET status='RESULT_REPORTED',result_digest=?,result_receipt=?::jsonb,finished_at=clock_timestamp() WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()",digest,response.toString(),IngestionStore.id(a))==1,409,"ATTEMPT_STALE");
            for(JsonNode output:candidate.path("outputs"))require(db.update("UPDATE ingest_object SET actual_bytes=?,sha256=? WHERE id=? AND attempt_id=? AND state='RESERVED'",output.path("bytes").asLong(),output.path("sha256").asText(),uuid(output,"objectId"),IngestionStore.id(a))==1,409,"ATTEMPT_STALE");
            require(db.update("UPDATE ingest_job SET status='VALIDATING',candidate_artifacts=?::jsonb,current_attempt_id=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=? AND current_attempt_id=? AND generation=? AND status='RUNNING'",candidate.toString(),IngestionStore.id(j),IngestionStore.id(a),w.path("generation").asInt())==1,409,"ATTEMPT_STALE");
            jobs.intent("VALIDATE",IngestionStore.id(j),w.path("generation").asInt());return response;
        });
    }
    private ObjectNode validateCandidate(ObjectNode a,JsonNode result) {
        fields(result,"outputs","regionPixelCount","regionValidReflectanceCount");
        require(result.path("outputs").isArray() && result.path("outputs").size()==2,400,"INVALID_REQUEST");
        ObjectNode j=jobs.job(a.path("owner_id").asText(),uuid(a,"job_id"),false);long pixels=(long)j.path("window").get(2).asInt()*j.path("window").get(3).asInt();
        long region=integer(result,"regionPixelCount",0,pixels),valid=integer(result,"regionValidReflectanceCount",0,region);
        ObjectNode candidate=db.object().put("standardizeAttemptId",a.path("id").asText()).put("regionPixelCount",region).put("regionValidReflectanceCount",valid);
        var outputs=candidate.putArray("outputs");Set<String> roles=new HashSet<>();
        storage.ensurePrivate();
        for(JsonNode output:result.path("outputs")) {
            fields(output,"role","bytes","sha256");String role=output.path("role").asText();require(Set.of("TIFF","MANIFEST").contains(role) && roles.add(role),400,"INVALID_REQUEST");
            long bytes=integer(output,"bytes",1,role.equals("TIFF")?tiffLimit():manifestLimit());String hash=output.path("sha256").asText();require(hash.matches("[0-9a-f]{64}"),400,"INVALID_REQUEST");
            ObjectNode object=db.one("SELECT * FROM ingest_object WHERE attempt_id=? AND role=? AND job_id=? AND state='RESERVED'",IngestionStore.id(a),role,IngestionStore.id(j));require(object!=null,409,"ATTEMPT_STALE");
            require(storage.size(object,Duration.between(db.now(),IngestionStore.time(a,"hard_deadline")).getSeconds())==bytes,409,"INPUT_HASH_MISMATCH");
            outputs.add(db.object().put("role",role).put("objectId",object.path("id").asText()).put("bytes",bytes).put("sha256",hash));
        }
        return candidate;
    }
    /** The LOCAL caller supplies only the in-process registration closure after actual file validation. */
    public ObjectNode commitValidated(UUID id,JsonNode identity,String executor,String digest,BiFunction<ObjectNode,ObjectNode,ObjectNode> register) {
        ObjectNode replayBody=((ObjectNode)identity).deepCopy().put("resultDigest",digest);ObjectNode replay=receipt(id,replayBody,executor);if(replay!=null)return replay;
        return db.transaction(() -> {
            products.quotaLock();ObjectNode w=work(id,true,false),j=parent(w,true);work(id,true,true);
            ObjectNode prior=receipt(id,replayBody,executor);if(prior!=null)return prior;
            ObjectNode a=active(id,identity,w,j,executor,true);require(slots.renew("COMPUTE","WORK:"+id,executor,a.path("slot_fence").asLong()),409,"ATTEMPT_STALE");
            ObjectNode response=register.apply(j,a);
            require(db.update("UPDATE ingest_attempt SET status='SUCCEEDED',result_digest=?,result_receipt=?::jsonb,finished_at=clock_timestamp() WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()",digest,response.toString(),IngestionStore.id(a))==1,409,"ATTEMPT_STALE");return response;
        });
    }
    private static final Set<String> REASONS=Set.of("INVALID_REQUEST","INVALID_ARCHIVE","UNSUPPORTED_PRODUCT","INVALID_GRID","NO_VALID_PIXELS","REGION_OUTSIDE_PRODUCT","WINDOW_TOO_LARGE","RESOURCE_LIMIT","INPUT_HASH_MISMATCH","HARD_TIMEOUT","LEASE_LOST","SERVICE_UNAVAILABLE","ADMISSION_REJECTED");
    public ObjectNode fail(UUID id,JsonNode body) {products.enabled();return fail(id,body,slots.requestExecutor(),false);}
    public ObjectNode failLocal(UUID id,JsonNode body,String executor) {return fail(id,body,executor,true);}
    private ObjectNode fail(UUID id,JsonNode body,String executor,boolean local) {
        String reason=body.path("reason").asText();require(REASONS.contains(reason),400,"INVALID_REQUEST");
        return db.transaction(() -> {ObjectNode w=work(id,local,false),j=parent(w,true);work(id,local,true);ObjectNode a=active(id,body,w,j,executor,local);failed(w,j,a,reason);return db.object().put("accepted",true);});
    }
    private void failed(ObjectNode w,ObjectNode j,ObjectNode a,String reason) {
        ObjectNode error=products.error(reason,w.path("kind").asText());
        if(a!=null)db.update("UPDATE ingest_attempt SET status='FAILED',error=?::jsonb,finished_at=clock_timestamp() WHERE id=? AND status='RUNNING'",error.toString(),IngestionStore.id(a));
        db.update("UPDATE ingest_attempt SET status='FAILED',error=?::jsonb,finished_at=clock_timestamp() WHERE job_id=? AND generation=? AND status='RESULT_REPORTED'",error.toString(),IngestionStore.id(j),w.path("generation").asInt());
        db.update("UPDATE ingest_object SET retention='ORPHAN',delete_after=clock_timestamp()+interval '24 hours' WHERE job_id=? AND retention<>'PERMANENT_ASSET' AND state<>'DELETED'",IngestionStore.id(j));
        require(db.update("UPDATE ingest_job SET status='FAILED',last_error=?::jsonb,version=version+1,updated_at=clock_timestamp() WHERE id=? AND generation=? AND status IN ('QUEUED','RUNNING','VALIDATING')",error.toString(),IngestionStore.id(j),w.path("generation").asInt())==1,409,"ATTEMPT_STALE");
    }
    public void failQueued(UUID id,boolean local,String reason) {
        db.transaction(() -> {ObjectNode w=work(id,local,false),j=parent(w,true);work(id,local,true);
            boolean pending=(local?"VALIDATING":"QUEUED").equals(j.path("status").asText()) && j.path("current_attempt_id").isNull() && j.path("generation").asInt()==w.path("generation").asInt();
            if(pending && attempt(id)==null) {failed(w,j,null,reason);db.update("UPDATE ingest_outbox SET status='FAILED',last_error=?::jsonb,updated_at=clock_timestamp() WHERE id=?",products.error(reason,w.path("kind").asText()).toString(),id);}return null;});
    }
    public ObjectNode stopped(UUID id,JsonNode body) {products.enabled();return stopped(id,body,slots.requestExecutor(),false);}
    public ObjectNode stoppedLocal(UUID id,JsonNode body,String executor) {return stopped(id,body,executor,true);}
    private ObjectNode stopped(UUID id,JsonNode body,String executor,boolean local) {
        long fence=integer(body,"slotFence",1,Long.MAX_VALUE);require(executor.equals(uuid(body,"executorId").toString()) && List.of("EXITED","KILLED").contains(body.path("termination").asText()),400,"INVALID_REQUEST");
        db.transaction(() -> {ObjectNode w=work(id,local,false),j=parent(w,true);work(id,local,true);
            ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=? AND executor_id=? AND slot_fence=? FOR UPDATE",uuid(body,"attemptId"),id,executor,fence);require(a!=null,409,"ATTEMPT_STALE");
            if("RUNNING".equals(a.path("status").asText()) && j.path("current_attempt_id").asText().equals(a.path("id").asText()) && j.path("generation").asInt()==a.path("generation").asInt())failed(w,j,a,"LEASE_LOST");
            db.update("UPDATE ingest_attempt SET stopped_at=clock_timestamp() WHERE id=?",IngestionStore.id(a));return null;});
        slots.stopped("COMPUTE","WORK:"+id,executor,fence);return db.object().put("accepted",true);
    }
    @Scheduled(fixedDelay=10000)
    public void expire() {
        if(!config.isEnabled())return;
        for(ObjectNode w:db.rows("SELECT * FROM ingest_outbox WHERE kind IN ('STANDARDIZE','VALIDATE') AND created_at<clock_timestamp()-(? * interval '1 second') AND status<>'FAILED' AND NOT EXISTS(SELECT 1 FROM ingest_attempt a WHERE a.work_id=ingest_outbox.id) ORDER BY created_at LIMIT 10",config.getQueueDeadlineSeconds()))
            failQueued(IngestionStore.id(w),"VALIDATE".equals(w.path("kind").asText()),"HARD_TIMEOUT");
        for(ObjectNode snapshot:db.rows("SELECT * FROM ingest_attempt WHERE kind IN ('STANDARDIZE','VALIDATE') AND status='RUNNING' AND (lease_until<=clock_timestamp() OR hard_deadline<=clock_timestamp()) ORDER BY lease_until LIMIT 10")) {
            UUID id=uuid(snapshot,"work_id");boolean local="VALIDATE".equals(snapshot.path("kind").asText());
            boolean changed=db.transaction(() -> {ObjectNode w=work(id,local,false),j=parent(w,true);work(id,local,true);ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? FOR UPDATE",IngestionStore.id(snapshot));
                if("RUNNING".equals(a.path("status").asText()) && j.path("current_attempt_id").asText().equals(a.path("id").asText()) && j.path("generation").asInt()==a.path("generation").asInt()
                        && (!IngestionStore.time(a,"lease_until").isAfter(db.now()) || !IngestionStore.time(a,"hard_deadline").isAfter(db.now()))) {failed(w,j,a,"LEASE_LOST");return true;}return false;});
            if(changed)slots.recovery("COMPUTE","WORK:"+id,snapshot.path("executor_id").asText(),snapshot.path("slot_fence").asLong());
        }
    }
}
