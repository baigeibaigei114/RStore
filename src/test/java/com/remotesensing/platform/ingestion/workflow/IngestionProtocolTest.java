package com.remotesensing.platform.ingestion.workflow;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class IngestionProtocolTest {
    @Test void equivalentNumericRepresentationsBindTheSameConfirmation() throws Exception {
        var first=IngestionJson.JSON.readTree("{\"z\":[-0.0,1e-4],\"a\":\"中文\"}");
        var second=IngestionJson.JSON.readTree("{\"a\":\"中文\",\"z\":[0,0.0001000]}");
        assertThat(IngestionJson.canonical(first)).isEqualTo("{\"a\":\"中文\",\"z\":[0,0.0001]}");
        assertThat(IngestionJson.sha(first)).isEqualTo(IngestionJson.sha(second));
    }
    @Test void duplicateFieldsAndUnknownControlFieldsAreRejected() {
        assertThatThrownBy(() -> IngestionJson.JSON.readTree("{\"productId\":1,\"productId\":2}")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> IngestionJson.fields(IngestionJson.JSON.readTree("{\"clientRequestId\":\"x\",\"owner\":\"other\"}"),"clientRequestId"))
                .isInstanceOf(com.remotesensing.platform.ingestion.api.IngestionProblem.class);
    }
    @Test void windowIdentityDoesNotRoundAwaySubpixelCoordinates() throws Exception {
        assertThat(IngestionJson.sha(IngestionJson.JSON.readTree("{\"x\":1.00000001}")))
                .isNotEqualTo(IngestionJson.sha(IngestionJson.JSON.readTree("{\"x\":1.00000002}")));
    }
}
