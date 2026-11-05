package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** Browser retry changes only the publication. Raw expiry never enters this path. */
@Service
public class IngestionPublicationService {
    private final IngestionStore db;private final IngestionService products;private final IngestionJobService jobs;private final IngestionLayerService layers;
    public IngestionPublicationService(IngestionStore db,IngestionService products,IngestionJobService jobs,IngestionLayerService layers){this.db=db;this.products=products;this.jobs=jobs;this.layers=layers;}
    public ObjectNode retry(String owner,UUID jobId,JsonNode body){
        layers.enabled();fields(body,"clientRequestId","expectedVersion");UUID key=uuid(body,"clientRequestId");long version=integer(body,"expectedVersion",1,Long.MAX_VALUE);String hash=sha(body);
        return db.transaction(()->{
            products.quotaLock();jobs.job(owner,jobId,true);
            ObjectNode p=db.one("SELECT * FROM ingest_publication WHERE job_id=? AND owner_id=? FOR UPDATE",jobId,owner);require(p!=null,404,"NOT_FOUND");
            JsonNode receipt=p.path("request_receipts").path(key.toString());
            if(!receipt.isMissingNode()){require(hash.equals(receipt.path("hash").asText()),409,"REQUEST_KEY_CONFLICT");return (ObjectNode)receipt.path("response");}
            if(p.path("version").asLong()!=version)throw new IngestionProblem(409,"STATE_CONFLICT",p.path("version").asLong());
            layers.asset(p,true);
            if(!"FAILED".equals(p.path("status").asText()))return view(p);
            require(p.path("generation").asInt()<3,409,"STATE_CONFLICT");products.queueCapacity();
            int generation=p.path("generation").asInt()+1;
            require(db.update("UPDATE ingest_publication SET status='PENDING',generation=?,current_attempt_id=NULL,verified_at=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=? AND status='FAILED' AND version=?",generation,IngestionStore.id(p),version)==1,409,"STATE_CONFLICT");
            jobs.intent("PUBLISH",IngestionStore.id(p),generation);
            ObjectNode current=layers.publication(owner,IngestionStore.id(p),false),response=view(current);
            ObjectNode receipts=p.withObject("request_receipts").deepCopy();receipts.set(key.toString(),db.object().put("hash",hash).set("response",response));
            require(db.update("UPDATE ingest_publication SET request_receipts=?::jsonb WHERE id=? AND generation=?",receipts.toString(),IngestionStore.id(p),generation)==1,409,"STATE_CONFLICT");return response;
        });
    }
    public static ObjectNode view(ObjectNode p){
        ObjectNode result=JSON.createObjectNode().put("publicationId",p.path("id").asText()).put("imageId",p.path("image_id").asLong())
                .put("status",p.path("status").asText()).put("version",p.path("version").asLong()).put("generation",p.path("generation").asInt())
                .putNull("layerId").putNull("wmsPath").putNull("wcsPath");
        result.set("error",p.path("last_error"));
        if("AVAILABLE".equals(p.path("status").asText()) && !p.path("verified_at").isNull())result.setAll(IngestionLayerService.paths(p));return result;
    }
}
