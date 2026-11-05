package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.ComputeSlots;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** Owner/confirmation/quota state. File IO is always outside these short transactions. */
@Service
public class IngestionService {
    public record Created(ObjectNode data, boolean reused) {}
    public record Upload(UUID productId, UUID attemptId, String owner, String executor, long fence,
                         Instant deadline, long expectedBytes, ObjectNode object) {}
    private final IngestionStore db;
    private final IngestionProperties config;
    private final IngestionStorage storage;
    private final ComputeSlots slots;
    public IngestionService(IngestionStore db, IngestionProperties config, IngestionStorage storage, ComputeSlots slots) {
        this.db=db; this.config=config; this.storage=storage; this.slots=slots;
    }
    public void enabled() { require(config.isEnabled(),503,"SERVICE_UNAVAILABLE"); }
    public ObjectNode product(String owner, UUID id, boolean lock) {
        ObjectNode p=db.one("SELECT * FROM ingest_product WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),id,owner);
        require(p!=null,404,"NOT_FOUND"); return p;
    }
    public ObjectNode object(UUID id) {
        ObjectNode result=db.one("SELECT * FROM ingest_object WHERE id=?",id);
        require(result!=null,404,"NOT_FOUND"); return result;
    }
    public void quotaLock() { db.one("SELECT * FROM ingest_runtime_slot WHERE slot_key='QUOTA' FOR UPDATE"); }
    public void quota(String owner, long bytes) {
        long global=db.count("SELECT COALESCE(sum(reserved_bytes),0) FROM ingest_object WHERE role IN ('RAW','PREVIEW') AND state<>'DELETED'");
        long owned=db.count("SELECT COALESCE(sum(reserved_bytes),0) FROM ingest_object WHERE role IN ('RAW','PREVIEW') AND state<>'DELETED' AND owner_id=?",owner);
        require(global+bytes<=config.getGlobalRawQuota() && owned+bytes<=config.getOwnerRawQuota(),429,"QUOTA_EXCEEDED");
    }
    public void queueCapacity() {
        long count=db.count("SELECT (SELECT count(*) FROM ingest_outbox w WHERE (w.kind IN ('INSPECT','PREFLIGHT','STANDARDIZE','VALIDATE') OR (? AND w.kind='PUBLISH')) AND w.status<>'FAILED'"
                + " AND NOT EXISTS(SELECT 1 FROM ingest_attempt a WHERE a.work_id=w.id AND a.status IN ('RESULT_REPORTED','SUCCEEDED','FAILED')))"
                + "+(SELECT count(*) FROM ingest_product WHERE status='UPLOADING')",config.isPublicationEnabled());
        require(count<config.getMaxWaitingWorks(),503,"CAPACITY_BUSY");
    }
    public Created create(String owner, JsonNode body) {
        enabled(); UUID request=uuid(body,"clientRequestId"); String name=name(body,"name",100), file=name(body,"fileName",255);
        long size=integer(body,"sizeBytes",1,Math.min(config.getArchiveMaxBytes(),2L<<30));
        storage.ensurePrivate();
        return db.transaction(() -> {
            quotaLock();
            ObjectNode old=db.one("SELECT * FROM ingest_product WHERE owner_id=? AND create_request_id=?",owner,request);
            if(old!=null) {
                require(old.path("name").asText().equals(name) && old.path("file_name").asText().equals(file) && old.path("expected_bytes").asLong()==size,409,"REQUEST_KEY_CONFLICT");
                return new Created(createdView(old),true);
            }
            quota(owner,size);
            UUID id=UUID.randomUUID(), raw=UUID.randomUUID();
            db.update("INSERT INTO ingest_product(id,owner_id,create_request_id,name,file_name,expected_bytes,status,raw_state,upload_expires_at)"
                    + " VALUES (?,?,?,?,?,?,'AWAITING_UPLOAD','RESERVED',clock_timestamp()+(? * interval '1 second'))",id,owner,request,name,file,size,config.getUploadReservationSeconds());
            reserveRaw(id,owner,raw,size);
            db.update("UPDATE ingest_product SET raw_object_id=? WHERE id=?",raw,id);
            return new Created(createdView(product(owner,id,false)),false);
        });
    }
    private void reserveRaw(UUID product, String owner, UUID raw, long bytes) {
        db.update("INSERT INTO ingest_object(id,owner_id,product_id,role,storage,server_key,bucket,state,reserved_bytes,retention)"
                + " VALUES (?,?,?,'RAW','MINIO',?,?,'RESERVED',?,'RAW_7D')",raw,owner,product,
                "ingestion/raw/"+product+"/"+raw+"/product.zip",storage.bucket(),bytes);
    }
    private ObjectNode createdView(ObjectNode p) {
        return db.object().put("productId",p.path("id").asText()).put("status",p.path("status").asText()).put("version",p.path("version").asLong())
                .put("uploadPath","/api/ingestion/products/"+p.path("id").asText()+"/content")
                .put("uploadExpiresAt",p.path("upload_expires_at").asText()).set("limits",db.object().put("archiveMaxBytes",Math.min(config.getArchiveMaxBytes(),2L<<30)));
    }
    public Upload startUpload(String owner, UUID productId, long declaredLength) {
        enabled(); ObjectNode snapshot=product(owner,productId,false);
        require(!"UPLOADING".equals(snapshot.path("status").asText()),409,"UPLOAD_IN_PROGRESS");
        require("AWAITING_UPLOAD".equals(snapshot.path("status").asText()),409,"ALREADY_UPLOADED");
        long seconds=Math.min(config.getUploadDeadlineSeconds(),Duration.between(db.now(),IngestionStore.time(snapshot,"upload_expires_at")).getSeconds());
        require(seconds>0,410,"UPLOAD_EXPIRED");
        if(declaredLength>=0) require(declaredLength==snapshot.path("expected_bytes").asLong(),400,"INVALID_REQUEST");
        UUID attempt=UUID.fromString(snapshot.path("raw_object_id").asText()); String executor=UUID.randomUUID().toString();
        ComputeSlots.Lease lease=slots.reserve("UPLOAD","UPLOAD:"+attempt,executor,(int)seconds);
        require(lease!=null,503,"CAPACITY_BUSY");
        try {
            return db.transaction(() -> {
                quotaLock(); queueCapacity(); ObjectNode p=product(owner,productId,true);
                require("AWAITING_UPLOAD".equals(p.path("status").asText()) && p.path("raw_object_id").asText().equals(attempt.toString()),409,"STATE_CONFLICT");
                require(IngestionStore.time(p,"upload_expires_at").isAfter(db.now()),410,"UPLOAD_EXPIRED");
                db.update("INSERT INTO ingest_attempt(id,owner_id,kind,product_id,generation,claim_request_id,slot_fence,executor_id,lease_until,hard_deadline)"
                        + " VALUES (?,?,'UPLOAD',?,?,?,?,?,LEAST(?::timestamptz,clock_timestamp()+(? * interval '1 second')),?::timestamptz)",
                        attempt,owner,productId,p.path("upload_generation").asInt(),UUID.randomUUID(),lease.fence(),executor,lease.hardDeadline().toString(),config.getLeaseSeconds(),lease.hardDeadline().toString());
                db.update("UPDATE ingest_object SET attempt_id=? WHERE id=? AND state='RESERVED'",attempt,attempt);
                db.update("UPDATE ingest_product SET status='UPLOADING',current_attempt_id=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",attempt,productId);
                return new Upload(productId,attempt,owner,executor,lease.fence(),lease.hardDeadline(),p.path("expected_bytes").asLong(),object(attempt));
            });
        } catch(RuntimeException error) {
            // Confirm no attempt was committed before releasing an unused upload reservation.
            ObjectNode committed=db.one("SELECT * FROM ingest_attempt WHERE id=?",attempt);
            if(committed==null) slots.stopped("UPLOAD","UPLOAD:"+attempt,executor,lease.fence());
            else slots.recovery("UPLOAD","UPLOAD:"+attempt,executor,lease.fence());
            throw error;
        }
    }
    public void uploadHeartbeat(Upload upload) {
        db.transaction(() -> {
            ObjectNode p=product(upload.owner(),upload.productId(),true);
            ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? FOR UPDATE",upload.attemptId());
            if(a!=null && "SUCCEEDED".equals(a.path("status").asText())) return null;
            require("UPLOADING".equals(p.path("status").asText()) && p.path("current_attempt_id").asText().equals(upload.attemptId().toString()),409,"ATTEMPT_STALE");
            require(slots.renew("UPLOAD","UPLOAD:"+upload.attemptId(),upload.executor(),upload.fence()),409,"ATTEMPT_STALE");
            int changed=db.update("UPDATE ingest_attempt SET lease_until=LEAST(hard_deadline,clock_timestamp()+(? * interval '1 second')),heartbeat_seq=heartbeat_seq+1"
                    + " WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()",config.getLeaseSeconds(),upload.attemptId());
            require(changed==1,409,"ATTEMPT_STALE"); return null;
        });
    }
    public ObjectNode finishUpload(Upload upload, Path file, long bytes, String digest) {
        require(bytes==upload.expectedBytes() && upload.deadline().isAfter(db.now()),400,"INVALID_REQUEST");
        storage.ensurePrivate();
        storage.upload(upload.object(),file,Duration.between(db.now(),upload.deadline()).getSeconds());
        return db.transaction(() -> {
            quotaLock(); ObjectNode p=product(upload.owner(),upload.productId(),true);
            require("UPLOADING".equals(p.path("status").asText()) && p.path("current_attempt_id").asText().equals(upload.attemptId().toString())
                    && slots.valid("UPLOAD","UPLOAD:"+upload.attemptId(),upload.executor(),upload.fence()),409,"ATTEMPT_STALE");
            require(slots.renew("UPLOAD","UPLOAD:"+upload.attemptId(),upload.executor(),upload.fence()),409,"ATTEMPT_STALE");
            int changed=db.update("UPDATE ingest_attempt SET status='SUCCEEDED',finished_at=clock_timestamp(),result_digest=?"
                    + " WHERE id=? AND status='RUNNING' AND lease_until>clock_timestamp() AND hard_deadline>clock_timestamp()",digest,upload.attemptId());
            require(changed==1,409,"ATTEMPT_STALE");
            db.update("UPDATE ingest_object SET state='AVAILABLE',actual_bytes=?,sha256=?,delete_after=clock_timestamp()+(? * interval '1 day') WHERE id=?",bytes,digest,config.getRawRetentionDays(),upload.attemptId());
            db.update("UPDATE ingest_product SET status='INSPECT_QUEUED',raw_state='AVAILABLE',archive_sha256=?,uploaded_at=clock_timestamp(),"
                    + "expires_at=clock_timestamp()+(? * interval '1 day'),inspect_generation=1,current_attempt_id=NULL,last_error=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=?",digest,config.getRawRetentionDays(),upload.productId());
            intent("INSPECT",upload.productId(),1);
            ObjectNode result=product(upload.owner(),upload.productId(),false);
            return db.object().put("productId",upload.productId().toString()).put("status","INSPECT_QUEUED").put("version",result.path("version").asLong());
        });
    }
    public void abortUpload(Upload upload, boolean storageStarted, String reason) {
        db.transaction(() -> {
            ObjectNode p=product(upload.owner(),upload.productId(),true);
            if("UPLOADING".equals(p.path("status").asText()) && p.path("current_attempt_id").asText().equals(upload.attemptId().toString())) {
                db.update("UPDATE ingest_attempt SET status='FAILED',error=?::jsonb,finished_at=clock_timestamp() WHERE id=? AND status='RUNNING'",error(reason,"UPLOAD").toString(),upload.attemptId());
                db.update("UPDATE ingest_product SET status='FAILED',raw_state='ABSENT',last_error=?::jsonb,version=version+1,updated_at=clock_timestamp() WHERE id=?",error(reason,"UPLOAD").toString(),upload.productId());
                db.update("UPDATE ingest_object SET state=?,retention='ORPHAN',delete_after=clock_timestamp()+interval '24 hours' WHERE id=?",storageStarted?"RESERVED":"DELETED",upload.attemptId());
            }
            return null;
        });
    }
    public void uploadStopped(Upload upload) {
        db.update("UPDATE ingest_attempt SET stopped_at=clock_timestamp() WHERE id=? AND executor_id=? AND slot_fence=?",upload.attemptId(),upload.executor(),upload.fence());
        slots.stopped("UPLOAD","UPLOAD:"+upload.attemptId(),upload.executor(),upload.fence());
    }
    public UUID intent(String kind, UUID target, int generation) {
        UUID work=UUID.randomUUID();
        ObjectNode payload=db.object().put("schemaVersion",1).put("kind",kind).put("workId",work.toString()).put("targetId",target.toString()).put("generation",generation);
        db.update("INSERT INTO ingest_outbox(id,kind,target_id,generation,dispatch_mode,payload) VALUES (?,?,?,?,'MQ',?::jsonb)",work,kind,target,generation,payload.toString());
        return work;
    }
    public void requireRaw(ObjectNode p) {
        require("AVAILABLE".equals(p.path("raw_state").asText()) && !p.path("expires_at").isNull() && IngestionStore.time(p,"expires_at").isAfter(db.now()),410,"RAW_EXPIRED");
        require("AVAILABLE".equals(object(UUID.fromString(p.path("raw_object_id").asText())).path("state").asText()),410,"RAW_EXPIRED");
    }
    public ObjectNode retry(String owner, UUID id, JsonNode body) {
        enabled(); UUID key=uuid(body,"clientRequestId"); long version=integer(body,"expectedVersion",1,Long.MAX_VALUE); String hash=sha(body);
        return db.transaction(() -> {
            quotaLock(); ObjectNode p=product(owner,id,true); JsonNode receipts=p.path("request_receipts");
            JsonNode old=receipts.path(key.toString());
            if(!old.isMissingNode()) { require(old.path("hash").asText().equals(hash),409,"REQUEST_KEY_CONFLICT"); return (ObjectNode)old.path("response"); }
            if(p.path("version").asLong()!=version) throw new IngestionProblem(409,"STATE_CONFLICT",p.path("version").asLong());
            require("FAILED".equals(p.path("status").asText()),409,"STATE_CONFLICT");
            boolean inspect="AVAILABLE".equals(p.path("raw_state").asText());
            String status;
            if(inspect) {
                requireRaw(p); queueCapacity(); int generation=p.path("inspect_generation").asInt()+1; require(generation<=3,409,"STATE_CONFLICT");
                db.update("UPDATE ingest_product SET status='INSPECT_QUEUED',inspect_generation=?,current_attempt_id=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=?",generation,id);
                intent("INSPECT",id,generation); status="INSPECT_QUEUED";
            } else {
                int generation=p.path("upload_generation").asInt()+1; require(generation<=3,409,"STATE_CONFLICT");
                quota(owner,p.path("expected_bytes").asLong()); UUID raw=UUID.randomUUID(); reserveRaw(id,owner,raw,p.path("expected_bytes").asLong());
                db.update("UPDATE ingest_product SET status='AWAITING_UPLOAD',raw_state='RESERVED',raw_object_id=?,upload_generation=?,current_attempt_id=NULL,"
                        + "upload_expires_at=clock_timestamp()+(? * interval '1 second'),version=version+1,updated_at=clock_timestamp() WHERE id=?",raw,generation,config.getUploadReservationSeconds(),id);
                status="AWAITING_UPLOAD";
            }
            ObjectNode response=db.object().put("productId",id.toString()).put("status",status).put("version",version+1);
            ObjectNode next=(ObjectNode)receipts.deepCopy(); next.set(key.toString(),db.object().put("hash",hash).set("response",response));
            db.update("UPDATE ingest_product SET request_receipts=?::jsonb WHERE id=?",next.toString(),id);
            return response;
        });
    }
    public Created preflight(String owner, JsonNode body) {
        enabled(); UUID request=uuid(body,"clientRequestId"), productId=uuid(body,"productId");
        long regionId=integer(body,"monitoringRegionId",1,Long.MAX_VALUE), version=integer(body,"expectedRegionVersion",1,Long.MAX_VALUE);
        String name=name(body,"imageName",100);
        return db.transaction(() -> {
            quotaLock();
            ObjectNode old=db.one("SELECT * FROM ingest_confirmation WHERE owner_id=? AND create_request_id=?",owner,request);
            if(old!=null) {
                require(old.path("product_id").asText().equals(productId.toString()) && old.path("monitoring_region_id").asLong()==regionId
                        && old.path("region_version").asLong()==version && old.path("image_name").asText().equals(name),409,"REQUEST_KEY_CONFLICT");
                return new Created(db.object().put("confirmationId",old.path("id").asText()).put("status",old.path("status").asText()).put("version",old.path("version").asLong()),true);
            }
            queueCapacity(); ObjectNode p=product(owner,productId,true); requireRaw(p); require("READY".equals(p.path("status").asText()),409,"STATE_CONFLICT");
            ObjectNode region=db.one("SELECT id,name,version,ST_AsGeoJSON(geom,15)::jsonb AS geometry FROM monitoring_region WHERE id=? AND owner_id=? FOR SHARE",regionId,owner);
            require(region!=null,404,"NOT_FOUND"); require(region.path("version").asLong()==version,409,"REGION_CHANGED");
            ObjectNode snapshot=db.object().put("schemaVersion",1).put("regionId",regionId).put("name",region.path("name").asText())
                    .put("version",version).put("crs","EPSG:4326").put("pixelRule","center_covered"); snapshot.set("geometry",region.get("geometry"));
            UUID confirmation=UUID.randomUUID();
            db.update("INSERT INTO ingest_confirmation(id,owner_id,create_request_id,product_id,monitoring_region_id,region_version,region_snapshot,image_name) VALUES (?,?,?,?,?,?,?::jsonb,?)",
                    confirmation,owner,request,productId,regionId,version,snapshot.toString(),name);
            intent("PREFLIGHT",confirmation,1);
            return new Created(db.object().put("confirmationId",confirmation.toString()).put("status","PENDING").put("version",1),false);
        });
    }
    public ObjectNode confirmationView(String owner, UUID id) {
        enabled(); ObjectNode row=db.one("SELECT * FROM ingest_confirmation WHERE id=? AND owner_id=?",id,owner); require(row!=null,404,"NOT_FOUND");
        String status=row.path("status").asText();
        if(status.equals("READY") && !IngestionStore.time(row,"expires_at").isAfter(db.now())) status="EXPIRED";
        ObjectNode view=db.object().put("confirmationId",id.toString()).put("status",status).put("version",row.path("version").asLong())
                .put("productId",row.path("product_id").asText()).put("imageName",row.path("image_name").asText());
        for(String[] pair:new String[][]{{"region_snapshot","regionSnapshot"},{"source_grid","sourceGrid"},{"window","window"},{"confirmation_hash","confirmationHash"},{"expires_at","expiresAt"},{"last_error","error"},{"job_id","jobId"}}) view.set(pair[1],row.path(pair[0]));
        ObjectNode p=product(owner,UUID.fromString(row.path("product_id").asText()),false);
        view.set("sourceId",p.path("descriptor").path("sourceId")); view.set("profileVersion",p.path("profile_version"));
        return view;
    }
    public ObjectNode productView(String owner, UUID id) { enabled(); return view(product(owner,id,false)); }
    private ObjectNode view(ObjectNode p) {
        String status=p.path("status").asText();
        if((!p.path("expires_at").isNull() && !p.path("expires_at").isMissingNode() && !IngestionStore.time(p,"expires_at").isAfter(db.now()))
                || (status.equals("AWAITING_UPLOAD") && !IngestionStore.time(p,"upload_expires_at").isAfter(db.now()))) status="EXPIRED";
        ObjectNode view=db.object().put("productId",p.path("id").asText()).put("name",p.path("name").asText()).put("status",status)
                .put("rawState",p.path("raw_state").asText()).put("version",p.path("version").asLong()).put("sizeBytes",p.path("expected_bytes").asLong());
        for(String[] pair:new String[][]{{"expires_at","expiresAt"},{"descriptor_version","descriptorVersion"},{"profile_version","profileVersion"},{"last_error","error"}}) view.set(pair[1],p.path(pair[0]));
        for(String name:List.of("sourceId","captureTime","grid")) view.set(name,p.path("descriptor").path(name));
        view.putNull("footprint"); view.putNull("previewPath");
        if(status.equals("READY")) {
            JsonNode grid=p.path("descriptor").path("grid"); JsonNode tr=grid.path("transform");
            ObjectNode footprint=db.one("SELECT ST_AsGeoJSON(ST_Transform(ST_Segmentize(ST_MakeEnvelope(?,?,?,?,?),1000),4326),15)::jsonb AS geometry",
                    tr.get(2).doubleValue(),tr.get(5).doubleValue()-10*grid.path("height").asInt(),tr.get(2).doubleValue()+10*grid.path("width").asInt(),tr.get(5).doubleValue(),Integer.parseInt(grid.path("crs").asText().substring(5)));
            view.set("footprint",footprint.path("geometry")); view.set("previewGrid",p.path("descriptor").path("previewGrid"));
            view.put("previewPath","/api/ingestion/products/"+p.path("id").asText()+"/preview");
        }
        var actions=view.putArray("allowedActions");
        if(status.equals("AWAITING_UPLOAD")) actions.add("UPLOAD");
        if(status.equals("READY")) actions.add("PREFLIGHT").add("DOWNLOAD");
        if(status.equals("FAILED")) actions.add("RETRY");
        return view;
    }
    public ObjectNode products(String owner, int page, int pageSize) {
        enabled(); require(page>=1 && page<=100000 && pageSize>=1 && pageSize<=50,400,"INVALID_REQUEST");
        ObjectNode result=db.object().put("page",page).put("pageSize",pageSize).put("total",db.count("SELECT count(*) FROM ingest_product WHERE owner_id=?",owner));
        var items=result.putArray("items");
        for(ObjectNode row:db.rows("SELECT * FROM ingest_product WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",owner,pageSize,(page-1)*pageSize)) items.add(view(row));
        return result;
    }
    public ObjectNode downloadable(String owner, UUID id, String role) {
        enabled(); return db.transaction(() -> {
            ObjectNode p=product(owner,id,true); requireRaw(p);
            ObjectNode object=db.one("SELECT * FROM ingest_object WHERE product_id=? AND role=? AND state='AVAILABLE' ORDER BY id LIMIT 1 FOR UPDATE",id,role);
            require(object!=null,404,"NOT_FOUND");
            db.update("UPDATE ingest_object SET read_lease_until=clock_timestamp()+interval '30 minutes' WHERE id=?",IngestionStore.id(object)); return object;
        });
    }
    public ObjectNode error(String reason, String stage) {
        return db.object().put("reason",reason).put("stage",stage).put("retryable",List.of("LEASE_LOST","HARD_TIMEOUT","DISPATCH_FAILED","SERVICE_UNAVAILABLE").contains(reason)).put("message","本阶段未完成，请查看状态后重试");
    }
    @Scheduled(fixedDelay=10000)
    public void expireUnusedReservations() {
        if(!config.isEnabled()) return;
        db.transaction(() -> {
            quotaLock();
            for(ObjectNode p:db.rows("SELECT * FROM ingest_product WHERE status='AWAITING_UPLOAD' AND current_attempt_id IS NULL AND upload_expires_at<=clock_timestamp() ORDER BY id LIMIT 10 FOR UPDATE SKIP LOCKED")) {
                db.update("UPDATE ingest_object SET state='DELETED' WHERE id=? AND state='RESERVED' AND attempt_id IS NULL",UUID.fromString(p.path("raw_object_id").asText()));
                db.update("UPDATE ingest_product SET status='EXPIRED',raw_state='DELETED',version=version+1,updated_at=clock_timestamp() WHERE id=?",IngestionStore.id(p));
            }
            return null;
        });
    }
    @Scheduled(fixedDelay=10000)
    public void expireInterruptedUploads() {
        if(!config.isEnabled()) return;
        for(ObjectNode candidate:db.rows("SELECT * FROM ingest_attempt WHERE kind='UPLOAD' AND status='RUNNING'"
                + " AND (lease_until<=clock_timestamp() OR hard_deadline<=clock_timestamp()) ORDER BY lease_until LIMIT 10")) {
            UUID productId=UUID.fromString(candidate.path("product_id").asText());
            boolean expired=db.transaction(() -> {
                ObjectNode p=product(candidate.path("owner_id").asText(),productId,true);
                ObjectNode a=db.one("SELECT * FROM ingest_attempt WHERE id=? FOR UPDATE",IngestionStore.id(candidate));
                if(!"RUNNING".equals(a.path("status").asText()) || (IngestionStore.time(a,"lease_until").isAfter(db.now()) && IngestionStore.time(a,"hard_deadline").isAfter(db.now()))) return false;
                Upload upload=new Upload(productId,IngestionStore.id(a),a.path("owner_id").asText(),a.path("executor_id").asText(),a.path("slot_fence").asLong(),
                        IngestionStore.time(a,"hard_deadline"),p.path("expected_bytes").asLong(),object(UUID.fromString(p.path("raw_object_id").asText())));
                abortUpload(upload,true,"LEASE_LOST"); return true;
            });
            if(expired) slots.recovery("UPLOAD","UPLOAD:"+candidate.path("id").asText(),candidate.path("executor_id").asText(),candidate.path("slot_fence").asLong());
        }
    }
}
