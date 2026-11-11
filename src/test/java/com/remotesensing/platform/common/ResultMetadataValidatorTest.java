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

    @Test
    void validatesChangeAgainstBothSourceIdsGridAndTheCommonDenominator() {
        String params = """
                {"beforeImageId":7,"afterImageId":8,"band":1,"threshold":0.5,
                 "changeSourceSnapshot":{"width":4,"height":2}}
                """;
        String metadata = """
                {"schemaVersion":1,"algorithm":"CHANGE_DETECTION","scope":"cropped_raster",
                 "comparison":{"beforeImageId":7,"afterImageId":8,"band":1,"threshold":0.5},
                 "grid":{"width":4,"height":2,"alignment":"same_grid"},
                 "statistics":{"totalPixelCount":8,"validPixelCount":5,"invalidPixelCount":3,"validPixelRatio":0.625,
                               "min":0,"max":1,"mean":0.4,"changedPixelCount":2,"unchangedPixelCount":3,"changeRatio":0.4}}
                """;
        assertDoesNotThrow(() -> ResultMetadataValidator.validate(parse(metadata), "CHANGE_DETECTION", params));
        assertDoesNotThrow(() -> ResultMetadataValidator.validate(null, "CHANGE_DETECTION", params));
        for (String invalid : new String[]{
                metadata.replace("\"beforeImageId\":7", "\"beforeImageId\":6"),
                metadata.replace("\"threshold\":0.5", "\"threshold\":0.6"),
                metadata.replace("\"width\":4", "\"width\":5"),
                metadata.replace("\"changeRatio\":0.4", "\"changeRatio\":0.25")
        }) {
            assertThrows(BusinessException.class, () -> ResultMetadataValidator.validate(parse(invalid), "CHANGE_DETECTION", params));
        }
    }
}
