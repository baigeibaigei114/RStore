package com.remotesensing.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RsImageVisibilityUpdateDTO {

    @NotBlank(message = "visibility 不能为空")
    private String visibility;
}
