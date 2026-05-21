package com.remotesensing.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 影像可见性更新请求 DTO。
 * 用于修改遥感影像的可见性范围（如 PUBLIC / PRIVATE），控制其他用户能否查看该影像。
 */
@Data
public class RsImageVisibilityUpdateDTO {

    /** 目标可见性值："PUBLIC"（公开）或 "PRIVATE"（私有），对应 rs_image 表 visibility 列。不能为空。 */
    @NotBlank(message = "visibility 不能为空")
    private String visibility;
}
