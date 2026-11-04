package com.remotesensing.platform.ingestion.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IngestionAssetReadServiceTest {
    private final IngestionStore db=mock(IngestionStore.class);
    private final IngestionJobService jobs=mock(IngestionJobService.class);
    private final IngestionStorage storage=mock(IngestionStorage.class);
    private final CurrentUserContext users=mock(CurrentUserContext.class);
    private final IngestionAssetReadService reader=new IngestionAssetReadService(db,jobs,storage,users);
    private final ObjectNode job=IngestionJson.JSON.createObjectNode(),object=IngestionJson.JSON.createObjectNode();
    private final RsImage image=new RsImage();
    @BeforeEach void setup() {
        String jobId=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString();
        image.setId(7L);image.setOwnerId("owner");image.setStatus("READY");image.setMinioBucket("private-ingestion");
        image.setObjectKey("ingestion/artifacts/"+jobId+"/"+attempt+"/reflectance.tif");
        job.put("id",jobId).putObject("candidate_artifacts").put("standardizeAttemptId",attempt);
        object.put("id",UUID.randomUUID().toString()).put("job_id",jobId).put("attempt_id",attempt).put("owner_id","owner")
                .put("role","TIFF").put("state","AVAILABLE").put("retention","PERMANENT_ASSET").put("bucket",image.getMinioBucket()).put("server_key",image.getObjectKey());
        when(users.getAuthenticatedUserId()).thenReturn("owner");
        when(db.one(anyString(),any(Object[].class))).thenAnswer(i -> ((String)i.getArgument(0)).contains("FROM ingest_job")?job:object);
    }
    @Test void ownerGetsTheExactRegisteredPermanentTiff() {
        var signed=new FilePresignedUrlVO(image.getObjectKey(),"http://storage/authorized",1800);
        when(storage.presignedTiff(object)).thenReturn(signed);
        assertThat(reader.downloadUrl(image)).isSameAs(signed);
        verify(jobs).assetUsable(job);verify(storage).presignedTiff(object);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void otherOwnerCannotSignEvenIfImageWasExposedByAnotherReader() {
        when(users.getAuthenticatedUserId()).thenReturn("other");
        assertThatThrownBy(() -> reader.downloadUrl(image)).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.status).isEqualTo(404));
        verifyNoInteractions(storage);verify(db,never()).one(anyString(),any(Object[].class));
    }
    @Test void anonymousCannotSign() {
        when(users.getAuthenticatedUserId()).thenThrow(new com.remotesensing.platform.exception.BusinessException(401,"unauthorized"));
        assertThatThrownBy(() -> reader.downloadUrl(image)).isInstanceOf(com.remotesensing.platform.exception.BusinessException.class);
        verifyNoInteractions(storage);
    }
    @ParameterizedTest
    @ValueSource(strings={"RAW","PREVIEW","UNREGISTERED","BUCKET","ATTEMPT"})
    void unrelatedOrUnregisteredObjectsCannotBeSigned(String change) {
        if(change.equals("UNREGISTERED"))when(db.one(contains("FROM ingest_job"),any(Object[].class))).thenReturn(null);
        else if(change.equals("BUCKET"))object.put("bucket","different-bucket");
        else if(change.equals("ATTEMPT"))object.put("attempt_id",UUID.randomUUID().toString());
        else object.put("server_key",change.equals("RAW")?"ingestion/raw/product/raw/product.zip":"ingestion/preview/product/attempt/preview.png");
        assertThatThrownBy(() -> reader.downloadUrl(image)).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.status).isEqualTo(409));
        verifyNoInteractions(storage);
    }
}
