package com.remotesensing.platform.ingestion.publishing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.workflow.*;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static com.remotesensing.platform.ingestion.workflow.IngestionJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionPublicationServiceTest {
    @Test void pendingStateRejectsStaleDifferentKeyAndAcceptsCurrentVersionWithoutWrites(){
        var db=mock(IngestionStore.class);var products=mock(IngestionService.class);var jobs=mock(IngestionJobService.class);var layers=mock(IngestionLayerService.class);
        ObjectNode p=JSON.createObjectNode().put("status","PENDING").put("version",8);
        p.putObject("request_receipts");
        when(db.transaction(any())).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());
        when(db.one(anyString(),any(Object[].class))).thenReturn(p);
        var service=new IngestionPublicationService(db,products,jobs,layers);
        UUID job=UUID.randomUUID();
        var body=JSON.createObjectNode().put("clientRequestId",UUID.randomUUID().toString()).put("expectedVersion",7);
        var error=assertThrows(IngestionProblem.class,()->service.retry("owner",job,body));
        assertEquals("STATE_CONFLICT",error.reason);
        assertEquals(8L,error.currentVersion);
        body.put("expectedVersion",8);
        assertEquals("PENDING",service.retry("owner",job,body).path("status").asText());
        verify(db,never()).update(anyString(),any(Object[].class));
        verify(jobs,never()).intent(anyString(),any(),anyInt());
    }
    @Test void lostRetryResponseReplaysBeforeOldVersionAndDoesNotReadRaw(){
        var db=mock(IngestionStore.class);var products=mock(IngestionService.class);var jobs=mock(IngestionJobService.class);var layers=mock(IngestionLayerService.class);
        var body=JSON.createObjectNode().put("clientRequestId",UUID.randomUUID().toString()).put("expectedVersion",2);
        var response=JSON.createObjectNode().put("status","PENDING").put("imageId",7);
        ObjectNode p=JSON.createObjectNode().put("status","FAILED").put("version",99);
        p.putObject("request_receipts").putObject(body.path("clientRequestId").asText()).put("hash",sha(body)).set("response",response);
        when(db.transaction(any())).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());when(db.one(anyString(),any(Object[].class))).thenReturn(p);
        var service=new IngestionPublicationService(db,products,jobs,layers);assertEquals(response,service.retry("owner",UUID.randomUUID(),body));
        verify(layers,never()).asset(any(),anyBoolean());verify(products,never()).product(anyString(),any(),anyBoolean());verify(jobs,never()).intent(anyString(),any(),anyInt());
    }
    @Test void exhaustedGenerationCannotCreateFourthAttempt(){
        var db=mock(IngestionStore.class);var products=mock(IngestionService.class);var jobs=mock(IngestionJobService.class);var layers=mock(IngestionLayerService.class);
        // Use an ordinary receipt object; no retry key has been consumed.
        ObjectNode p=JSON.createObjectNode().put("status","FAILED").put("generation",3).put("version",2);p.putObject("request_receipts");
        when(db.transaction(any())).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());when(db.one(anyString(),any(Object[].class))).thenReturn(p);
        var service=new IngestionPublicationService(db,products,jobs,layers);
        var body=JSON.createObjectNode().put("clientRequestId",UUID.randomUUID().toString()).put("expectedVersion",2);
        assertThrows(IngestionProblem.class,()->service.retry("owner",UUID.randomUUID(),body));verify(jobs,never()).intent(anyString(),any(),anyInt());
    }
}
