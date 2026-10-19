package com.remotesensing.platform.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MonitoringRegionDTO {
    @NotBlank
    @Size(max = 100)
    private String name;
    @NotNull
    private JsonNode geometry;
}
