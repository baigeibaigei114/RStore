package com.remotesensing.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequestDTO {

    @NotBlank(message = "username 不能为空")
    private String username;

    @NotBlank(message = "password 不能为空")
    private String password;
}
