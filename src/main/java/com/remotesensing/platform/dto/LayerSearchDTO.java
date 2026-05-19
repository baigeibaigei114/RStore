package com.remotesensing.platform.dto;

import lombok.Data;

@Data
public class LayerSearchDTO {

    private String taskType;
    private Long imageId;
    private String keyword;
}
