package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.publishing.IngestionMapCopyStorage;
import com.remotesensing.platform.ingestion.runtime.ComputeSlots;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** LOCAL only: no controller or Worker callback exposes these attempt operations. */
@Service
public class IngestionPublicationWorkService {
    private final IngestionStore db;private final IngestionProperties config;private final IngestionService products;
    private final IngestionJobService jobs;private final IngestionLayerService layers;private final ComputeSlots slots;
    public IngestionPublicationWorkService(IngestionStore db,IngestionProperties config,IngestionService products,IngestionJobService jobs,IngestionLayerService layers,ComputeSlots slots){
        this.db=db;this.config=config;this.products=products;this.jobs=jobs;this.layers=layers;this.slots=slots;
    }
    public ObjectNode work(UUID id,boolean lock){
        ObjectNode w=db.one("SELECT * FROM ingest_outbox WHERE id=?"+(lock?" FOR UPDATE":""),id);
        require(w!=null && "PUBLISH".equals(w.path("kind").asText()) && "LOCAL".equals(w.path("dispatch_mode").asText()),404,"NOT_FOUND");return w;
    }
    private ObjectNode parent(ObjectNode w,boolean lock){
        ObjectNode p=db.one("SELECT * FROM ingest_publication WHERE id=?",uuid(w,"target_id"));require(p!=null,404,"NOT_FOUND");
        if(lock)jobs.job(p.path("owner_id").asText(),uuid(p,"job_id"),true);
        return lock?layers.publication(p.path("owner_id").asText(),IngestionStore.id(p),true):p;
    }
    private ObjectNode attempt(UUID id){return db.one("SELECT * FROM ingest_attempt WHERE work_id=?",id);}
    private boolean queued(ObjectNode w,ObjectNode p){return "PENDING".equals(p.path("status").asText()) && p.path("current_attempt_id").isNull()
            && p.path("generation").asInt()==w.path("generation").asInt() && !"FAILED".equals(w.path("status").asText());}
    public ObjectNode claim(UUID id,UUID request,String executor){
        layers.enabled();UUID.fromString(executor);ObjectNode w=work(id,false),p=parent(w,false),old=attempt(id);
        if(old!=null){
            if(executor.equals(old.path("executor_id").asText()) && request.toString().equals(old.path("claim_request_id").asText()) && "RUNNING".equals(old.path("status").asText())){
                try{guard(id,identity(old),executor,false);return response(old);}catch(com.remotesensing.platform.ingestion.api.IngestionProblem stale){return db.object().put("action","SKIP");}
            }
            return db.object().put("action","SKIP");
        }
        if(!queued(w,p))return db.object().put("action","SKIP");
        if(!IngestionStore.time(w,"created_at").plusSeconds(config.getQueueDeadlineSeconds()).isAfter(db.now())){failQueued(id,"HARD_TIMEOUT");return db.object().put("action","SKIP");}
        ComputeSlots.Lease permit=slots.reserve("COMPUTE","WORK:"+id,executor,Math.min(120,config.getPublishDeadlineSeconds()));
        if(permit==null)return db.object().put("action","BUSY");
        boolean own=!TransactionSynchronizationManager.isActualTransactionActive();boolean[] rollback={false};
        try{
            ObjectNode result=db.transaction(()->{
                if(own && TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){rollback[0]=status==STATUS_ROLLED_BACK;}});
                products.quotaLock();ObjectNode current=parent(w,true),out=work(id,true);
                if(!queued(out,current) || attempt(id)!=null)return db.object().put("action","SKIP");
                var asset=layers.asset(current,true);UUID aid=UUID.randomUUID();
                ObjectNode map=copy(current,false);
                if(map==null){
                    long bytes=asset.tiff().path("actual_bytes").asLong();require(bytes>0 && bytes<=(256L<<20),409,"ASSET_CHANGED");
                    long reserved=db.count("SELECT COALESCE(sum(reserved_bytes),0) FROM ingest_object WHERE role='MAP_COPY' AND state<>'DELETED'");
                    require(bytes<=Math.min(4L<<30,config.getMapCopyQuota())-reserved,429,"QUOTA_EXCEEDED");
                }else validCopy(asset,map);
                require(db.update("INSERT INTO ingest_attempt(id,owner_id,kind,publication_id,generation,work_id,claim_request_id,slot_fence,executor_id,lease_until,hard_deadline) VALUES (?,?,'PUBLISH',?,?,?,?,?,?,LEAST(?::timestamptz,clock_timestamp()+(? * interval '1 second')),?::timestamptz)",
                        aid,current.path("owner_id").asText(),IngestionStore.id(current),current.path("generation").asInt(),id,request,permit.fence(),executor,permit.hardDeadline().toString(),config.getLeaseSeconds(),permit.hardDeadline().toString())==1,409,"ATTEMPT_STALE");
                if(map==null)require(db.update("INSERT INTO ingest_object(id,owner_id,job_id,publication_id,attempt_id,role,storage,server_key,reserved_bytes,retention) VALUES (?,?,?,?,?,'MAP_COPY','SHARED_MAP',?,?,'PERMANENT_ASSET')",
                        UUID.randomUUID(),current.path("owner_id").asText(),uuid(current,"job_id"),IngestionStore.id(current),aid,IngestionMapCopyStorage.key(asset),asset.tiff().path("actual_bytes").asLong())==1,409,"STATE_CONFLICT");
                require(db.update("UPDATE ingest_publication SET status='PUBLISHING',current_attempt_id=?,version=version+1,updated_at=clock_timestamp() WHERE id=? AND status='PENDING' AND current_attempt_id IS NULL AND generation=?",aid,IngestionStore.id(current),out.path("generation").asInt())==1,409,"ATTEMPT_STALE");
                require(db.update("UPDATE ingest_outbox SET status='SENT',sent_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",id)==1,409,"ATTEMPT_STALE");return response(attempt(id));
            });
            if(!"EXECUTE".equals(result.path("action").asText()))slots.stopped("COMPUTE","WORK:"+id,executor,permit.fence());return result;
        }catch(RuntimeException error){
            if(rollback[0]){slots.stopped("COMPUTE","WORK:"+id,executor,permit.fence());failQueued(id,error instanceof com.remotesensing.platform.ingestion.api.IngestionProblem problem?problem.reason:"PUBLISH_FAILED");}
            else slots.recovery("COMPUTE","WORK:"+id,executor,permit.fence());throw error;
        }
    }
    private ObjectNode response(ObjectNode a){return identity(a).put("action","EXECUTE").put("hardDeadline",a.path("hard_deadline").asText());}
    private ObjectNode identity(ObjectNode a){return db.object().put("attemptId",a.path("id").asText()).put("slotFence",a.path("slot_fence").asLong());}
    public ObjectNode copy(ObjectNode p,boolean lock){return db.one("SELECT * FROM ingest_object WHERE publication_id=? AND owner_id=? AND role='MAP_COPY'"+(lock?" FOR UPDATE":""),IngestionStore.id(p),p.path("owner_id").asText());}
    private void validCopy(IngestionLayerService.Asset a,ObjectNode map){
        require(map!=null && map.path("job_id").equals(a.publication().path("job_id")) && map.path("publication_id").equals(a.publication().path("id")) && map.path("owner_id").equals(a.publication().path("owner_id"))
                && "SHARED_MAP".equals(map.path("storage").asText()) && "PERMANENT_ASSET".equals(map.path("retention").asText()) && Set.of("RESERVED","AVAILABLE").contains(map.path("state").asText())
                && map.path("server_key").asText().equals(IngestionMapCopyStorage.key(a)) && map.path("reserved_bytes").asLong()==a.tiff().path("actual_bytes").asLong(),409,"ASSET_CHANGED");
        if("AVAILABLE".equals(map.path("state").asText()))require(map.path("actual_bytes").equals(a.tiff().path("actual_bytes")) && map.path("sha256").equals(a.tiff().path("sha256")),409,"ASSET_CHANGED");
    }
    public IngestionLayerService.Asset guard(UUID id,JsonNode identity,String executor,boolean lock){
        ObjectNode w=work(id,false),p=parent(w,lock);if(lock)work(id,true);
        ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=?"+(lock?" FOR UPDATE":""),uuid(identity,"attemptId"),id);
        require(a!=null && "PUBLISH".equals(a.path("kind").asText()) && "RUNNING".equals(a.path("status").asText()) && executor.equals(a.path("executor_id").asText())
                && a.path("slot_fence").asLong()==integer(identity,"slotFence",1,Long.MAX_VALUE) && a.path("publication_id").equals(p.path("id")) && a.path("owner_id").equals(p.path("owner_id"))
                && "PUBLISHING".equals(p.path("status").asText()) && p.path("current_attempt_id").equals(a.path("id")) && p.path("generation").asInt()==a.path("generation").asInt() && w.path("generation").asInt()==a.path("generation").asInt()
                && IngestionStore.time(a,"lease_until").isAfter(db.now()) && IngestionStore.time(a,"hard_deadline").isAfter(db.now()) && slots.valid("COMPUTE","WORK:"+id,executor,a.path("slot_fence").asLong()),409,"ATTEMPT_STALE");
        return layers.asset(p,lock);
    }
    public void heartbeat(UUID id,JsonNode identity,String executor,long seq){
        db.transaction(()->{guard(id,identity,executor,true);ObjectNode a=attempt(id);require(seq>=a.path("heartbeat_seq").asLong(),409,"ATTEMPT_STALE");
            if(seq>a.path("heartbeat_seq").asLong()){
                require(slots.renew("COMPUTE","WORK:"+id,executor,a.path("slot_fence").asLong()),409,"ATTEMPT_STALE");
                require(db.update("UPDATE ingest_attempt SET heartbeat_seq=?,lease_until=LEAST(hard_deadline,clock_timestamp()+(? * interval '1 second')) WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()",seq,config.getLeaseSeconds(),IngestionStore.id(a))==1,409,"ATTEMPT_STALE");
            }return null;});
    }
    public boolean completed(UUID id,JsonNode identity,String executor,String digest){
        ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=? AND executor_id=? AND slot_fence=?",uuid(identity,"attemptId"),id,executor,integer(identity,"slotFence",1,Long.MAX_VALUE));
        return a!=null && "SUCCEEDED".equals(a.path("status").asText()) && !a.path("result_receipt").isNull() && digest.equals(a.path("result_digest").asText());
    }
    public static final class CompletionRolledBack extends IngestionProblem {
        public CompletionRolledBack(RuntimeException error) {
            super(error instanceof IngestionProblem problem ? problem.status : 503,
                    error instanceof IngestionProblem problem ? problem.reason : "PUBLISH_FAILED");
            initCause(error);
        }
    }
    public void complete(UUID id,JsonNode identity,String executor,String fingerprint,String pngDigest){
        String digest=sha(db.object().put("asset",fingerprint).put("png",pngDigest));if(completed(id,identity,executor,digest))return;
        boolean[] rolledBack={false};
        try {
            db.independent(()->{
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void afterCompletion(int status) {
                        rolledBack[0]=status==STATUS_ROLLED_BACK;
                    }
                });
                var asset=guard(id,identity,executor,true);require(asset.fingerprint().equals(fingerprint),409,"ASSET_CHANGED");
                ObjectNode map=copy(asset.publication(),true);validCopy(asset,map);
                require(slots.renew("COMPUTE","WORK:"+id,executor,identity.path("slotFence").asLong()),409,"ATTEMPT_STALE");
                require(db.update("UPDATE ingest_object SET state='AVAILABLE',actual_bytes=?,sha256=? WHERE id=? AND state IN ('RESERVED','AVAILABLE')",asset.tiff().path("actual_bytes").asLong(),asset.tiff().path("sha256").asText(),IngestionStore.id(map))==1,409,"ATTEMPT_STALE");
                require(db.update("UPDATE ingest_publication SET status='AVAILABLE',verified_at=clock_timestamp(),last_error=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=? AND status='PUBLISHING' AND generation=? AND current_attempt_id=?",
                        IngestionStore.id(asset.publication()),asset.publication().path("generation").asInt(),uuid(identity,"attemptId"))==1,409,"ATTEMPT_STALE");
                var receipt=db.object().put("accepted",true).put("receiptId",identity.path("attemptId").asText()).put("targetStatus","AVAILABLE").put("pngSha256",pngDigest).put("assetFingerprint",fingerprint);
                require(db.update("UPDATE ingest_attempt SET status='SUCCEEDED',finished_at=clock_timestamp(),result_digest=?,result_receipt=?::jsonb WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()",digest,receipt.toString(),uuid(identity,"attemptId"))==1,409,"ATTEMPT_STALE");return null;
            });
        } catch (RuntimeException error) {
            if(rolledBack[0])throw new CompletionRolledBack(error);
            throw error;
        }
    }
    private void failed(ObjectNode p,ObjectNode a,String reason){
        ObjectNode error=products.error(reason,"PUBLISH").put("retryable",Set.of("PUBLISH_FAILED","PUBLISH_UNCERTAIN","SERVICE_UNAVAILABLE","RESOURCE_LIMIT","LEASE_LOST","HARD_TIMEOUT").contains(reason));
        if(a!=null)require(db.update("UPDATE ingest_attempt SET status='FAILED',error=?::jsonb,finished_at=clock_timestamp() WHERE id=? AND status='RUNNING'",error.toString(),IngestionStore.id(a))==1,409,"ATTEMPT_STALE");
        require(db.update("UPDATE ingest_publication SET status='FAILED',last_error=?::jsonb,version=version+1,updated_at=clock_timestamp() WHERE id=? AND generation=? AND status IN ('PENDING','PUBLISHING')",error.toString(),IngestionStore.id(p),p.path("generation").asInt())==1,409,"ATTEMPT_STALE");
    }
    public void fail(UUID id,JsonNode identity,String executor,String reason){
        String safe=Set.of("PUBLISH_FAILED","PUBLISH_UNCERTAIN","RESOURCE_LIMIT","INPUT_HASH_MISMATCH","ASSET_CHANGED","ASSET_UNAVAILABLE","HARD_TIMEOUT","LEASE_LOST","ATTEMPT_STALE","SERVICE_UNAVAILABLE").contains(reason)?reason:"PUBLISH_FAILED";
        db.transaction(()->{
            ObjectNode w=work(id,false),p=parent(w,true);work(id,true);
            ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=? AND executor_id=? AND slot_fence=? FOR UPDATE",uuid(identity,"attemptId"),id,executor,identity.path("slotFence").asLong());
            require(a!=null && p.path("id").equals(a.path("publication_id"))
                    && w.path("generation").asInt()==a.path("generation").asInt(),409,"ATTEMPT_STALE");
            // The scanner may already have committed this attempt's failure.
            if("FAILED".equals(a.path("status").asText()))return null;
            require("RUNNING".equals(a.path("status").asText()) && p.path("current_attempt_id").equals(a.path("id"))
                    && p.path("generation").asInt()==a.path("generation").asInt() && "PUBLISHING".equals(p.path("status").asText()),409,"ATTEMPT_STALE");
            failed(p,a,safe);
            return null;
        });
    }
    public void stopped(UUID id,JsonNode identity,String executor){
        db.transaction(()->{ObjectNode w=work(id,false),p=parent(w,true);work(id,true);
            ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? AND work_id=? AND executor_id=? AND slot_fence=? FOR UPDATE",uuid(identity,"attemptId"),id,executor,identity.path("slotFence").asLong());require(a!=null,409,"ATTEMPT_STALE");
            require(!"RUNNING".equals(a.path("status").asText()),409,"ATTEMPT_STALE");
            require(db.update("UPDATE ingest_attempt SET stopped_at=clock_timestamp() WHERE id=?",IngestionStore.id(a))==1,409,"ATTEMPT_STALE");return null;});
        slots.stopped("COMPUTE","WORK:"+id,executor,identity.path("slotFence").asLong());
    }
    public void failQueued(UUID id,String reason){
        db.transaction(()->{ObjectNode w=work(id,false),p=parent(w,true);work(id,true);
            if(queued(w,p) && attempt(id)==null){failed(p,null,reason);db.update("UPDATE ingest_outbox SET status='FAILED',last_error=?::jsonb,updated_at=clock_timestamp() WHERE id=?",products.error(reason,"PUBLISH").toString(),id);}return null;});
    }
    @Scheduled(fixedDelay=10000) public void expire(){
        if(!config.isEnabled())return;
        if(config.isPublicationEnabled())for(ObjectNode w:db.rows("SELECT * FROM ingest_outbox w WHERE kind='PUBLISH' AND status<>'FAILED' AND created_at<clock_timestamp()-(? * interval '1 second') AND NOT EXISTS(SELECT 1 FROM ingest_attempt a WHERE a.work_id=w.id) ORDER BY created_at LIMIT 10",config.getQueueDeadlineSeconds()))failQueued(IngestionStore.id(w),"HARD_TIMEOUT");
        for(ObjectNode a:db.rows("SELECT * FROM ingest_attempt WHERE kind='PUBLISH' AND status='RUNNING' AND (lease_until<=clock_timestamp() OR hard_deadline<=clock_timestamp()) ORDER BY lease_until LIMIT 10")){
            UUID id=uuid(a,"work_id");
            boolean changed=db.transaction(()->{
                ObjectNode w=work(id,false),p=parent(w,true);work(id,true);
                ObjectNode current=db.one("SELECT * FROM ingest_attempt WHERE id=? FOR UPDATE",IngestionStore.id(a));
                if(current!=null && "RUNNING".equals(current.path("status").asText()) && "PUBLISHING".equals(p.path("status").asText()) && p.path("current_attempt_id").equals(current.path("id"))
                        && p.path("generation").asInt()==current.path("generation").asInt() && (!IngestionStore.time(current,"lease_until").isAfter(db.now()) || !IngestionStore.time(current,"hard_deadline").isAfter(db.now()))){failed(p,current,"LEASE_LOST");return true;}return false;
            });
            if(changed)slots.recovery("COMPUTE","WORK:"+id,a.path("executor_id").asText(),a.path("slot_fence").asLong());
        }
    }
}
