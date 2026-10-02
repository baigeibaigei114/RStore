package com.remotesensing.platform.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class InputAdmissionTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    private GeoTiffMetadataVO metadata() {
        var m = new GeoTiffMetadataVO();
        m.setWidth(2); m.setHeight(2); m.setBandCount(4); m.setCrs("EPSG:32650");
        m.setBandDescriptions(List.of("B02", "B03", "B04", "B08"));
        m.setDtypes(List.of("float32", "float32", "float32", "float32"));
        m.setInputUnits("surface_reflectance"); m.setNodata(BigDecimal.valueOf(-9999));
        m.setScales(List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        m.setOffsets(List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        m.setTransform(List.of(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.valueOf(390000),
                BigDecimal.ZERO, BigDecimal.valueOf(-10), BigDecimal.valueOf(3200000)));
        return m;
    }

    private ObjectNode manifest(Path file) throws Exception {
        var m = (ObjectNode) json.readTree("""
                {"schemaVersion":1,"productType":"SENTINEL2_L2A","preprocessingVersion":"s2-reflectance-v1",
                 "sourceId":"unit-test-only","captureTime":"2024-01-01T00:00:00Z",
                 "inputUnits":"surface_reflectance","bandOrder":["B02","B03","B04","B08"],
                 "crs":"EPSG:32650","width":2,"height":2,"transform":[10,0,390000,0,-10,3200000],
                 "sclValidClasses":[4,5,6,7],"sclResampling":"nearest","window":[0,0,2,2],
                 "calibration":{"blue":{"scale":0.0001,"offset":0},"green":{"scale":0.0001,"offset":0},
                   "red":{"scale":0.0001,"offset":0},"nir":{"scale":0.0001,"offset":0}}}
                """);
        m.put("sha256", InputAdmission.sha256(file));
        return m;
    }

    @Test void acceptsMatchingContractAndCreatesIndependentSnapshot() throws Exception {
        Path file = Files.writeString(directory.resolve("test.bin"), "test only: raster parser is tested separately");
        var source = manifest(file);
        var admission = InputAdmission.validate(json.writeValueAsBytes(source), file, metadata());
        var stored = json.createObjectNode().set("admission", admission);
        var snapshot = InputAdmission.snapshot(stored.toString(), 10L, Map.of("redBand", 3, "nirBand", 4));
        source.put("sourceId", "changed");
        assertThat(snapshot.path("manifest").path("sourceId").asText()).isEqualTo("unit-test-only");
        assertThat(snapshot.path("algorithmVersion").asText()).isEqualTo("ndvi-v1");
        assertThat(snapshot.path("imageId").asLong()).isEqualTo(10);
    }

    @Test void rejectsBadManifestFields() throws Exception {
        Path file = Files.writeString(directory.resolve("test.bin"), "test");
        for (String field : List.of("sha256", "crs", "captureTime", "calibration", "window",
                "schemaVersion", "bandOrder", "sclValidClasses", "inputUnits", "sourceId", "preprocessingVersion")) {
            var m = manifest(file);
            m.remove(field);
            assertThatThrownBy(() -> InputAdmission.validate(json.writeValueAsBytes(m), file, metadata()))
                    .as(field).isInstanceOf(BusinessException.class);
        }
        var m = manifest(file);
        m.put("productType", "SYNTHETIC");
        assertThatThrownBy(() -> InputAdmission.validate(json.writeValueAsBytes(m), file, metadata()))
                .hasMessageContaining("productType");
        m.put("productType", "SENTINEL2_L2A");
        m.put("schemaVersion", 4294967297L);
        assertThatThrownBy(() -> InputAdmission.validate(json.writeValueAsBytes(m), file, metadata()))
                .hasMessageContaining("契约版本");
        m.put("schemaVersion", 1);
        Files.writeString(file, "tampered");
        assertThatThrownBy(() -> InputAdmission.validate(json.writeValueAsBytes(m), file, metadata()))
                .hasMessageContaining("SHA256");
    }

    @Test void rejectsMismatchedRasterAndUnverifiedInput() throws Exception {
        Path file = Files.writeString(directory.resolve("test.bin"), "test");
        var bytes = json.writeValueAsBytes(manifest(file));
        var m = metadata(); m.setInputUnits(null);
        assertThatThrownBy(() -> InputAdmission.validate(bytes, file, m)).hasMessageContaining("单位");
        final var large = metadata(); large.setWidth(5000);
        assertThatThrownBy(() -> InputAdmission.validate(bytes, file, large)).hasMessageContaining("尺寸");
        assertThatThrownBy(() -> InputAdmission.snapshot(null, 1L, Map.of("redBand", 3, "nirBand", 4)))
                .hasMessageContaining("标准化");
        assertThatThrownBy(() -> InputAdmission.validate(new byte[65537], file, metadata()))
                .hasMessageContaining("65536");
    }
}
