package com.remotesensing.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户登录请求 DTO。
 * 用于接收前端登录表单提交的用户名和密码凭证。
 */
@Data
public class LoginRequestDTO {

    /** 登录用户名，对应 sys_user 表 username 列。不能为空。 */
    @NotBlank(message = "username 不能为空")
    private String username;

    /** 登录密码（明文），服务端进行哈希校验。不能为空。 */
    @NotBlank(message = "password 不能为空")
    private String password;
}
