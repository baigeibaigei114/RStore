package com.remotesensing.platform.ingestion.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.config.properties.IngestionProperties;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import com.remotesensing.platform.ingestion.persistence.IngestionStore;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

class IngestionJobServiceTest {
    private final UUID confirmation=UUID.randomUUID(),product=UUID.randomUUID(),jobId=UUID.randomUUID(),request=UUID.randomUUID();
    private final String hash="a".repeat(64);
    private final IngestionStore db=mock(IngestionStore.class);
    private final IngestionService products=mock(IngestionService.class);
    private final IngestionProperties config=new IngestionProperties();
    @SuppressWarnings("unchecked") private final ObjectProvider<IngestionJobService.ValidationRuntime> validation=mock(ObjectProvider.class);
    private final ObjectNode c=IngestionJson.JSON.createObjectNode(),j=IngestionJson.JSON.createObjectNode();
    private ObjectNode keyOwner;
    private ObjectNode image;
    private IngestionJobService service;
    @BeforeEach void setUp() {
        config.setEnabled(true);config.setStandardizationEnabled(true);
        when(db.object()).thenAnswer(i -> IngestionJson.JSON.createObjectNode());
        when(db.tree(isNull())).thenReturn(IngestionJson.JSON.nullNode());
        when(db.transaction(any())).thenAnswer(i -> ((Supplier<?>)i.getArgument(0)).get());
        when(db.now()).thenAnswer(i -> Instant.now());
        when(db.one(anyString(),any(Object[].class))).thenAnswer(i -> {
            String sql=i.getArgument(0);
            if(sql.contains("confirm_request_id=?"))return keyOwner;
            if(sql.contains("FROM ingest_confirmation"))return c;
            if(sql.contains("FROM ingest_job"))return j;
            if(sql.contains("FROM monitoring_region"))return IngestionJson.JSON.createObjectNode().put("version",1);
            if(sql.contains("FROM rs_image"))return image;
            return null;
        });
        c.put("id",confirmation.toString()).put("product_id",product.toString()).put("status","READY").put("confirmation_hash",hash)
                .put("monitoring_region_id",1).put("region_version",1).put("expires_at",Instant.now().plusSeconds(600).toString());
        j.put("id",jobId.toString()).put("status","QUEUED").put("owner_id","owner").putNull("image_id");
        service=new IngestionJobService(db,config,products,validation);
    }
    private ObjectNode body() {return IngestionJson.JSON.createObjectNode().put("clientRequestId",request.toString()).put("confirmationHash",hash);}
    @Test void consumedReceiptSurvivesExpiryAndUnavailableRuntime() {
        c.put("status","CONSUMED").put("job_id",jobId.toString()).put("expires_at",Instant.EPOCH.toString());
        var result=service.confirm("owner",confirmation,body());
        assertThat(result.reused()).isTrue();assertThat(result.data().path("jobId").asText()).isEqualTo(jobId.toString());
        verify(products,never()).requireRaw(any());verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void requestKeyCannotBeReboundToAnotherConfirmation() {
        keyOwner=IngestionJson.JSON.createObjectNode().put("id",UUID.randomUUID().toString()).put("confirmation_hash",hash);
        assertThatThrownBy(() -> service.confirm("owner",confirmation,body())).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.reason).isEqualTo("REQUEST_KEY_CONFLICT"));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void incompleteValidationRuntimeCannotCreateJob() {
        assertThatThrownBy(() -> service.confirm("owner",confirmation,body())).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.status).isEqualTo(503));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void expiredNewConfirmationIsRejectedWithoutMutating() {
        when(validation.getIfAvailable()).thenReturn(() -> true);
        when(products.product(anyString(),eq(product),eq(true))).thenReturn(IngestionJson.JSON.createObjectNode());
        c.put("expires_at",Instant.EPOCH.toString());
        assertThatThrownBy(() -> service.confirm("owner",confirmation,body())).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.reason).isEqualTo("CONFIRMATION_EXPIRED"));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void retryReceiptPrecedesStaleVersionAndRuntimeGate() {
        var retry=IngestionJson.JSON.createObjectNode().put("clientRequestId",request.toString()).put("expectedVersion",1).put("productId",product.toString());
        ObjectNode response=IngestionJson.JSON.createObjectNode().put("jobId",jobId.toString()).put("generation",2);
        j.put("version",9).put("status","RUNNING").putObject("request_receipts").set(request.toString(),IngestionJson.JSON.createObjectNode().put("hash",IngestionJson.sha(retry)).set("response",response));
        assertThat(service.retry("owner",jobId,retry)).isEqualTo(response);
        verify(db,never()).update(anyString(),any(Object[].class));verify(products,never()).requireRaw(any());
    }
    @Test void sameContentAndParametersAcrossProductsHaveOneIdentity() throws Exception {
        ObjectNode doc=(ObjectNode)IngestionJson.JSON.readTree("{\"schemaVersion\":1,\"archiveSha256\":\"a\",\"parserVersion\":\"p\",\"profileVersion\":\"v\",\"regionSnapshot\":{\"version\":1},\"sourceGrid\":{},\"window\":[0,0,1,1],\"calibration\":{},\"rawCalibration\":{},\"qualityRules\":{},\"productId\":\"first\",\"imageName\":\"first\"}");
        String first=IngestionJson.sha(IngestionJobService.intentDocument(doc));
        doc.put("productId","second").put("imageName","renamed");
        assertThat(IngestionJson.sha(IngestionJobService.intentDocument(doc))).isEqualTo(first);
        doc.withObject("regionSnapshot").put("version",2);
        assertThat(IngestionJson.sha(IngestionJobService.intentDocument(doc))).isNotEqualTo(first);
    }
    @ParameterizedTest
    @ValueSource(strings={"DELETED","CHANGED"})
    void unavailableAssetDoesNotBlockReadOnlyMixedHistoryButReuseStillFails(String state) {
        j.put("status","SUCCEEDED").put("image_id",7).put("generation",1).put("version",2);
        image=IngestionJson.JSON.createObjectNode().put("status","READY").putNull("deleted_at");
        if(state.equals("DELETED"))image.put("deleted_at",Instant.now().toString());
        else image.putObject("metadata_json").putObject("admission").put("status","REJECTED");
        ObjectNode active=IngestionJson.JSON.createObjectNode().put("id",UUID.randomUUID().toString()).put("owner_id","owner").put("status","QUEUED").put("generation",1).put("version",1).putNull("image_id");
        when(db.rows(anyString(),any(Object[].class))).thenReturn(java.util.List.of(j,active));
        when(db.count(anyString(),any(Object[].class))).thenReturn(2L);
        var history=service.list("owner",1,20);
        assertThat(history.path("total").asInt()).isEqualTo(2);
        assertThat(history.path("items").get(0).path("assetStatus").asText()).isEqualTo(state.equals("DELETED")?"UNAVAILABLE":"CHANGED");
        assertThat(history.path("items").get(1).path("status").asText()).isEqualTo("QUEUED");
        assertThat(service.view("owner",jobId).path("status").asText()).isEqualTo("SUCCEEDED");
        verify(db,never()).transaction(any());verify(db,never()).update(anyString(),any(Object[].class));
        c.put("status","CONSUMED").put("job_id",jobId.toString());
        assertThatThrownBy(() -> service.confirm("owner",confirmation,body())).isInstanceOfSatisfying(IngestionProblem.class,
                p -> {assertThat(p.status).isEqualTo(state.equals("DELETED")?410:409);assertThat(p.reason).isEqualTo(state.equals("DELETED")?"ASSET_UNAVAILABLE":"ASSET_CHANGED");});
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
