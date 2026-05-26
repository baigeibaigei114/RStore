package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import com.remotesensing.platform.vo.ImageBandCapabilityVO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageBandCapabilityServiceImplTest {

    private final ImageBandCapabilityServiceImpl service = new ImageBandCapabilityServiceImpl(new ObjectMapper());

    @Test
    void rgbImageShouldExposeRgbMappingButNotNdvi() {
        GeoTiffMetadataVO metadata = new GeoTiffMetadataVO();
        metadata.setBandCount(3);
        metadata.setColorInterpretations(List.of("red", "green", "blue"));

        GeoTiffMetadataVO enriched = service.enrichMetadata(metadata);

        assertThat(enriched.getBandMapping()).containsEntry("red", 1)
                .containsEntry("green", 2)
                .containsEntry("blue", 3);
        assertThat(enriched.getSupportedTaskTypes()).isEmpty();
    }

    @Test
    void trustedFourBandImageShouldSupportNdviAndNdwi() {
        RsImage image = new RsImage();
        image.setMetadataJson("""
                {
                  "bandCount": 4,
                  "bandMapping": {"blue": 1, "green": 2, "red": 3, "nir": 4},
                  "bandMappingSource": "SYSTEM_STAC_SENTINEL2",
                  "bandMappingConfidence": "HIGH"
                }
                """);

        ImageBandCapabilityVO capability = service.resolve(image);

        assertThat(capability.supportedTaskTypes()).containsExactly("NDVI", "NDWI");
    }

    @Test
    void taskParamsShouldUseTrustedMappingWhenMissing() {
        RsImage image = new RsImage();
        image.setMetadataJson("""
                {
                  "bandCount": 4,
                  "bandMapping": {"blue": 1, "green": 2, "red": 3, "nir": 4},
                  "bandMappingSource": "SYSTEM_STAC_SENTINEL2",
                  "bandMappingConfidence": "HIGH"
                }
                """);
        Map<String, Object> params = new LinkedHashMap<>();

        service.validateAndFillTaskParams(image, TaskType.NDVI, params);

        assertThat(params).containsEntry("redBand", 3)
                .containsEntry("nirBand", 4);
    }

    @Test
    void taskParamsShouldRejectUntrustedMapping() {
        RsImage image = new RsImage();
        image.setMetadataJson("{\"bandCount\": 3}");

        assertThatThrownBy(() -> service.validateAndFillTaskParams(image, TaskType.NDVI, new LinkedHashMap<>()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能执行 NDVI");
    }

    @Test
    void taskParamsShouldRejectUserOverrideAgainstTrustedMapping() {
        RsImage image = new RsImage();
        image.setMetadataJson("""
                {
                  "bandCount": 4,
                  "bandMapping": {"blue": 1, "green": 2, "red": 3, "nir": 4},
                  "bandMappingSource": "SYSTEM_STAC_SENTINEL2",
                  "bandMappingConfidence": "HIGH"
                }
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("redBand", 1);
        params.put("nirBand", 4);

        assertThatThrownBy(() -> service.validateAndFillTaskParams(image, TaskType.NDVI, params))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("redBand");
    }

    @Test
    void metadataMappingShouldIgnoreBandOutOfRange() {
        RsImage image = new RsImage();
        image.setBandCount(3);
        image.setMetadataJson("""
                {
                  "bandCount": 3,
                  "bandMapping": {"green": 1, "red": 2, "nir": 4},
                  "bandMappingSource": "USER_CONFIRMED",
                  "bandMappingConfidence": "MEDIUM"
                }
                """);

        ImageBandCapabilityVO capability = service.resolve(image);

        assertThat(capability.bandMapping()).containsEntry("green", 1)
                .containsEntry("red", 2)
                .doesNotContainKey("nir");
        assertThat(capability.supportedTaskTypes()).isEmpty();
        assertThatThrownBy(() -> service.validateAndFillTaskParams(image, TaskType.NDVI, new LinkedHashMap<>()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能执行 NDVI");
    }
}
