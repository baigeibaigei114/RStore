package com.remotesensing.platform.ingestion.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import org.junit.jupiter.api.Test;

class IngestionAssetRegistrationTest {
    @Test void changedCandidateAfterIoCannotInsertAnAsset() {
        var images=mock(RsImageMapper.class);var db=mock(IngestionStore.class);
        var service=new IngestionAssetRegistration(db,images,mock(IngestionJobService.class));
        ObjectNode job=IngestionJson.JSON.createObjectNode().put("status","VALIDATING").putNull("image_id");job.putObject("candidate_artifacts").put("generation",2);
        var old=new IngestionValidationService.Validated(new GeoTiffMetadataVO(),IngestionJson.JSON.createObjectNode(),IngestionJson.JSON.createObjectNode(),"older-candidate-hash");
        assertThatThrownBy(() -> service.register(job,IngestionJson.JSON.createObjectNode(),old)).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.reason).isEqualTo("ATTEMPT_STALE"));
        verifyNoInteractions(images,db);
    }
    @Test void workerMetadataWithoutJavaAdmissionCannotInsertAnAsset() {
        var images=mock(RsImageMapper.class);var db=mock(IngestionStore.class);
        var service=new IngestionAssetRegistration(db,images,mock(IngestionJobService.class));
        ObjectNode job=IngestionJson.JSON.createObjectNode().put("status","VALIDATING").putNull("image_id");job.putObject("candidate_artifacts");
        var actual=new IngestionValidationService.Validated(new GeoTiffMetadataVO(),IngestionJson.JSON.createObjectNode(),IngestionJson.JSON.createObjectNode(),IngestionJson.sha(job.path("candidate_artifacts")));
        assertThatThrownBy(() -> service.register(job,IngestionJson.JSON.createObjectNode(),actual)).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.reason).isEqualTo("ADMISSION_REJECTED"));
        verifyNoInteractions(images,db);
    }
}
