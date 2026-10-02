package com.remotesensing.platform.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NdviMetadataValidatorTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode result() throws Exception {
        return (ObjectNode) json.readTree("""
            {"schemaVersion":2,"scope":"monitoring_region","algorithm":"NDVI",
             "regionSnapshot":{"regionId":1,"version":1},"coverageRatio":1,
             "bandMapping":{"redBand":3,"nirBand":4},
             "statistics":{"totalPixelCount":4,"validPixelCount":3,"invalidPixelCount":1,
                           "validPixelRatio":0.75,"min":0,"max":0.5,"mean":0.3333333}}
            """);
    }

    @Test
    void acceptsMatchingRegionalResult() throws Exception {
        var result = result();
        NdviMetadataValidator.validate(result, "NDVI");
        NdviMetadataValidator.validateTaskRegion(result, "{\"regionSnapshot\":{\"regionId\":1,\"version\":1}}");
    }

    @Test
    void rejectsWrongRegionAndMissingStatistics() throws Exception {
        String params = "{\"regionSnapshot\":{\"regionId\":1,\"version\":2}}";
        assertThatThrownBy(() -> NdviMetadataValidator.validateTaskRegion(result(), params))
                .hasMessageContaining("不一致");
        assertThatThrownBy(() -> NdviMetadataValidator.validateTaskRegion(null, params))
                .hasMessageContaining("v2");
        assertThatThrownBy(() -> NdviMetadataValidator.validateTaskRegion(result(), "{}"))
                .hasMessageContaining("矩形");
    }

    @Test
    void rejectsBadCoverageAndCounts() throws Exception {
        var result = result().put("coverageRatio", 0.8);
        assertThatThrownBy(() -> NdviMetadataValidator.validate(result, "NDVI")).hasMessageContaining("覆盖");
        result.put("coverageRatio", 1);
        ((ObjectNode) result.get("statistics")).put("totalPixelCount", 3);
        assertThatThrownBy(() -> NdviMetadataValidator.validate(result, "NDVI")).hasMessageContaining("数量");
    }
}
