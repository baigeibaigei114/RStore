package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** Server-owned confirmation/intent identity. No IO or model calls inside these transactions. */
@Service
public class IngestionJobService {
    /** Implemented by the LOCAL validator, so an incomplete deployment cannot accept new jobs. */
    public interface ValidationRuntime { boolean available(); }
    private final IngestionStore db;
    private final IngestionProperties config;
    private final IngestionService products;
    private final ObjectProvider<ValidationRuntime> validation;
    public IngestionJobService(IngestionStore db, IngestionProperties config, IngestionService products,
                               ObjectProvider<ValidationRuntime> validation) {
        this.db=db; this.config=config; this.products=products; this.validation=validation;
    }
    public boolean available() {
        ValidationRuntime runtime=validation.getIfAvailable();
        return config.isEnabled() && config.isStandardizationEnabled() && runtime!=null && runtime.available();
    }
    private void requireAvailable() { require(available(),503,"SERVICE_UNAVAILABLE"); }
    public ObjectNode job(String owner, UUID id, boolean lock) {
        ObjectNode row=db.one("SELECT * FROM ingest_job WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),id,owner);
        require(row!=null,404,"NOT_FOUND"); return row;
    }
    public ObjectNode confirmation(String owner, UUID id, boolean lock) {
        ObjectNode row=db.one("SELECT * FROM ingest_confirmation WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),id,owner);
        require(row!=null,404,"NOT_FOUND"); return row;
    }
    public static ObjectNode intentDocument(JsonNode confirmation) {
        ObjectNode result=JSON.createObjectNode();
        for(String key:List.of("schemaVersion","archiveSha256","parserVersion","profileVersion","regionSnapshot","sourceGrid","window","calibration","rawCalibration","qualityRules")) {
            require(confirmation.hasNonNull(key),409,"CONFIRMATION_STALE"); result.set(key,confirmation.get(key));
        }
        return result;
    }
    public static void window(JsonNode window,JsonNode grid) {
        require(window.isArray() && window.size()==4,400,"INVALID_GRID");
        for(JsonNode n:window) require(n.isIntegralNumber() && n.canConvertToInt() && n.asInt()>=0,400,"INVALID_GRID");
        require(window.get(2).asInt()>=1 && window.get(2).asInt()<=2048 && window.get(3).asInt()>=1 && window.get(3).asInt()<=2048
                && (long)window.get(0).asInt()+window.get(2).asInt()<=grid.path("width").asInt()
                && (long)window.get(1).asInt()+window.get(3).asInt()<=grid.path("height").asInt(),400,"WINDOW_TOO_LARGE");
    }
    public void activeCapacity(String owner) {
        require(db.count("SELECT count(*) FROM ingest_job WHERE owner_id=? AND status IN ('QUEUED','RUNNING','VALIDATING')",owner)<config.getOwnerActiveJobs(),503,"CAPACITY_BUSY");
        require(db.count("SELECT count(*) FROM ingest_job WHERE status IN ('QUEUED','RUNNING','VALIDATING')")<config.getMaxWaitingWorks(),503,"CAPACITY_BUSY");
        products.queueCapacity();
    }
    public void assetQuota(String owner,long reservation) {
        long total=db.count("SELECT COALESCE(sum(reserved_bytes),0) FROM ingest_object WHERE role IN ('TIFF','MANIFEST') AND state<>'DELETED'");
        long own=db.count("SELECT COALESCE(sum(reserved_bytes),0) FROM ingest_object WHERE role IN ('TIFF','MANIFEST') AND state<>'DELETED' AND owner_id=?",owner);
        require(reservation<=config.getGlobalAssetQuota()-total && reservation<=config.getOwnerAssetQuota()-own,429,"QUOTA_EXCEEDED");
    }
    public IngestionService.Created confirm(String owner,UUID id,JsonNode body) {
        products.enabled(); fields(body,"clientRequestId","confirmationHash"); UUID request=uuid(body,"clientRequestId");
        String hash=body.path("confirmationHash").asText(); require(hash.matches("[0-9a-f]{64}"),400,"INVALID_REQUEST");
        ObjectNode snapshot=confirmation(owner,id,false);
        return db.transaction(() -> {
            products.quotaLock();
            ObjectNode keyOwner=db.one("SELECT * FROM ingest_confirmation WHERE owner_id=? AND confirm_request_id=?",owner,request);
            if(keyOwner!=null) require(keyOwner.path("id").asText().equals(id.toString()) && hash.equals(keyOwner.path("confirmation_hash").asText()),409,"REQUEST_KEY_CONFLICT");
            // A consumed receipt is permanent, even after raw/region/confirmation expiry.
            ObjectNode receipt=confirmation(owner,id,false);
            if("CONSUMED".equals(receipt.path("status").asText())) {
                require(hash.equals(receipt.path("confirmation_hash").asText()),409,"REQUEST_KEY_CONFLICT");
                return new IngestionService.Created(submission(job(owner,uuid(receipt,"job_id"),false),true),true);
            }
            requireAvailable();
            ObjectNode source=products.product(owner,uuid(snapshot,"product_id"),true);
            ObjectNode region=db.one("SELECT id,version FROM monitoring_region WHERE id=? AND owner_id=? FOR SHARE",snapshot.path("monitoring_region_id").asLong(),owner);
            require(region!=null,404,"NOT_FOUND");
            ObjectNode c=confirmation(owner,id,true);
            require("READY".equals(c.path("status").asText()),409,"CONFIRMATION_STALE");
            require(IngestionStore.time(c,"expires_at").isAfter(db.now()),410,"CONFIRMATION_EXPIRED");
            require(hash.equals(c.path("confirmation_hash").asText()),409,"CONFIRMATION_STALE");
            require(region.path("version").asLong()==c.path("region_version").asLong(),409,"REGION_CHANGED");
            products.requireRaw(source); require("READY".equals(source.path("status").asText()),409,"CONFIRMATION_STALE");
            ObjectNode document=parseCanonical(c);
            require(sha(c.path("canonical_document").asText().getBytes(StandardCharsets.UTF_8)).equals(hash)
                    && document.path("productId").asText().equals(source.path("id").asText())
                    && document.path("archiveSha256").asText().equals(source.path("archive_sha256").asText())
                    && document.path("descriptorVersion").asInt()==source.path("descriptor_version").asInt()
                    && document.path("parserVersion").asText().equals(source.path("parser_version").asText())
                    && config.getProfileVersion().equals(source.path("profile_version").asText())
                    && config.getProfileVersion().equals(document.path("profileVersion").asText())
                    && canonical(document.path("sourceGrid")).equals(canonical(source.path("descriptor").path("grid")))
                    && canonical(document.path("calibration")).equals(canonical(source.path("descriptor").path("calibration")))
                    && canonical(document.path("rawCalibration")).equals(canonical(source.path("descriptor").path("rawCalibration")))
                    && canonical(document.path("regionSnapshot")).equals(canonical(c.path("region_snapshot")))
                    && canonical(document.path("window")).equals(canonical(c.path("window"))),409,"CONFIRMATION_STALE");
            window(c.path("window"),c.path("source_grid"));
            String intent=canonical(intentDocument(document)), intentHash=sha(intent.getBytes(StandardCharsets.UTF_8));
            ObjectNode existing=db.one("SELECT * FROM ingest_job WHERE owner_id=? AND intent_hash=? FOR UPDATE",owner,intentHash);
            boolean reused=existing!=null; UUID jobId;
            if(reused) { jobId=IngestionStore.id(existing); if("SUCCEEDED".equals(existing.path("status").asText())) assetUsable(existing); }
            else {
                activeCapacity(owner); assetQuota(owner,Math.min(config.getTiffMaxBytes(),256L<<20)+Math.min(config.getManifestMaxBytes(),65536));
                jobId=UUID.randomUUID();
                db.update("INSERT INTO ingest_job(id,owner_id,origin_product_id,active_product_id,archive_sha256,intent_hash,intent_document,region_snapshot,\"window\",profile_version,image_name,status)"
                        + " VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,'QUEUED')",jobId,owner,IngestionStore.id(source),IngestionStore.id(source),source.path("archive_sha256").asText(),intentHash,intent,
                        c.path("region_snapshot").toString(),c.path("window").toString(),config.getProfileVersion(),c.path("image_name").asText());
                intent("STANDARDIZE",jobId,1);
            }
            require(db.update("UPDATE ingest_confirmation SET status='CONSUMED',confirm_request_id=?,job_id=?,version=version+1,updated_at=clock_timestamp() WHERE id=? AND status='READY'",request,jobId,id)==1,409,"STATE_CONFLICT");
            return new IngestionService.Created(submission(job(owner,jobId,false),reused),reused);
        });
    }
    private ObjectNode parseCanonical(ObjectNode c) {
        try { return (ObjectNode)JSON.readTree(c.path("canonical_document").asText()); }
        catch(java.io.IOException | ClassCastException error) { throw new IngestionProblem(409,"CONFIRMATION_STALE"); }
    }
    public UUID intent(String kind,UUID target,int generation) {
        require(List.of("STANDARDIZE","VALIDATE","PUBLISH").contains(kind),400,"INVALID_REQUEST"); UUID work=UUID.randomUUID();
        ObjectNode payload=db.object().put("schemaVersion",1).put("kind",kind).put("workId",work.toString()).put("targetId",target.toString()).put("generation",generation);
        db.update("INSERT INTO ingest_outbox(id,kind,target_id,generation,dispatch_mode,payload) VALUES (?,?,?,?,?,?::jsonb)",work,kind,target,generation,kind.equals("STANDARDIZE")?"MQ":"LOCAL",payload.toString());
        return work;
    }
    public ObjectNode retry(String owner,UUID id,JsonNode body) {
        products.enabled(); fields(body,"clientRequestId","expectedVersion","productId"); UUID request=uuid(body,"clientRequestId"), product=uuid(body,"productId");
        long version=integer(body,"expectedVersion",1,Long.MAX_VALUE); String hash=sha(body);
        return db.transaction(() -> {
            products.quotaLock(); ObjectNode source=products.product(owner,product,true); ObjectNode j=job(owner,id,true);
            JsonNode prior=j.path("request_receipts").path(request.toString());
            if(!prior.isMissingNode()) { require(hash.equals(prior.path("hash").asText()),409,"REQUEST_KEY_CONFLICT"); return (ObjectNode)prior.path("response"); }
            requireAvailable();
            if(j.path("version").asLong()!=version) throw new IngestionProblem(409,"STATE_CONFLICT",j.path("version").asLong());
            require("FAILED".equals(j.path("status").asText()) && j.path("generation").asInt()<3,409,"STATE_CONFLICT");
            products.requireRaw(source); require("READY".equals(source.path("status").asText()),409,"STATE_CONFLICT");
            require(j.path("archive_sha256").asText().equals(source.path("archive_sha256").asText()) && config.getProfileVersion().equals(source.path("profile_version").asText()),409,"INPUT_HASH_MISMATCH");
            JsonNode doc=db.parse(j.path("intent_document").asText());
            require(canonical(doc.path("sourceGrid")).equals(canonical(source.path("descriptor").path("grid")))
                    && canonical(doc.path("calibration")).equals(canonical(source.path("descriptor").path("calibration")))
                    && canonical(doc.path("rawCalibration")).equals(canonical(source.path("descriptor").path("rawCalibration")))
                    && doc.path("parserVersion").asText().equals(source.path("parser_version").asText()),409,"INPUT_HASH_MISMATCH");
            activeCapacity(owner); assetQuota(owner,Math.min(config.getTiffMaxBytes(),256L<<20)+Math.min(config.getManifestMaxBytes(),65536));
            int generation=j.path("generation").asInt()+1;
            require(db.update("UPDATE ingest_job SET status='QUEUED',generation=?,active_product_id=?,current_attempt_id=NULL,candidate_artifacts=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=? AND status='FAILED'",generation,product,id,version)==1,409,"STATE_CONFLICT");
            intent("STANDARDIZE",id,generation);
            ObjectNode response=submission(job(owner,id,false),false); response.put("version",version+1).put("generation",generation);
            ObjectNode receipts=j.withObject("request_receipts").deepCopy(); receipts.set(request.toString(),db.object().put("hash",hash).set("response",response));
            db.update("UPDATE ingest_job SET request_receipts=?::jsonb WHERE id=?",receipts.toString(),id); return response;
        });
    }
    public void assetUsable(ObjectNode j) {
        String state=assetStatus(j);
        require(!"UNAVAILABLE".equals(state),410,"ASSET_UNAVAILABLE");
        require("AVAILABLE".equals(state),409,"ASSET_CHANGED");
    }
    /** Historical job reads report asset state; only reuse/download/publication require usability. */
    private String assetStatus(ObjectNode j) {
        ObjectNode image=db.one("SELECT id,owner_id,status,deleted_at,metadata_json,minio_bucket,object_key FROM rs_image WHERE id=? AND owner_id=?",j.path("image_id").asLong(),j.path("owner_id").asText());
        if(image==null || !image.path("deleted_at").isNull() || List.of("DELETED","DELETE_LOCKED").contains(image.path("status").asText())) return "UNAVAILABLE";
        JsonNode admission=image.path("metadata_json").path("admission");
        if(!"READY".equals(image.path("status").asText()) || !"PASSED".equals(admission.path("status").asText()) || !"input-v1".equals(admission.path("validatorVersion").asText())) return "CHANGED";
        ObjectNode tiff=db.one("SELECT * FROM ingest_object WHERE job_id=? AND role='TIFF' AND retention='PERMANENT_ASSET' AND state='AVAILABLE'",IngestionStore.id(j));
        return tiff!=null && tiff.path("sha256").asText().equals(admission.path("manifest").path("sha256").asText())
                && tiff.path("server_key").asText().equals(image.path("object_key").asText()) && tiff.path("bucket").asText().equals(image.path("minio_bucket").asText())?"AVAILABLE":"CHANGED";
    }
    private ObjectNode submission(ObjectNode j,boolean reused) {
        if("SUCCEEDED".equals(j.path("status").asText())) assetUsable(j);
        return jobResult(j).put("reused",reused);
    }
    private ObjectNode jobResult(ObjectNode j) {
        ObjectNode pub=db.one("SELECT * FROM ingest_publication WHERE job_id=? AND owner_id=?",IngestionStore.id(j),j.path("owner_id").asText());
        ObjectNode result=db.object().put("jobId",j.path("id").asText()).put("status",j.path("status").asText()).put("overallStatus",overall(j,pub));
        result.set("imageId",j.path("image_id"));result.set("publicationId",pub==null?db.tree(null):pub.path("id"));return result;
    }
    private String overall(ObjectNode j,ObjectNode pub) {
        if("FAILED".equals(j.path("status").asText())) return "FAILED";
        if(!"SUCCEEDED".equals(j.path("status").asText())) return "STANDARDIZING";
        if(pub!=null && "AVAILABLE".equals(pub.path("status").asText())) return "COMPLETE";
        return pub!=null && "FAILED".equals(pub.path("status").asText())?"PUBLISH_FAILED":"INGESTED_PUBLISH_PENDING";
    }
    public ObjectNode view(String owner,UUID id) { products.enabled();return view(job(owner,id,false)); }
    private ObjectNode view(ObjectNode j) {
        ObjectNode result=jobResult(j);result.put("assetStatus","SUCCEEDED".equals(j.path("status").asText())?assetStatus(j):null);
        result.put("version",j.path("version").asLong()).put("generation",j.path("generation").asInt());
        String stage=switch(j.path("status").asText()) {case "QUEUED","RUNNING" -> "STANDARDIZE";case "VALIDATING" -> "VALIDATE";case "SUCCEEDED" -> "PUBLISH";default -> j.path("last_error").path("stage").asText("STANDARDIZE");};
        result.put("stage",stage);for(String[] pair:new String[][]{{"active_product_id","productId"},{"region_snapshot","regionSnapshot"},{"window","window"},{"last_error","error"}})result.set(pair[1],j.path(pair[0]));
        ObjectNode pub=db.one("SELECT status FROM ingest_publication WHERE job_id=? AND owner_id=?",IngestionStore.id(j),j.path("owner_id").asText());
        result.set("publicationStatus",pub==null?db.tree(null):pub.path("status"));
        for(String key:List.of("regionPixelCount","regionValidReflectanceCount"))result.set(key,j.path("candidate_artifacts").path(key));
        var actions=result.putArray("allowedActions");if("FAILED".equals(j.path("status").asText()) && j.path("generation").asInt()<3)actions.add("RETRY");
        return result;
    }
    public ObjectNode list(String owner,int page,int pageSize) {
        products.enabled();require(page>=1 && page<=100000 && pageSize>=1 && pageSize<=50,400,"INVALID_REQUEST");
        ObjectNode result=db.object().put("page",page).put("pageSize",pageSize).put("total",db.count("SELECT count(*) FROM ingest_job WHERE owner_id=?",owner));
        var rows=result.putArray("items");for(ObjectNode j:db.rows("SELECT * FROM ingest_job WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",owner,pageSize,(page-1)*pageSize))rows.add(view(j));return result;
    }
    public JsonNode publication(String owner,UUID id) {
        products.enabled();ObjectNode j=job(owner,id,false);
        ObjectNode p=db.one("SELECT * FROM ingest_publication WHERE job_id=? AND owner_id=?",id,owner);if(p==null)return db.tree(null);
        assetUsable(j);
        ObjectNode result=db.object().put("publicationId",p.path("id").asText()).put("imageId",p.path("image_id").asLong())
                .put("status",p.path("status").asText()).put("version",p.path("version").asLong()).putNull("layerId").putNull("wmsPath").putNull("wcsPath");
        result.set("error",p.path("last_error"));return result;
    }
}
