package com.remotesensing.platform.ingestion.publishing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.*;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.*;
import com.remotesensing.platform.ingestion.workflow.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.JSON;
import static org.mockito.Mockito.*;

class IngestionPublicationExecutorTest {
    private void failure(String reason,boolean uncertain){
        var db=mock(IngestionStore.class);when(db.object()).thenAnswer(i->JSON.createObjectNode());
        var works=mock(IngestionPublicationWorkService.class);var geo=mock(IngestionGeoServerClient.class);var copies=mock(IngestionMapCopyStorage.class);var slots=mock(ComputeSlots.class);
        UUID id=UUID.randomUUID();when(geo.configured()).thenReturn(true);
        when(works.claim(eq(id),any(),anyString())).thenReturn(JSON.createObjectNode().put("action","EXECUTE").put("attemptId",UUID.randomUUID().toString()).put("slotFence",4).put("hardDeadline",Instant.now().plusSeconds(100).toString()));
        var pub=JSON.createObjectNode().put("image_id",1);var image=JSON.createObjectNode();
        var asset=new IngestionLayerService.Asset(pub,JSON.createObjectNode(),image,JSON.createObjectNode());when(works.guard(eq(id),any(),anyString(),eq(false))).thenReturn(asset);
        doThrow(new IngestionProblem(503,reason)).when(geo).publish(any(),any(),any());
        new IngestionPublicationExecutor(db,works,geo,copies,slots).execute(id);
        verify(works).fail(eq(id),any(),anyString(),eq(reason));verify(works,never()).complete(any(),any(),anyString(),anyString(),anyString());
        if(uncertain){verify(works,never()).stopped(any(),any(),anyString());verify(slots).recovery(eq("COMPUTE"),eq("WORK:"+id),anyString(),eq(4L));}
        else verify(works).stopped(eq(id),any(),anyString());
    }
    @Test void knownSourceOrPngFailureCannotCompletePublication(){failure("PUBLISH_FAILED",false);}
    @Test void unknownUpstreamWriteRetainsRecoveryPermit(){failure("PUBLISH_UNCERTAIN",true);}
    @ParameterizedTest
    @ValueSource(strings={"unknown","receipt","rollback"})
    void completionOutcomeControlsFailureAndSlotRelease(String outcome){
        var db=mock(IngestionStore.class);when(db.object()).thenAnswer(i->JSON.createObjectNode());
        var works=mock(IngestionPublicationWorkService.class);var geo=mock(IngestionGeoServerClient.class);var copies=mock(IngestionMapCopyStorage.class);var slots=mock(ComputeSlots.class);
        UUID id=UUID.randomUUID();when(geo.configured()).thenReturn(true);
        when(works.claim(eq(id),any(),anyString())).thenReturn(JSON.createObjectNode().put("action","EXECUTE").put("attemptId",UUID.randomUUID().toString()).put("slotFence",5).put("hardDeadline",Instant.now().plusSeconds(100).toString()));
        var image=JSON.createObjectNode();var manifest=image.putObject("metadata_json").putObject("admission").putObject("manifest").put("width",2).put("height",2).put("crs","EPSG:32650");
        manifest.putArray("transform").add(10).add(0).add(390000).add(0).add(-10).add(3200000);
        var asset=new IngestionLayerService.Asset(JSON.createObjectNode(),JSON.createObjectNode(),image,JSON.createObjectNode());
        when(works.guard(eq(id),any(),anyString(),eq(false))).thenReturn(asset);when(geo.map(any(),any(),any())).thenReturn(new byte[]{1,2,3});
        RuntimeException failure="rollback".equals(outcome)
                ? new IngestionPublicationWorkService.CompletionRolledBack(new IngestionProblem(409,"ASSET_CHANGED"))
                : new IllegalStateException("unknown transaction outcome");
        doThrow(failure).when(works).complete(eq(id),any(),anyString(),anyString(),anyString());
        if("receipt".equals(outcome))when(works.completed(eq(id),any(),anyString(),anyString())).thenReturn(true);
        new IngestionPublicationExecutor(db,works,geo,copies,slots).execute(id);
        if("rollback".equals(outcome))verify(works).fail(eq(id),any(),anyString(),eq("ASSET_CHANGED"));
        else verify(works,never()).fail(any(),any(),anyString(),anyString());
        if("unknown".equals(outcome)){
            verify(works,never()).stopped(any(),any(),anyString());
            verify(slots).recovery(eq("COMPUTE"),eq("WORK:"+id),anyString(),eq(5L));
        }else{
            verify(works).stopped(eq(id),any(),anyString());
            verifyNoInteractions(slots);
        }
    }
}
