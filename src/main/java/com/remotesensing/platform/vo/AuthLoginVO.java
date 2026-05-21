package com.remotesensing.platform.vo;

import lombok.Data;

/**
 * 登录认证响应 VO。
 * 用户登录成功后返回的认证信息，包含 JWT Token 及用户基本信息。
 */
@Data
public class AuthLoginVO {

    /** JWT 访问令牌（Access Token），后续请求需在 Authorization 头中携带。 */
    private String accessToken;

    /** Token 类型，固定为 "Bearer"。 */
    private String tokenType;

    /** 用户主键 ID，对应 sys_user.id。 */
    private String userId;

    /** 登录用户名，对应 sys_user.username。 */
    private String username;

    /** 用户显示名称，对应 sys_user.display_name。 */
    private String displayName;

    /** 用户角色，如 "ADMIN" / "USER"，对应 sys_user.role。 */
    private String role;
}
