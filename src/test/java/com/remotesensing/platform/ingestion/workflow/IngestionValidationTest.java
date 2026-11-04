package com.remotesensing.platform.ingestion.workflow;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.ingestion.api.IngestionProblem;
import org.junit.jupiter.api.Test;

class IngestionValidationTest {
    private ObjectNode parse(String value) throws Exception {return (ObjectNode)IngestionJson.JSON.readTree(value);}
    @Test void candidateManifestMustBindArchiveAndIntegerWindow() throws Exception {
        ObjectNode document=parse("{\"parserVersion\":\"safe-l2a-probe-v1\",\"sourceGrid\":{\"crs\":\"EPSG:32652\",\"transform\":[10,0,300000,0,-10,4200000]},\"calibration\":{},\"rawCalibration\":{\"quantificationValue\":10000,\"boaAddOffsets\":{}}}");
        ObjectNode job=parse("{\"archive_sha256\":\"a\",\"window\":[4000,4000,512,512]}");job.put("intent_document",document.toString());
        ObjectNode manifest=parse("{\"crs\":\"EPSG:32652\",\"transform\":[10,0,340000,0,-10,4160000],\"window\":[4000,4000,512,512],\"calibration\":{},\"sha256\":\"tiff\",\"safe\":{\"archiveSha256\":\"a\",\"parserVersion\":\"safe-l2a-probe-v1\",\"quantificationValue\":10000,\"boaAddOffsets\":{}}}");
        ObjectNode tiff=parse("{\"sha256\":\"tiff\"}");
        IngestionValidationService.verifyManifest(job,manifest,tiff);
        manifest.withObject("safe").put("archiveSha256","different");
        assertThatThrownBy(() -> IngestionValidationService.verifyManifest(job,manifest,tiff)).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.reason).isEqualTo("INPUT_HASH_MISMATCH"));
    }
    @Test void shiftedCandidateGridIsRejectedEvenWhenManifestHashMatches() throws Exception {
        ObjectNode document=parse("{\"parserVersion\":\"p\",\"sourceGrid\":{\"crs\":\"EPSG:32652\",\"transform\":[10,0,300000,0,-10,4200000]},\"calibration\":{},\"rawCalibration\":{\"quantificationValue\":10000,\"boaAddOffsets\":{}}}");
        ObjectNode job=parse("{\"archive_sha256\":\"a\",\"window\":[0,0,512,512]}");job.put("intent_document",document.toString());
        ObjectNode manifest=parse("{\"crs\":\"EPSG:32652\",\"transform\":[10,0,300010,0,-10,4200000],\"window\":[0,0,512,512],\"calibration\":{},\"sha256\":\"tiff\",\"safe\":{\"archiveSha256\":\"a\",\"parserVersion\":\"p\",\"quantificationValue\":10000,\"boaAddOffsets\":{}}}");
        assertThatThrownBy(() -> IngestionValidationService.verifyManifest(job,manifest,parse("{\"sha256\":\"tiff\"}"))).isInstanceOfSatisfying(IngestionProblem.class,p -> assertThat(p.reason).isEqualTo("INVALID_GRID"));
    }
}
