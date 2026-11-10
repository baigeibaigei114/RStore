package com.remotesensing.platform.ingestion.publishing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.ComputeSlots;
import com.remotesensing.platform.ingestion.workflow.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionPublicationWorkServiceTest {
    @Test void missingLocalWorkNeverReservesCompute(){
        var db=mock(IngestionStore.class);var slots=mock(ComputeSlots.class);
        var works=new IngestionPublicationWorkService(db,new IngestionProperties(),mock(IngestionService.class),mock(IngestionJobService.class),mock(IngestionLayerService.class),slots);
        assertEquals(404,assertThrows(IngestionProblem.class,()->works.claim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID().toString())).status);verifyNoInteractions(slots);
    }
    @Test void staleFenceCannotAuthorizePrePublicationValidation(){
        var db=mock(IngestionStore.class);var slots=mock(ComputeSlots.class);var layers=mock(IngestionLayerService.class);
        UUID work=UUID.randomUUID(),pub=UUID.randomUUID(),attempt=UUID.randomUUID();String executor=UUID.randomUUID().toString();
        ObjectNode w=JSON.createObjectNode().put("kind","PUBLISH").put("dispatch_mode","LOCAL").put("target_id",pub.toString()).put("generation",2);
        ObjectNode p=JSON.createObjectNode().put("id",pub.toString()).put("status","PUBLISHING").put("generation",2).put("current_attempt_id",attempt.toString()).put("owner_id","owner");
        ObjectNode a=JSON.createObjectNode().put("id",attempt.toString()).put("kind","PUBLISH").put("status","RUNNING").put("executor_id",executor).put("slot_fence",8).put("publication_id",pub.toString()).put("owner_id","owner");
        when(db.one(anyString(),any(Object[].class))).thenAnswer(i->{String sql=i.getArgument(0);return sql.contains("ingest_outbox")?w:sql.contains("ingest_publication")?p:a;});
        var works=new IngestionPublicationWorkService(db,new IngestionProperties(),mock(IngestionService.class),mock(IngestionJobService.class),layers,slots);
        assertThrows(IngestionProblem.class,()->works.guard(work,JSON.createObjectNode().put("attemptId",attempt.toString()).put("slotFence",7),executor,false));verifyNoInteractions(layers);
    }
}
