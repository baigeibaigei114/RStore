package com.remotesensing.platform.ingestion.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.publishing.IngestionGeoServerClient;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;

/** UUID publication namespace, independent of legacy numeric task layers. */
@Service
public class IngestionLayerService {
    public record Asset(ObjectNode publication,ObjectNode job,ObjectNode image,ObjectNode tiff) {
        public JsonNode manifest() {return image.path("metadata_json").path("admission").path("manifest");}
        public String fingerprint() {
            ObjectNode identity=JSON.createObjectNode();
            for(String key:List.of("id","owner_id","job_id","image_id","source_kind","workspace","store_name","layer_name","style_version"))identity.set(key,publication.path(key));
            identity.set("admission",image.path("metadata_json").path("admission"));identity.set("tiff",tiff);
            return sha(identity);
        }
    }
    public record MapRequest(String bbox,String srs,int width,int height,boolean transparent) {}
    private final IngestionStore db;
    private final IngestionJobService jobs;
    private final IngestionProperties config;
    private final IngestionGeoServerClient upstream;
    public IngestionLayerService(IngestionStore db,IngestionJobService jobs,IngestionProperties config,IngestionGeoServerClient upstream) {
        this.db=db;this.jobs=jobs;this.config=config;this.upstream=upstream;
    }
    public void enabled() {require(config.isEnabled() && config.isPublicationEnabled(),503,"SERVICE_UNAVAILABLE");}
    public ObjectNode publication(String owner,UUID id,boolean lock) {
        ObjectNode p=db.one("SELECT * FROM ingest_publication WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),id,owner);
        require(p!=null,404,"NOT_FOUND");return p;
    }
    public Asset asset(ObjectNode p,boolean lock) {
        long imageId=p.path("image_id").asLong();String name="asset_"+imageId,owner=p.path("owner_id").asText();
        require(imageId>0 && "STANDARDIZED_IMAGE".equals(p.path("source_kind").asText()) && "ingest_private".equals(p.path("workspace").asText())
                && name.equals(p.path("layer_name").asText()) && (name+"_store").equals(p.path("store_name").asText())
                && "s2-rgb-v1".equals(p.path("style_version").asText()),409,"ASSET_CHANGED");
        ObjectNode j=jobs.job(owner,uuid(p,"job_id"),false);
        require("SUCCEEDED".equals(j.path("status").asText()) && j.path("image_id").asLong()==imageId,409,"ASSET_CHANGED");
        jobs.assetUsable(j);
        ObjectNode image=db.one("SELECT * FROM rs_image WHERE id=? AND owner_id=?"+(lock?" FOR SHARE":""),imageId,owner);
        require(image!=null && image.path("deleted_at").isNull() && "READY".equals(image.path("status").asText()),410,"ASSET_UNAVAILABLE");
        ObjectNode tiff=db.one("SELECT * FROM ingest_object WHERE job_id=? AND owner_id=? AND role='TIFF' AND retention='PERMANENT_ASSET' AND state='AVAILABLE'"+(lock?" FOR SHARE":""),IngestionStore.id(j),owner);
        require(tiff!=null && "MINIO".equals(tiff.path("storage").asText()) && j.path("candidate_artifacts").path("standardizeAttemptId").equals(tiff.path("attempt_id"))
                && image.path("object_key").equals(tiff.path("server_key")) && image.path("minio_bucket").equals(tiff.path("bucket"))
                && "PASSED".equals(image.path("metadata_json").path("admission").path("status").asText())
                && "input-v1".equals(image.path("metadata_json").path("admission").path("validatorVersion").asText())
                && tiff.path("sha256").equals(image.path("metadata_json").path("admission").path("manifest").path("sha256")),409,"ASSET_CHANGED");
        return new Asset(p,j,image,tiff);
    }
    public Asset readable(String owner,UUID id) {
        enabled();ObjectNode p=publication(owner,id,false);
        require("AVAILABLE".equals(p.path("status").asText()) && !p.path("verified_at").isNull(),409,"STATE_CONFLICT");
        return asset(p,false);
    }
    public ObjectNode list(String owner,int page,int pageSize) {
        enabled();require(page>=1 && page<=100000 && pageSize>=1 && pageSize<=50,400,"INVALID_REQUEST");
        // Filter unusable assets in SQL so a deleted item cannot poison the whole list or its count.
        String from=" FROM ingest_publication p JOIN rs_image i ON i.id=p.image_id AND i.owner_id=p.owner_id JOIN ingest_job j ON j.id=p.job_id AND j.owner_id=p.owner_id"
                +" JOIN ingest_object o ON o.job_id=j.id AND o.owner_id=p.owner_id AND o.role='TIFF' AND o.state='AVAILABLE' AND o.retention='PERMANENT_ASSET'"
                +" WHERE p.owner_id=? AND p.status='AVAILABLE' AND p.verified_at IS NOT NULL AND j.status='SUCCEEDED' AND j.image_id=i.id"
                +" AND i.deleted_at IS NULL AND i.status='READY' AND i.metadata_json->'admission'->>'status'='PASSED'"
                +" AND i.metadata_json->'admission'->>'validatorVersion'='input-v1' AND i.metadata_json->'admission'->'manifest'->>'sha256'=o.sha256"
                +" AND o.storage='MINIO' AND i.object_key=o.server_key AND i.minio_bucket=o.bucket AND j.candidate_artifacts->>'standardizeAttemptId'=o.attempt_id::text"
                +" AND p.source_kind='STANDARDIZED_IMAGE' AND p.workspace='ingest_private' AND p.layer_name='asset_'||i.id::text"
                +" AND p.store_name=p.layer_name||'_store' AND p.style_version='s2-rgb-v1'";
        ObjectNode result=db.object().put("page",page).put("pageSize",pageSize).put("total",db.count("SELECT count(*)"+from,owner));
        var items=result.putArray("items");
        for(ObjectNode p:db.rows("SELECT p.id,p.image_id,i.image_name,i.metadata_json->'bounds' AS bounds"+from+" ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?",owner,pageSize,(page-1)*pageSize)) {
            ObjectNode item=paths(p).put("sourceKind","STANDARDIZED_IMAGE").put("name",p.path("image_name").asText());
            item.set("bounds",p.path("bounds"));items.add(item);
        }
        return result;
    }
    public static ObjectNode paths(ObjectNode p) {
        String id=p.path("id").asText();return JSON.createObjectNode().put("layerId",id).put("imageId",p.path("image_id").asLong())
                .put("wmsPath","/api/ingestion/layers/"+id+"/wms").put("wcsPath","/api/ingestion/layers/"+id+"/wcs");
    }
    public static MapRequest parameters(MultiValueMap<String,String> input,String nativeCrs) {
        Map<String,String> p=new HashMap<>();Set<String> allowed=Set.of("bbox","srs","width","height","transparent");
        input.forEach((key,values) -> {
            String k=key.toLowerCase(Locale.ROOT);require(allowed.contains(k) && values.size()==1 && values.get(0)!=null && values.get(0).length()<=256 && p.putIfAbsent(k,values.get(0))==null,400,"INVALID_REQUEST");
        });
        require(p.keySet().containsAll(Set.of("bbox","srs","width","height")),400,"INVALID_REQUEST");
        String[] coords=p.get("bbox").split(",",-1);require(coords.length==4,400,"INVALID_REQUEST");double[] b=new double[4];
        try {for(int i=0;i<4;i++){b[i]=Double.parseDouble(coords[i]);require(Double.isFinite(b[i]),400,"INVALID_REQUEST");}}
        catch(NumberFormatException error){throw new com.remotesensing.platform.ingestion.api.IngestionProblem(400,"INVALID_REQUEST");}
        require(b[0]<b[2] && b[1]<b[3],400,"INVALID_REQUEST");
        String srs=p.get("srs").toUpperCase(Locale.ROOT);require(srs.equals("EPSG:3857") || srs.equals("EPSG:4326") || srs.equals(nativeCrs),400,"INVALID_REQUEST");
        require(p.get("width").matches("[1-9][0-9]{0,3}") && p.get("height").matches("[1-9][0-9]{0,3}"),400,"INVALID_REQUEST");
        int width=Integer.parseInt(p.get("width")),height=Integer.parseInt(p.get("height"));require(width<=2048 && height<=2048,400,"INVALID_REQUEST");
        String transparent=p.getOrDefault("transparent","true");require(Set.of("true","false").contains(transparent),400,"INVALID_REQUEST");
        return new MapRequest(p.get("bbox"),srs,width,height,Boolean.parseBoolean(transparent));
    }
    public byte[] wms(String owner,UUID id,MultiValueMap<String,String> input) {
        Asset a=readable(owner,id);return upstream.map(a,parameters(input,a.manifest().path("crs").asText()),java.time.Instant.now().plusSeconds(30));
    }
    public IngestionGeoServerClient.Coverage wcs(String owner,UUID id,MultiValueMap<String,String> input) {
        require(input.isEmpty(),400,"INVALID_REQUEST");return upstream.coverage(readable(owner,id),java.time.Instant.now().plusSeconds(30));
    }
}
