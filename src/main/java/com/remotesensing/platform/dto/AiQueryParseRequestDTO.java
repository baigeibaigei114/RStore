package com.remotesensing.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 自然语言影像检索解析请求。
 */
@Data
public class AiQueryParseRequestDTO {

    /** 用户输入的自然语言检索条件。 */
    @NotBlank(message = "查询文本不能为空")
    @Size(max = 500, message = "查询文本长度不能超过 500")
    private String text;
}
