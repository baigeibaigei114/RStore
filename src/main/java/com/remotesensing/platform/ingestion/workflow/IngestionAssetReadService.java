package com.remotesensing.platform.ingestion.workflow;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import org.springframework.stereotype.Service;
import static com.remotesensing.platform.ingestion.api.IngestionProblem.require;

/** Image-authorized access to the registered permanent TIFF; never a general key signing API. */
@Service
public class IngestionAssetReadService {
    private final IngestionStore db;
    private final IngestionJobService jobs;
    private final IngestionStorage storage;
    private final CurrentUserContext users;

    public IngestionAssetReadService(IngestionStore db,IngestionJobService jobs,IngestionStorage storage,CurrentUserContext users) {
        this.db=db;this.jobs=jobs;this.storage=storage;this.users=users;
    }

    public FilePresignedUrlVO downloadUrl(RsImage image) {
        String owner=users.getAuthenticatedUserId();
        require(owner!=null && owner.equals(image.getOwnerId()),404,"NOT_FOUND");
        require(image.getDeletedAt()==null && !"DELETED".equals(image.getStatus()) && !"DELETE_LOCKED".equals(image.getStatus()),410,"ASSET_UNAVAILABLE");
        require("READY".equals(image.getStatus()),409,"ASSET_CHANGED");
        var job=db.one("SELECT * FROM ingest_job WHERE image_id=? AND owner_id=? AND status='SUCCEEDED'",image.getId(),owner);
        require(job!=null,409,"ASSET_CHANGED");
        jobs.assetUsable(job);
        var tiff=db.one("SELECT * FROM ingest_object WHERE job_id=? AND owner_id=? AND role='TIFF' AND retention='PERMANENT_ASSET' AND state='AVAILABLE'",IngestionStore.id(job),owner);
        require(tiff!=null && owner.equals(tiff.path("owner_id").asText())
                && job.path("id").equals(tiff.path("job_id"))
                && job.path("candidate_artifacts").path("standardizeAttemptId").equals(tiff.path("attempt_id"))
                && tiff.path("bucket").asText().equals(image.getMinioBucket())
                && tiff.path("server_key").asText().equals(image.getObjectKey()),409,"ASSET_CHANGED");
        return storage.presignedTiff(tiff);
    }
}
