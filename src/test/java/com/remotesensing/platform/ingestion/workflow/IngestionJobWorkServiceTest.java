package com.remotesensing.platform.ingestion.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import com.remotesensing.platform.ingestion.runtime.ComputeSlots;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IngestionJobWorkServiceTest {
    private final IngestionStore db=mock(IngestionStore.class);
    private final ComputeSlots slots=mock(ComputeSlots.class);
    private final IngestionStorage storage=mock(IngestionStorage.class);
    private final UUID work=UUID.randomUUID(),attempt=UUID.randomUUID();
    private final ObjectNode row=IngestionJson.JSON.createObjectNode(),a=IngestionJson.JSON.createObjectNode();
    private IngestionJobWorkService service;
    @BeforeEach void setUp() {
        row.put("id",work.toString()).put("kind","STANDARDIZE").put("dispatch_mode","MQ");
        a.put("id",attempt.toString()).put("status","RUNNING").put("slot_fence",2).putNull("result_receipt");
        when(db.one(anyString(),any(Object[].class))).thenAnswer(i -> ((String)i.getArgument(0)).contains("ingest_outbox")?row:a);
        when(slots.requestExecutor()).thenReturn("11111111-1111-4111-8111-111111111111");
        service=new IngestionJobWorkService(db,new IngestionProperties(),mock(IngestionService.class),mock(IngestionJobService.class),storage,slots);
    }
    private ObjectNode body() {
        var result=IngestionJson.JSON.createObjectNode().put("regionPixelCount",1);
        return IngestionJson.JSON.createObjectNode().put("attemptId",attempt.toString()).put("slotFence",2).put("resultDigest",IngestionJson.sha(result)).set("result",result);
    }
    @Test void staleCompletionCannotReadCandidateObjects() {
        assertThatThrownBy(() -> service.complete(work,body())).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.reason).isEqualTo("ATTEMPT_STALE"));
        verifyNoInteractions(storage);verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void duplicateCompletionReturnsReceiptBeforeLeaseOrObjectIo() {
        ObjectNode body=body(),receipt=IngestionJson.JSON.createObjectNode().put("accepted",true).put("targetStatus","VALIDATING");
        a.put("status","SUCCEEDED").put("result_digest",body.path("resultDigest").asText()).set("result_receipt",receipt);
        assertThat(service.complete(work,body)).isEqualTo(receipt);
        verifyNoInteractions(storage);verify(slots,never()).valid(anyString(),anyString(),anyString(),anyLong());
    }
    @Test void workerWorkEntryRejectsLocalValidationKind() {
        row.put("kind","VALIDATE").put("dispatch_mode","LOCAL");
        assertThatThrownBy(() -> service.work(work,false,false)).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.status).isEqualTo(400));
        verifyNoInteractions(storage);
    }
    @Test void exhaustedOutboxStillFailsItsUnstartedJob() {
        UUID jobId=UUID.randomUUID(),productId=UUID.randomUUID();
        row.put("status","FAILED").put("generation",1).put("target_id",jobId.toString());
        ObjectNode job=IngestionJson.JSON.createObjectNode().put("id",jobId.toString()).put("owner_id","owner")
                .put("status","QUEUED").put("generation",1).put("active_product_id",productId.toString()).putNull("current_attempt_id");
        IngestionService products=mock(IngestionService.class);IngestionJobService jobs=mock(IngestionJobService.class);
        when(jobs.job("owner",jobId,true)).thenReturn(job);
        when(products.error("DISPATCH_FAILED","STANDARDIZE")).thenReturn(IngestionJson.JSON.createObjectNode().put("reason","DISPATCH_FAILED"));
        when(db.transaction(any())).thenAnswer(i -> ((Supplier<?>)i.getArgument(0)).get());
        when(db.one(anyString(),any(Object[].class))).thenAnswer(i -> {
            String sql=i.getArgument(0);return sql.contains("ingest_outbox")?row:sql.contains("ingest_job")?job:null;
        });
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        new IngestionJobWorkService(db,new IngestionProperties(),products,jobs,storage,slots).failQueued(work,false,"DISPATCH_FAILED");
        verify(db).update(startsWith("UPDATE ingest_job SET status='FAILED'"),any(Object[].class));
    }
}
