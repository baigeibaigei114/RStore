package com.remotesensing.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI Plan 创建请求。
 */
@Data
public class AiPlanCreateDTO {

    @NotBlank(message = "需求描述不能为空")
    @Size(max = 1000, message = "需求描述长度不能超过 1000")
    private String text;
}
