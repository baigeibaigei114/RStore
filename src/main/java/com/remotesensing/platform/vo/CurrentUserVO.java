package com.remotesensing.platform.vo;

import lombok.Data;

/**
 * 当前登录用户信息 VO。
 * 用于返回当前已认证用户的基本信息，供前端页面展示用户状态和权限控制。
 */
@Data
public class CurrentUserVO {

    /** 用户主键 ID，对应 sys_user.id。 */
    private String userId;

    /** 登录用户名，对应 sys_user.username。 */
    private String username;

    /** 用户显示名称，对应 sys_user.display_name。 */
    private String displayName;

    /** 用户角色，如 "ADMIN" / "USER"，对应 sys_user.role。 */
    private String role;
}
