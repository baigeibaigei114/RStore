package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.common.InputAdmission;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.IngestionProcessRunner;
import com.remotesensing.platform.service.ImageBandCapabilityService;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** Reads actual bound files outside locks. Its result is passed only to an atomic registration closure. */
@Service
public class IngestionValidationService {
    public record Validated(GeoTiffMetadataVO metadata,ObjectNode tiff,ObjectNode manifest,String candidateHash) {}
    private final IngestionStore db;
    private final IngestionStorage storage;
    private final IngestionProcessRunner runner;
    private final ImageBandCapabilityService bands;
    public IngestionValidationService(IngestionStore db,IngestionStorage storage,IngestionProcessRunner runner,ImageBandCapabilityService bands) {
        this.db=db;this.storage=storage;this.runner=runner;this.bands=bands;
    }
    public Validated validate(ObjectNode job,Path directory,Instant deadline,BooleanSupplier lease,Consumer<String> stopped) {
        storage.ensurePrivate();
        JsonNode candidate=job.path("candidate_artifacts");require(candidate.isObject() && candidate.path("outputs").isArray() && candidate.path("outputs").size()==2,400,"ADMISSION_REJECTED");
        ObjectNode tiff=null,manifest=null;
        for(JsonNode out:candidate.path("outputs")) {
            String role=out.path("role").asText();require(List.of("TIFF","MANIFEST").contains(role),400,"ADMISSION_REJECTED");
            ObjectNode object=db.one("SELECT * FROM ingest_object WHERE id=? AND job_id=? AND owner_id=? AND role=? AND attempt_id=?",uuid(out,"objectId"),IngestionStore.id(job),job.path("owner_id").asText(),role,uuid(candidate,"standardizeAttemptId"));
            require(object!=null && "RESERVED".equals(object.path("state").asText()) && object.path("actual_bytes").asLong()==out.path("bytes").asLong()
                    && object.path("sha256").asText().equals(out.path("sha256").asText()),409,"INPUT_HASH_MISMATCH");
            if(role.equals("TIFF")) {require(tiff==null,400,"ADMISSION_REJECTED");tiff=object;}
            else {require(manifest==null,400,"ADMISSION_REJECTED");manifest=object;}
        }
        require(tiff!=null && manifest!=null,400,"ADMISSION_REJECTED");
        require(tiff.path("actual_bytes").asLong()<=256L*1024*1024 && manifest.path("actual_bytes").asLong()<=65536,400,"RESOURCE_LIMIT");
        try {
            Path raster=directory.resolve("reflectance.tif"),json=directory.resolve("reflectance.json"),region=directory.resolve("region.json"),result=directory.resolve("validated.json"),ready=directory.resolve("native.ready");
            storage.download(tiff,raster,remaining(deadline),256L<<20);storage.download(manifest,json,remaining(deadline),65536);
            require(lease.getAsBoolean(),409,"LEASE_LOST");
            byte[] bytes=Files.readAllBytes(json);JsonNode document=JSON.readTree(bytes);
            verifyManifest(job,document,tiff);
            ObjectNode source=db.one("SELECT descriptor FROM ingest_product WHERE id=? AND owner_id=?",uuid(job,"active_product_id"),job.path("owner_id").asText());
            require(source!=null,409,"INPUT_HASH_MISMATCH");JsonNode descriptor=source.path("descriptor");
            require(document.path("sourceId").asText().equals(descriptor.path("sourceId").asText())
                    && document.path("captureTime").asText().equals(descriptor.path("captureTime").asText())
                    && document.path("safe").path("productMetadataSha256").asText().equals(descriptor.path("productMetadataSha256").asText())
                    && document.path("safe").path("processingBaseline").asText().equals(descriptor.path("processingBaseline").asText())
                    && document.path("safe").path("granule").asText().equals(descriptor.path("granule").asText()),409,"INPUT_HASH_MISMATCH");
            Files.writeString(region,canonical(job.path("region_snapshot")),StandardOpenOption.CREATE_NEW);
            runner.run(raster,region,result,ready,deadline,lease,stopped);
            require(Files.size(result)<=1024*1024,400,"ADMISSION_REJECTED");
            JsonNode actual=JSON.readTree(Files.readAllBytes(result));
            require(actual.path("rectangleValidReflectanceCount").asLong()>0,400,"NO_VALID_PIXELS");
            long pixels=(long)job.path("window").get(2).asInt()*job.path("window").get(3).asInt();
            long count=integer(actual,"regionPixelCount",0,pixels),valid=integer(actual,"regionValidReflectanceCount",0,count);
            require(candidate.path("regionPixelCount").asLong()==count && candidate.path("regionValidReflectanceCount").asLong()==valid,409,"INPUT_HASH_MISMATCH");
            GeoTiffMetadataVO metadata=JSON.treeToValue(actual.path("metadata"),GeoTiffMetadataVO.class);
            metadata.setAdmission(InputAdmission.validate(bytes,raster,metadata));
            bands.enrichMetadata(metadata);
            require(metadata.getBandMapping()!=null && Integer.valueOf(3).equals(metadata.getBandMapping().get("red")) && Integer.valueOf(4).equals(metadata.getBandMapping().get("nir")),400,"ADMISSION_REJECTED");
            require(Instant.now().isBefore(deadline) && lease.getAsBoolean(),409,"LEASE_LOST");
            return new Validated(metadata,tiff,manifest,sha(candidate));
        } catch(IngestionProblem error) {throw error;}
        catch(BusinessException | java.io.IOException error) {throw new IngestionProblem(400,"ADMISSION_REJECTED");}
    }
    public static void verifyManifest(ObjectNode job,JsonNode manifest,ObjectNode tiff) {
        JsonNode doc;
        try {doc=JSON.readTree(job.path("intent_document").asText());}
        catch(java.io.IOException error) {throw new IngestionProblem(400,"ADMISSION_REJECTED");}
        require(manifest!=null && manifest.isObject(),400,"ADMISSION_REJECTED");
        JsonNode safe=manifest.path("safe");
        require(job.path("archive_sha256").asText().equals(safe.path("archiveSha256").asText()) && doc.path("parserVersion").asText().equals(safe.path("parserVersion").asText())
                && canonical(job.path("window")).equals(canonical(manifest.path("window")))
                && canonical(doc.path("calibration")).equals(canonical(manifest.path("calibration")))
                && canonical(doc.path("rawCalibration").path("boaAddOffsets")).equals(canonical(safe.path("boaAddOffsets")))
                && canonical(doc.path("rawCalibration").path("quantificationValue")).equals(canonical(safe.path("quantificationValue")))
                && tiff.path("sha256").asText().equals(manifest.path("sha256").asText()),409,"INPUT_HASH_MISMATCH");
        JsonNode grid=doc.path("sourceGrid"),window=job.path("window"),tr=grid.path("transform"),out=manifest.path("transform");
        require(manifest.path("crs").asText().equals(grid.path("crs").asText()) && out.isArray() && out.size()==6 && tr.isArray() && tr.size()==6,400,"INVALID_GRID");
        double col=window.get(0).asDouble(),row=window.get(1).asDouble();
        double[] expected={tr.get(0).asDouble(),tr.get(1).asDouble(),tr.get(2).asDouble()+col*tr.get(0).asDouble()+row*tr.get(1).asDouble(),
            tr.get(3).asDouble(),tr.get(4).asDouble(),tr.get(5).asDouble()+col*tr.get(3).asDouble()+row*tr.get(4).asDouble()};
        for(int i=0;i<6;i++)require(out.get(i).isNumber() && Double.isFinite(out.get(i).asDouble()) && Math.abs(out.get(i).asDouble()-expected[i])<=1e-9,400,"INVALID_GRID");
    }
    private long remaining(Instant deadline) {long value=Duration.between(Instant.now(),deadline).getSeconds();require(value>0,503,"HARD_TIMEOUT");return value;}
}
