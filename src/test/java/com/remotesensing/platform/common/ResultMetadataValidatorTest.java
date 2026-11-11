package com.remotesensing.platform.common;

import static com.remotesensing.platform.agent.AgentJson.parse;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.exception.BusinessException;
import org.junit.jupiter.api.Test;

class ResultMetadataValidatorTest {
    private static final String PARAMS = "{\"greenBand\":2,\"nirBand\":4}";
    private static final JsonNode NDWI = parse("""
            {"schemaVersion":1,"algorithm":"NDWI","scope":"cropped_raster","bandMapping":{"greenBand":2,"nirBand":4},
             "statistics":{"totalPixelCount":4,"validPixelCount":3,"invalidPixelCount":1,
                           "validPixelRatio":0.75,"min":-0.5,"max":0.5,"mean":0}}
            """);

    @Test
    void acceptsMaskAwareNdwiAndKeepsOlderCallbacksWithoutStatisticsCompatible() {
        assertDoesNotThrow(() -> ResultMetadataValidator.validate(NDWI, "NDWI", PARAMS));
        assertDoesNotThrow(() -> ResultMetadataValidator.validate(NDWI, "NDWI", "{\"greenBand\":\"2\",\"nirBand\":4.0}"));
        assertDoesNotThrow(() -> ResultMetadataValidator.validate(NDWI, "NDWI", "{}"));
        assertDoesNotThrow(() -> ResultMetadataValidator.validate(null, "NDWI", PARAMS));
    }

    @Test
    void rejectsTheWrongTaskAlgorithmBandsAndDenominator() {
        assertThrows(BusinessException.class, () -> ResultMetadataValidator.validate(NDWI, "NDVI", PARAMS));
        assertThrows(BusinessException.class, () -> ResultMetadataValidator.validate(NDWI, "NDWI", "{\"greenBand\":1,\"nirBand\":4}"));
        JsonNode wrongRatio = parse(NDWI.toString().replace("0.75", "0.50"));
        assertThrows(BusinessException.class, () -> ResultMetadataValidator.validate(wrongRatio, "NDWI", PARAMS));
    }
}
