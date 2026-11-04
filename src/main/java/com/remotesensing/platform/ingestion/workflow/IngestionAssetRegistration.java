package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.mapper.RsImageMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** Called within the validated LOCAL attempt's transaction. No IO, upload, or afterCommit-only intent. */
@Service
public class IngestionAssetRegistration {
    private final IngestionStore db;
    private final RsImageMapper images;
    private final IngestionJobService jobs;
    public IngestionAssetRegistration(IngestionStore db,RsImageMapper images,IngestionJobService jobs) {this.db=db;this.images=images;this.jobs=jobs;}
    public ObjectNode register(ObjectNode job,ObjectNode attempt,IngestionValidationService.Validated actual) {
        require("VALIDATING".equals(job.path("status").asText()) && job.path("image_id").isNull()
                && sha(job.path("candidate_artifacts")).equals(actual.candidateHash()),409,"ATTEMPT_STALE");
        JsonNode metadata=JSON.valueToTree(actual.metadata());
        require("PASSED".equals(metadata.path("admission").path("status").asText())
                && actual.tiff().path("sha256").asText().equals(metadata.path("admission").path("manifest").path("sha256").asText()),400,"ADMISSION_REJECTED");
        // Re-lock the exact candidate locations before converting them into permanent asset references.
        for(ObjectNode expected:List.of(actual.tiff(),actual.manifest())) {
            ObjectNode current=db.one("SELECT * FROM ingest_object WHERE id=? AND job_id=? AND owner_id=? FOR UPDATE",IngestionStore.id(expected),IngestionStore.id(job),job.path("owner_id").asText());
            require(current!=null && "RESERVED".equals(current.path("state").asText()) && current.path("attempt_id").equals(expected.path("attempt_id"))
                    && current.path("server_key").equals(expected.path("server_key")) && current.path("bucket").equals(expected.path("bucket"))
                    && current.path("actual_bytes").equals(expected.path("actual_bytes")) && current.path("sha256").equals(expected.path("sha256")),409,"INPUT_HASH_MISMATCH");
        }
        var actualMetadata=actual.metadata();var bounds=actualMetadata.getBounds();require(bounds!=null && "EPSG:4326".equals(actualMetadata.getBoundsCrs()),400,"INVALID_GRID");
        RsImage image=new RsImage();image.setOwnerId(job.path("owner_id").asText());image.setVisibility("PRIVATE");
        image.setImageCode("INGEST_"+job.path("id").asText().replace("-",""));image.setImageName(job.path("image_name").asText());
        image.setSensorType("Optical");image.setSatelliteName("Sentinel-2");image.setAcquisitionTime(OffsetDateTime.parse(metadata.path("admission").path("manifest").path("captureTime").asText()));
        image.setResolutionMeter(actualMetadata.getResolutionMeter());image.setBandCount(actualMetadata.getBandCount());image.setProjection(actualMetadata.getCrs());
        image.setWidth(actualMetadata.getWidth());image.setHeight(actualMetadata.getHeight());image.setFileFormat("GeoTIFF");
        image.setFileSize(actual.tiff().path("actual_bytes").asLong());image.setContentType("image/tiff");image.setMetadataJson(metadata.toString());
        image.setMinioBucket(actual.tiff().path("bucket").asText());image.setObjectKey(actual.tiff().path("server_key").asText());
        image.setStatus("READY");image.setThumbnailStatus("SKIPPED");image.setDescription("SAFE区域标准化；地图发布独立处理");
        BigDecimal left=bounds.getLeft(),bottom=bounds.getBottom(),right=bounds.getRight(),top=bounds.getTop();
        image.setFootprintWkt("POLYGON(("+left+" "+bottom+","+right+" "+bottom+","+right+" "+top+","+left+" "+top+","+left+" "+bottom+"))");
        image.setCenterLon(left.add(right).divide(BigDecimal.valueOf(2)));image.setCenterLat(bottom.add(top).divide(BigDecimal.valueOf(2)));
        require(images.insert(image)==1 && image.getId()!=null,409,"STATE_CONFLICT");
        require(db.update("UPDATE ingest_job SET status='SUCCEEDED',image_id=?,last_error=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=? AND status='VALIDATING' AND current_attempt_id=? AND generation=?",image.getId(),IngestionStore.id(job),IngestionStore.id(attempt),attempt.path("generation").asInt())==1,409,"ATTEMPT_STALE");
        UUID publication=UUID.randomUUID();String layer="asset_"+image.getId();
        require(db.update("INSERT INTO ingest_publication(id,owner_id,job_id,image_id,status,workspace,store_name,layer_name,style_version) VALUES (?,?,?,?,'PENDING','ingest_private',?,?,'s2-rgb-v1')",publication,job.path("owner_id").asText(),IngestionStore.id(job),image.getId(),layer+"_store",layer)==1,409,"STATE_CONFLICT");
        jobs.intent("PUBLISH",publication,1);
        for(ObjectNode object:List.of(actual.tiff(),actual.manifest()))require(db.update("UPDATE ingest_object SET state='AVAILABLE',retention='PERMANENT_ASSET',reserved_bytes=actual_bytes,delete_after=NULL WHERE id=? AND job_id=? AND state='RESERVED'",IngestionStore.id(object),IngestionStore.id(job))==1,409,"ATTEMPT_STALE");
        require(db.update("UPDATE ingest_attempt SET status='SUCCEEDED' WHERE id=? AND job_id=? AND generation=? AND status='RESULT_REPORTED'",uuid(job.path("candidate_artifacts"),"standardizeAttemptId"),IngestionStore.id(job),job.path("generation").asInt())==1,409,"ATTEMPT_STALE");
        return db.object().put("accepted",true).put("receiptId",attempt.path("id").asText()).put("targetStatus","SUCCEEDED")
                .put("imageId",image.getId()).put("publicationId",publication.toString());
    }
}
